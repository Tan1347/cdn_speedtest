package org.tan.cdntest

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray

class CdnSnifferActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnCert: ImageButton
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutSnifferPanel: View
    private lateinit var chipGroup: ChipGroup
    private lateinit var tvResultCount: TextView
    private lateinit var btnClear: TextView
    private lateinit var rvResults: RecyclerView

    private lateinit var adapter: CdnSnifferAdapter
    private var allResults = mutableListOf<SnifferResult>()
    private var currentFilter = "all"
    private var currentUrl = ""
    private var hasSslError = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(this)
        AppLogger.i(this, "CdnSniffer", "嗅探页面启动")
        setContentView(R.layout.activity_cdn_sniffer)

        etUrl = findViewById(R.id.etUrl)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnCert = findViewById(R.id.btnCert)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        layoutSnifferPanel = findViewById(R.id.layoutSnifferPanel)
        chipGroup = findViewById(R.id.chipGroup)
        tvResultCount = findViewById(R.id.tvResultCount)
        btnClear = findViewById(R.id.btnClear)
        rvResults = findViewById(R.id.rvResults)

        setupWebView()
        setupUrlInput()
        setupBottomNav()
        setupChipFilter()
        setupResultList()
        setupClearButton()

        // 从 MainActivity 传入当前 URL
        val passedUrl = intent.getStringExtra("url")
        if (!passedUrl.isNullOrEmpty()) {
            webView.loadUrl(passedUrl)
            etUrl.setText(passedUrl)
        }
    }

    override fun onResume() {
        super.onResume()
        webView.settings.userAgentString = UserAgentHelper.getCurrentUa(this)
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
            @Suppress("DEPRECATION")
            databaseEnabled = true
            userAgentString = UserAgentHelper.getCurrentUa(this@CdnSnifferActivity)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (isDownloadUrl(url)) {
                    if (url.lowercase().contains(".m3u8")) {
                        showVideoDownloadDialog(SnifferResult(url = url, type = "video", isM3u8 = true))
                    } else {
                        showDownloadConfirm(url, guessMimeType(url))
                    }
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                hasSslError = false
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                currentUrl = url ?: ""
                etUrl.setText(url)
                updateCertIcon(url)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                hasSslError = true
                AlertDialog.Builder(this@CdnSnifferActivity)
                    .setTitle("SSL 证书警告")
                    .setMessage("此网站的证书不受信任，是否继续？")
                    .setPositiveButton("继续") { _, _ -> handler.proceed() }
                    .setNegativeButton("取消") { _, _ -> handler.cancel() }
                    .show()
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

        webView.setDownloadListener { url, _, _, mimeType, _ ->
            if (url.lowercase().contains(".m3u8")) {
                showVideoDownloadDialog(SnifferResult(url = url, type = "video", isM3u8 = true))
            } else {
                showDownloadConfirm(url, mimeType)
            }
        }
    }

    private fun setupUrlInput() {
        val loadUrl = {
            val input = etUrl.text.toString().trim()
            if (input.isNotEmpty()) {
                hideKeyboard()
                if (SearchEngineHelper.isUrl(input)) {
                    var url = input
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    webView.loadUrl(url)
                } else {
                    val searchUrl = SearchEngineHelper.buildSearchUrl(this, input)
                    webView.loadUrl(searchUrl)
                }
            }
        }

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                loadUrl()
                true
            } else false
        }

        btnRefresh.setOnClickListener {
            webView.reload()
        }

        btnCert.setOnClickListener {
            showCertDialog()
        }
    }

    private fun setupBottomNav() {
        findViewById<ImageButton>(R.id.btnGoBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        findViewById<ImageButton>(R.id.btnGoForward).setOnClickListener {
            if (webView.canGoForward()) webView.goForward()
        }
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener {
            webView.loadUrl("https://speedtest.2026524.xyz/")
        }
        findViewById<ImageButton>(R.id.btnSniffer).setOnClickListener {
            sniffResources()
        }
        findViewById<ImageButton>(R.id.btnMore).setOnClickListener {
            showMoreMenu(it)
        }
    }

    private fun showMoreMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "设置")
        popup.menu.add(0, 2, 1, "下载管理")
        popup.menu.add(0, 3, 2, "分享链接")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, MoreActivity::class.java))
                2 -> startActivity(Intent(this, DownloadManagerActivity::class.java))
                3 -> shareUrl()
            }
            true
        }
        popup.show()
    }

    private fun shareUrl() {
        if (currentUrl.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, currentUrl)
            startActivity(Intent.createChooser(intent, "分享链接"))
        }
    }

    private fun updateCertIcon(url: String?) {
        if (url == null) return
        val icon = when {
            url.startsWith("https://") && !hasSslError && webView.certificate != null -> R.drawable.ic_lock_green
            url.startsWith("https://") -> R.drawable.ic_lock_error
            else -> R.drawable.ic_lock_open
        }
        btnCert.setImageResource(icon)
    }

    private fun showCertDialog() {
        val message = if (currentUrl.startsWith("https://")) {
            val cert = webView.certificate
            if (cert != null) {
                val info = CertHelper.parseCert(cert)
                if (info != null) CertHelper.formatCertInfo(info) else "无法解析证书信息"
            } else {
                "无法获取证书信息"
            }
        } else {
            "当前页面不是 HTTPS 连接"
        }

        val cookies = CookieManager.getInstance().getCookie(currentUrl) ?: "无"
        val cookieSummary = if (cookies.length > 300) cookies.substring(0, 300) + "..." else cookies

        AlertDialog.Builder(this)
            .setTitle("网站信息")
            .setMessage("$message\n\nCookie:\n$cookieSummary")
            .setPositiveButton("复制 Cookie") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("cookie", cookies))
                Toast.makeText(this, "已复制 Cookie", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
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
        adapter = CdnSnifferAdapter(
            onCopy = { url -> copyToClipboard(url) },
            onDownload = { result ->
                if (result.type == "video") {
                    showVideoDownloadDialog(result)
                } else {
                    showDownloadConfirm(result.url, guessMimeType(result.url))
                }
            }
        )
        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter
    }

    private fun setupClearButton() {
        btnClear.setOnClickListener {
            allResults.clear()
            adapter.updateData(emptyList())
            layoutSnifferPanel.visibility = View.GONE
        }
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
          document.querySelectorAll('img[src]').forEach(function(e) { add(e.src, 'image'); });
          document.querySelectorAll('video[src], video source[src]').forEach(function(e) { add(e.src, 'video'); });
          document.querySelectorAll('audio[src], audio source[src]').forEach(function(e) { add(e.src, 'audio'); });
          document.querySelectorAll('a[href]').forEach(function(e) {
            var h = e.href;
            if (/\.(jpg|jpeg|png|gif|webp|svg|bmp|ico)(\?|#|$)/i.test(h)) add(h, 'image');
            else if (/\.(mp4|avi|mkv|mov|flv|wmv|webm|m4v)(\?|#|$)/i.test(h)) add(h, 'video');
            else if (/\.(m3u8)(\?|#|$)/i.test(h)) add(h, 'video');
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
                    .replace("\\\"", "\"").replace("\\/", "/")
                val jsonArray = JSONArray(cleanResult)
                allResults.clear()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val url = obj.getString("url")
                    val type = obj.getString("type")
                    val isM3u8 = url.lowercase().contains(".m3u8")
                    allResults.add(SnifferResult(url = url, type = type, isM3u8 = isM3u8))
                }
                layoutSnifferPanel.visibility = View.VISIBLE
                applyFilter()
                AppLogger.i(this, "CdnSniffer", "嗅探完成，发现 ${allResults.size} 个资源")
                fetchVideoInfoForResults()
            } catch (e: Exception) {
                Toast.makeText(this, "嗅探失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchVideoInfoForResults() {
        val videoResults = allResults.filter { it.type == "video" && (it.duration == 0L || it.isM3u8) }
        if (videoResults.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            for (result in videoResults) {
                try {
                    val info = VideoInfoFetcher.fetch(result.url, result.isM3u8)
                    if (info != null) {
                        val idx = allResults.indexOfFirst { it.url == result.url }
                        if (idx >= 0) {
                            allResults[idx] = result.copy(
                                duration = info.duration,
                                estimatedSize = info.estimatedSize,
                                thumbnail = info.thumbnail
                            )
                            launch(Dispatchers.Main) { adapter.updateData(getFilteredResults()) }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun getFilteredResults(): List<SnifferResult> {
        return if (currentFilter == "all") allResults else allResults.filter { it.type == currentFilter }
    }

    private fun addDownloadResult(url: String) {
        if (!allResults.any { it.url == url }) {
            val type = when {
                url.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp|svg|bmp)(\\?|#|$).*", RegexOption.IGNORE_CASE)) -> "image"
                url.matches(Regex(".*\\.(mp4|avi|mkv|mov|flv|wmv|webm)(\\?|#|$).*", RegexOption.IGNORE_CASE)) -> "video"
                url.matches(Regex(".*\\.(m3u8)(\\?|#|$).*", RegexOption.IGNORE_CASE)) -> "video"
                url.matches(Regex(".*\\.(mp3|wav|ogg|flac|aac)(\\?|#|$).*", RegexOption.IGNORE_CASE)) -> "audio"
                url.matches(Regex(".*\\.(apk|zip|rar|7z|tar|gz)(\\?|#|$).*", RegexOption.IGNORE_CASE)) -> "download"
                else -> "download"
            }
            val isM3u8 = url.lowercase().contains(".m3u8")
            allResults.add(0, SnifferResult(url = url, type = type, isM3u8 = isM3u8))
            layoutSnifferPanel.visibility = View.VISIBLE
            applyFilter()
        }
    }

    private fun applyFilter() {
        adapter.updateData(getFilteredResults())
        tvResultCount.text = "共 ${getFilteredResults().size} 个资源"
    }

    private fun isDownloadUrl(url: String): Boolean {
        val lower = url.lowercase()
        val exts = listOf(".apk",".zip",".rar",".7z",".tar",".gz",".dmg",".exe",".msi",".deb",".rpm",
            ".mp4",".avi",".mkv",".mov",".flv",".wmv",".webm",".m4v",".m3u8",
            ".mp3",".wav",".ogg",".flac",".aac",".m4a",".pdf",".doc",".docx",".xls",".xlsx")
        return exts.any { ext ->
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
            .setPositiveButton("下载") { _, _ -> startDownload(url, mimeType) }
            .setNeutralButton("复制链接") { _, _ -> copyToClipboard(url) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startDownload(url: String, mimeType: String?) {
        try {
            val fileName = Uri.parse(url).lastPathSegment ?: "download_${System.currentTimeMillis()}"
            val cleanName = fileName.split("?")[0].split("#")[0]
            val downloadDir = DownloadHelper.getDownloadDir(this)
            val destFile = java.io.File(downloadDir, cleanName)
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(cleanName)
                .setDescription("正在下载...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(destFile))
                .setMimeType(mimeType ?: "application/octet-stream")
            dm.enqueue(request)
            DownloadRecordStore.add(this, DownloadRecord(
                name = cleanName,
                url = url,
                path = destFile.absolutePath,
                size = 0,
                date = System.currentTimeMillis()
            ))
            Toast.makeText(this, "开始下载: $cleanName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showVideoDownloadDialog(result: SnifferResult) {
        val fileName = Uri.parse(result.url).lastPathSegment ?: "video"
        val cleanName = fileName.split("?")[0].split("#")[0]

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_video_download, null)
        val ivThumbnail = dialogView.findViewById<android.widget.ImageView>(R.id.ivThumbnail)
        val tvFileName = dialogView.findViewById<TextView>(R.id.tvFileName)
        val tvDuration = dialogView.findViewById<TextView>(R.id.tvDuration)
        val tvSize = dialogView.findViewById<TextView>(R.id.tvSize)
        val tvResolution = dialogView.findViewById<TextView>(R.id.tvResolution)
        val rgFormat = dialogView.findViewById<RadioGroup>(R.id.rgFormat)

        tvFileName.text = cleanName
        if (result.duration > 0) tvDuration.text = "时长: ${VideoInfoFetcher.formatDuration(result.duration)}"
        else tvDuration.text = "时长: 获取中..."
        if (result.estimatedSize > 0) tvSize.text = "大小: ${DownloadHelper.formatFileSize(result.estimatedSize)}"
        else tvSize.text = "大小: 获取中..."
        tvResolution.text = if (result.isM3u8) "格式: HLS (M3U8)" else "格式: 直链视频"
        if (result.thumbnail != null) ivThumbnail.setImageBitmap(result.thumbnail)

        // Set default format from settings
        val savedFormat = DownloadHelper.getTsFormat(this)
        rgFormat.check(when (savedFormat) {
            TsOutputFormat.ORIGINAL -> R.id.rbOriginal
            TsOutputFormat.MP4 -> R.id.rbMp4
            TsOutputFormat.H264 -> R.id.rbH264
            TsOutputFormat.HEVC -> R.id.rbHevc
            TsOutputFormat.AV1 -> R.id.rbAv1
        })

        AlertDialog.Builder(this)
            .setTitle("下载视频")
            .setView(dialogView)
            .setPositiveButton("下载") { _, _ ->
                val format = when (rgFormat.checkedRadioButtonId) {
                    R.id.rbMp4 -> TsOutputFormat.MP4
                    R.id.rbH264 -> TsOutputFormat.H264
                    R.id.rbHevc -> TsOutputFormat.HEVC
                    R.id.rbAv1 -> TsOutputFormat.AV1
                    else -> TsOutputFormat.ORIGINAL
                }
                if (result.isM3u8) {
                    startM3u8Download(result.url, cleanName, format)
                } else {
                    startDownload(result.url, guessMimeType(result.url))
                }
            }
            .setNeutralButton("复制链接") { _, _ -> copyToClipboard(result.url) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startM3u8Download(url: String, fileName: String, format: TsOutputFormat) {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("下载 M3U8 视频")
            setMessage("解析播放列表...")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            setCancelable(false)
            show()
        }

        scope.launch(Dispatchers.IO) {
            try {
                val m3u8Info = M3u8Parser.fetchAndParse(url)
                if (m3u8Info == null || m3u8Info.segments.isEmpty()) {
                    launch(Dispatchers.Main) {
                        progressDialog.dismiss()
                        Toast.makeText(this@CdnSnifferActivity, "M3U8 解析失败", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                launch(Dispatchers.Main) {
                    progressDialog.max = m3u8Info.segments.size
                    progressDialog.setMessage("下载 ${m3u8Info.segments.size} 个分片...")
                }

                val results = TsDownloader.download(m3u8Info.segments, m3u8Info.keyInfo) { downloaded, total ->
                    launch(Dispatchers.Main) {
                        progressDialog.progress = downloaded
                        progressDialog.setMessage("下载分片 $downloaded/$total")
                    }
                }

                launch(Dispatchers.Main) {
                    progressDialog.setMessage("合并转码中...")
                    progressDialog.isIndeterminate = true
                }

                val outputFile = TsMerger.merge(this@CdnSnifferActivity, results, fileName, format) { percent ->
                    launch(Dispatchers.Main) {
                        progressDialog.setMessage("合并转码 $percent%")
                    }
                }

                launch(Dispatchers.Main) {
                    progressDialog.dismiss()
                    if (outputFile != null) {
                        DownloadRecordStore.add(this@CdnSnifferActivity, DownloadRecord(
                            name = outputFile.name,
                            url = url,
                            path = outputFile.absolutePath,
                            size = outputFile.length(),
                            date = System.currentTimeMillis()
                        ))
                        Toast.makeText(this@CdnSnifferActivity, "下载完成: ${outputFile.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@CdnSnifferActivity, "合并转码失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@CdnSnifferActivity, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun copyToClipboard(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(etUrl.windowToken, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        scope.cancel()
        AppLogger.flush()
        super.onDestroy()
    }
}
