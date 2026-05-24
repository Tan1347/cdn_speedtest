package org.tan.cdntest

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.appcompat.app.AlertDialog
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONArray

class CdnSnifferActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnGo: MaterialButton
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnSniff: MaterialButton
    private lateinit var chipGroup: ChipGroup
    private lateinit var layoutResultBar: View
    private lateinit var tvResultCount: TextView
    private lateinit var btnClear: TextView
    private lateinit var rvResults: RecyclerView

    private lateinit var adapter: CdnSnifferAdapter
    private var allResults = mutableListOf<SnifferResult>()
    private var currentFilter = "all"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cdn_sniffer)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        etUrl = findViewById(R.id.etUrl)
        btnGo = findViewById(R.id.btnGo)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        btnSniff = findViewById(R.id.btnSniff)
        chipGroup = findViewById(R.id.chipGroup)
        layoutResultBar = findViewById(R.id.layoutResultBar)
        tvResultCount = findViewById(R.id.tvResultCount)
        btnClear = findViewById(R.id.btnClear)
        rvResults = findViewById(R.id.rvResults)

        setupWebView()
        setupUrlInput()
        setupSniffButton()
        setupChipFilter()
        setupResultList()

        btnClear.setOnClickListener {
            allResults.clear()
            adapter.updateData(emptyList())
            chipGroup.visibility = View.GONE
            layoutResultBar.visibility = View.GONE
            rvResults.visibility = View.GONE
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            databaseEnabled = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (isDownloadUrl(url)) {
                    showDownloadConfirm(url, guessMimeType(url))
                    return true  // 拦截，不交给系统
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                etUrl.setText(url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }
        }

        // 拦截下载链接
        webView.setDownloadListener { url, _, _, mimeType, _ ->
            showDownloadConfirm(url, mimeType)
        }
    }

    private fun setupUrlInput() {
        val loadUrl = {
            var url = etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                hideKeyboard()
                webView.loadUrl(url)
            }
        }

        btnGo.setOnClickListener { loadUrl() }

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl()
                true
            } else false
        }
    }

    private fun setupSniffButton() {
        btnSniff.setOnClickListener {
            sniffResources()
        }
    }

    private fun setupChipFilter() {
        val chipAll = findViewById<Chip>(R.id.chipAll)
        chipAll.isChecked = true

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                currentFilter = when (checkedIds[0]) {
                    R.id.chipAll -> "all"
                    R.id.chipImage -> "image"
                    R.id.chipVideo -> "video"
                    R.id.chipDownload -> "download"
                    R.id.chipOther -> "other"
                    else -> "all"
                }
                applyFilter()
            }
        }
    }

    private fun setupResultList() {
        adapter = CdnSnifferAdapter { url -> copyToClipboard(url) }
        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter
    }

    private fun sniffResources() {
        val js = """
        (function() {
          var results = [];
          var seen = {};
          function add(url, type) {
            if (url && url.startsWith('http') && !seen[url]) {
              seen[url] = true;
              results.push({url: url, type: type});
            }
          }
          document.querySelectorAll('img[src]').forEach(function(e) {
            add(e.src, 'image');
          });
          document.querySelectorAll('video[src], video source[src]').forEach(function(e) {
            add(e.src, 'video');
          });
          document.querySelectorAll('audio[src], audio source[src]').forEach(function(e) {
            add(e.src, 'audio');
          });
          document.querySelectorAll('a[href]').forEach(function(e) {
            var h = e.href;
            if (/\.(jpg|jpeg|png|gif|webp|svg|bmp|ico)(\?|#|$)/i.test(h)) add(h, 'image');
            else if (/\.(mp4|avi|mkv|mov|flv|wmv|webm|m4v)(\?|#|$)/i.test(h)) add(h, 'video');
            else if (/\.(mp3|wav|ogg|flac|aac|m4a)(\?|#|$)/i.test(h)) add(h, 'audio');
            else if (/\.(apk|zip|rar|7z|tar|gz|dmg|exe|msi|deb|rpm)(\?|#|$)/i.test(h)) add(h, 'download');
          });
          document.querySelectorAll('[style]').forEach(function(e) {
            var bg = e.style.backgroundImage;
            if (bg && bg.indexOf('url(') === 0) {
              var u = bg.replace(/^url\(["']?/, '').replace(/["']?\)$/, '');
              add(u, 'image');
            }
          });
          return JSON.stringify(results);
        })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            try {
                val cleanResult = result.removeSurrounding("\"")
                    .replace("\\\"", "\"")
                    .replace("\\/", "/")
                val jsonArray = JSONArray(cleanResult)
                allResults.clear()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    allResults.add(SnifferResult(obj.getString("url"), obj.getString("type")))
                }
                showResults()
            } catch (e: Exception) {
                Toast.makeText(this, "嗅探失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addDownloadResult(url: String) {
        val exists = allResults.any { it.url == url }
        if (!exists) {
            val type = when {
                url.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|svg|bmp)(\\?|#|$).*", RegexOption.IGNORE_CASE)) -> "image"
                url.matches(Regex(".*\\.(mp4|avi|mkv|mov|flv|wmv|webm)(\\?|#|$).*", RegexOption.IGNORE_CASE)) -> "video"
                url.matches(Regex(".*\\.(mp3|wav|ogg|flac|aac)(\\?|#|$).*", RegexOption.IGNORE_CASE)) -> "audio"
                url.matches(Regex(".*\\.(apk|zip|rar|7z|tar|gz)(\\?|#|$).*", RegexOption.IGNORE_CASE)) -> "download"
                else -> "download"
            }
            allResults.add(0, SnifferResult(url, type))
            showResults()
            Toast.makeText(this, "已捕获下载链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isDownloadUrl(url: String): Boolean {
        val lower = url.lowercase()
        val extensions = listOf(
            ".apk", ".zip", ".rar", ".7z", ".tar", ".gz", ".dmg", ".exe", ".msi", ".deb", ".rpm",
            ".mp4", ".avi", ".mkv", ".mov", ".flv", ".wmv", ".webm", ".m4v",
            ".mp3", ".wav", ".ogg", ".flac", ".aac", ".m4a",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"
        )
        return extensions.any { ext ->
            val idx = lower.indexOf(ext)
            idx > 0 && (idx + ext.length >= lower.length || lower[idx + ext.length] == '?' || lower[idx + ext.length] == '#')
        }
    }

    private fun guessMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".apk") -> "application/vnd.android.package-archive"
            lower.contains(".zip") || lower.contains(".rar") || lower.contains(".7z") -> "application/zip"
            lower.contains(".mp4") || lower.contains(".avi") || lower.contains(".mkv") -> "video/*"
            lower.contains(".mp3") || lower.contains(".wav") || lower.contains(".ogg") -> "audio/*"
            lower.contains(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun showDownloadConfirm(url: String, mimeType: String?) {
        val fileName = Uri.parse(url).lastPathSegment ?: "download"
        val cleanName = fileName.split("?")[0].split("#")[0]

        addDownloadResult(url)

        AlertDialog.Builder(this)
            .setTitle("下载确认")
            .setMessage("是否下载以下文件？\n\n$cleanName")
            .setPositiveButton("下载") { _, _ ->
                startDownload(url, mimeType)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startDownload(url: String, mimeType: String?) {
        try {
            val fileName = Uri.parse(url).lastPathSegment ?: "download_${System.currentTimeMillis()}"
            val cleanName = fileName.split("?")[0].split("#")[0]
            val downloadDir = DownloadHelper.getDownloadDir(this)

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(cleanName)
                .setDescription("正在下载到应用目录...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(java.io.File(downloadDir, cleanName)))
                .setMimeType(mimeType ?: "application/octet-stream")

            dm.enqueue(request)
            Toast.makeText(this, "开始下载: $cleanName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showResults() {
        chipGroup.visibility = View.VISIBLE
        layoutResultBar.visibility = View.VISIBLE
        rvResults.visibility = View.VISIBLE
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = if (currentFilter == "all") {
            allResults
        } else {
            allResults.filter { it.type == currentFilter }
        }
        adapter.updateData(filtered)
        tvResultCount.text = "共 ${filtered.size} 个资源"
    }

    private fun copyToClipboard(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etUrl.windowToken, 0)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
