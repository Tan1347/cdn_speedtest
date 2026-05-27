package org.tan.cdntest

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCert: ImageButton
    private lateinit var etUrl: EditText
    private var hasSslError = false

    // 嗅探相关
    private lateinit var layoutSnifferPanel: View
    private lateinit var chipGroup: ChipGroup
    private lateinit var tvResultCount: TextView
    private lateinit var btnClear: TextView
    private lateinit var rvResults: RecyclerView
    private lateinit var adapter: CdnSnifferAdapter
    private var allResults = mutableListOf<SnifferResult>()
    private var currentFilter = "all"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val networkCapturedUrls = ConcurrentHashMap.newKeySet<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(this)
        AppLogger.i(this, "MainActivity", "应用启动")
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        btnCert = findViewById(R.id.btnCert)
        etUrl = findViewById(R.id.etUrl)

        setupWebView()
        setupUrlInput()
        setupBottomNav()
        setupBackNavigation()
        setupSnifferPanel()
        requestNotificationPermission()

        val updater = UpdateChecker(this)
        updater.checkForUpdate { release ->
            if (release != null) updater.showUpdateDialog(release)
        }

        if (savedInstanceState == null) {
            webView.loadUrl("https://speedtest.2026524.xyz/")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun setupUrlInput() {
        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
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
                true
            } else false
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
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            @Suppress("DEPRECATION")
            databaseEnabled = true
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme
                // 拦截文件下载链接
                if ((scheme == "http" || scheme == "https") && isDownloadUrl(url)) {
                    if (url.lowercase().contains(".m3u8")) {
                        showSnifferPanel()
                    } else {
                        showDownloadConfirm(url, guessDownloadMimeType(url))
                    }
                    return true
                }
                if (scheme == "http" || scheme == "https") {
                    return false
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {}
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                val url = request.url.toString()
                val scheme = request.url.scheme
                if (scheme == "http" || scheme == "https") {
                    networkCapturedUrls.add(url)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                hasSslError = false
                networkCapturedUrls.clear()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                etUrl.setText(url)
                updateCertIcon(url)
                AppLogger.i(this@MainActivity, "MainActivity", "页面加载完成: $url")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                hasSslError = true
                AppLogger.w(this@MainActivity, "MainActivity", "SSL证书错误: ${error.url}")
                handler.proceed()
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
                showSnifferPanel()
            } else {
                showDownloadConfirm(url, mimeType)
            }
        }
    }

    private fun showDownloadConfirm(url: String, mimeType: String?) {
        val fileName = Uri.parse(url).lastPathSegment ?: "download"
        val cleanName = fileName.split("?")[0].split("#")[0]

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_download_confirm, null)
        val etFileName = dialogView.findViewById<EditText>(R.id.etFileName)
        etFileName.setText(cleanName)

        AlertDialog.Builder(this)
            .setTitle("下载确认")
            .setView(dialogView)
            .setPositiveButton("下载") { _, _ ->
                val name = etFileName.text.toString().trim().ifEmpty { cleanName }
                startDownload(url, mimeType, name)
            }
            .setNeutralButton("复制链接") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
                Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startDownload(url: String, mimeType: String?, customName: String? = null) {
        try {
            val fileName = Uri.parse(url).lastPathSegment ?: "download_${System.currentTimeMillis()}"
            val cleanName = customName ?: fileName.split("?")[0].split("#")[0]
            val downloadDir = DownloadHelper.getDownloadDir(this)
            val destFile = java.io.File(downloadDir, cleanName)
            DownloadEngine.enqueue(this, url, cleanName, destFile.absolutePath, mimeType)
            Toast.makeText(this, "开始下载: $cleanName", Toast.LENGTH_SHORT).show()
            AppLogger.i(this, "MainActivity", "触发下载: $cleanName, URL: $url")
        } catch (e: Exception) {
            Toast.makeText(this, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            AppLogger.e(this, "MainActivity", "下载失败: $url", e)
        }
    }

    private fun isDownloadUrl(url: String): Boolean {
        val lower = url.lowercase()
        val exts = listOf(".apk", ".zip", ".rar", ".7z", ".tar", ".gz", ".dmg", ".exe", ".msi",
            ".mp4", ".avi", ".mkv", ".mov", ".flv", ".wmv", ".webm", ".m3u8",
            ".mp3", ".wav", ".ogg", ".flac", ".aac", ".pdf")
        return exts.any { ext ->
            val idx = lower.indexOf(ext)
            idx > 0 && (idx + ext.length >= lower.length || lower[idx + ext.length] == '?' || lower[idx + ext.length] == '#')
        }
    }

    private fun guessDownloadMimeType(url: String): String {
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

    private fun classifyUrl(url: String): String? {
        val lower = url.lowercase()
        val ext = Regex("\\.(jpg|jpeg|png|gif|webp|svg|bmp|ico)(\\?|#|$)", RegexOption.IGNORE_CASE)
        val vidExt = Regex("\\.(mp4|avi|mkv|mov|flv|wmv|webm|m4v|m3u8|ts)(\\?|#|$)", RegexOption.IGNORE_CASE)
        val audExt = Regex("\\.(mp3|wav|ogg|flac|aac|m4a)(\\?|#|$)", RegexOption.IGNORE_CASE)
        val dlExt = Regex("\\.(apk|zip|rar|7z|tar|gz|dmg|exe|msi|deb|rpm|pdf)(\\?|#|$)", RegexOption.IGNORE_CASE)
        return when {
            vidExt.containsMatchIn(lower) -> "video"
            audExt.containsMatchIn(lower) -> "audio"
            ext.containsMatchIn(lower) -> "image"
            dlExt.containsMatchIn(lower) -> "download"
            else -> null
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
            toggleSnifferPanel()
        }
        findViewById<ImageButton>(R.id.btnMore).setOnClickListener {
            startActivity(Intent(this, MoreActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            webView.reload()
        }
        btnCert.setOnClickListener {
            showCertDialog()
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
        val url = webView.url ?: ""
        val message = if (url.startsWith("https://")) {
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

        val cookies = CookieManager.getInstance().getCookie(url) ?: "无"
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

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (layoutSnifferPanel.visibility == View.VISIBLE) {
                    layoutSnifferPanel.visibility = View.GONE
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etUrl.windowToken, 0)
    }

    // ========== 嗅探功能 ==========

    private fun setupSnifferPanel() {
        layoutSnifferPanel = findViewById(R.id.layoutSnifferPanel)
        chipGroup = findViewById(R.id.chipGroup)
        tvResultCount = findViewById(R.id.tvResultCount)
        btnClear = findViewById(R.id.btnClear)
        rvResults = findViewById(R.id.rvResults)

        setupChipFilter()
        setupResultList()
        setupClearButton()
        setupSizeFilter()
    }

    private var minSizeBytes = 0L

    private fun setupSizeFilter() {
        val seekBar = findViewById<SeekBar>(R.id.seekBarSizeFilter)
        val label = findViewById<TextView>(R.id.tvSizeFilterLabel)

        // Size thresholds: 0, 100KB, 500KB, 1MB, 5MB, 10MB, 50MB, 100MB
        val thresholds = longArrayOf(0, 100 * 1024, 500 * 1024, 1024 * 1024,
            5 * 1024 * 1024, 10 * 1024 * 1024, 50 * 1024 * 1024, 100 * 1024 * 1024)

        val prefs = getSharedPreferences("sniffer_prefs", Context.MODE_PRIVATE)
        val savedProgress = prefs.getInt("size_filter", 0)
        seekBar.progress = savedProgress
        minSizeBytes = thresholds.getOrElse(savedProgress) { 0 }
        label.text = if (minSizeBytes > 0) DownloadHelper.formatFileSize(minSizeBytes) else "0B"

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                minSizeBytes = thresholds.getOrElse(progress) { 0 }
                label.text = if (minSizeBytes > 0) DownloadHelper.formatFileSize(minSizeBytes) else "0B"
                applyFilter()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putInt("size_filter", sb?.progress ?: 0).apply()
            }
        })
    }

    private fun toggleSnifferPanel() {
        if (layoutSnifferPanel.visibility == View.VISIBLE) {
            layoutSnifferPanel.visibility = View.GONE
        } else {
            showSnifferPanel()
        }
    }

    private fun showSnifferPanel() {
        layoutSnifferPanel.visibility = View.VISIBLE
        sniffResources()
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
                android.util.Log.i("CDNTest", "[下载按钮] type=${result.type}, isM3u8=${result.isM3u8}, url=${result.url}")
                if (result.type == "video" || result.isM3u8) {
                    showVideoDownloadDialog(result)
                } else {
                    showDownloadConfirm(result.url, guessDownloadMimeType(result.url))
                }
            },
            onPreview = { result -> showPreviewDialog(result) },
            onSelectionChanged = { count -> updateBatchBar(count) }
        )
        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter

        setupBatchBar()
    }

    private lateinit var layoutBatchBar: View
    private lateinit var tvSelectedCount: TextView

    private fun setupBatchBar() {
        layoutBatchBar = findViewById(R.id.layoutBatchBar)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)

        findViewById<View>(R.id.btnSelectAll).setOnClickListener {
            adapter.selectAll()
        }
        findViewById<View>(R.id.btnBatchDownload).setOnClickListener {
            batchDownload()
        }
        findViewById<View>(R.id.btnCancelSelect).setOnClickListener {
            adapter.exitMultiSelect()
            layoutBatchBar.visibility = View.GONE
        }
    }

    private fun updateBatchBar(count: Int) {
        if (count > 0) {
            layoutBatchBar.visibility = View.VISIBLE
            tvSelectedCount.text = "已选 $count 项"
        } else {
            layoutBatchBar.visibility = View.GONE
        }
    }

    private fun batchDownload() {
        val selected = adapter.getSelectedResults()
        if (selected.isEmpty()) return

        val downloadDir = DownloadHelper.getDownloadDir(this)
        var count = 0
        for (result in selected) {
            val fileName = Uri.parse(result.url).lastPathSegment?.split("?")?.firstOrNull()
                ?: "download_${System.currentTimeMillis()}"
            val cleanName = fileName.split("#")[0]
            val destFile = java.io.File(downloadDir, cleanName)
            if (!DownloadEngine.isDownloading(result.url)) {
                DownloadEngine.enqueue(this, result.url, cleanName, destFile.absolutePath)
                count++
            }
        }
        Toast.makeText(this, "已加入 $count 个下载任务", Toast.LENGTH_SHORT).show()
        adapter.exitMultiSelect()
        layoutBatchBar.visibility = View.GONE
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

                // Merge network captured URLs (XHR/fetch/dynamic resources)
                val domUrls = allResults.map { it.url }.toSet()
                for (capturedUrl in networkCapturedUrls) {
                    if (capturedUrl !in domUrls && capturedUrl.startsWith("http")) {
                        val type = classifyUrl(capturedUrl)
                        if (type != null) {
                            val isM3u8 = capturedUrl.lowercase().contains(".m3u8")
                            allResults.add(SnifferResult(url = capturedUrl, type = type, isM3u8 = isM3u8))
                        }
                    }
                }

                layoutSnifferPanel.visibility = View.VISIBLE
                applyFilter()
                AppLogger.i(this, "CdnSniffer", "嗅探完成，发现 ${allResults.size} 个资源 (DOM: ${jsonArray.length()}, 网络: ${networkCapturedUrls.size})")
                fetchVideoInfoForResults()
            } catch (e: Exception) {
                Toast.makeText(this, "嗅探失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchVideoInfoForResults() {
        scope.launch(Dispatchers.IO) {
            // 1. Fetch full video info (duration, resolution, size, thumbnail) for video items and M3U8
            val videoResults = allResults.filter { (it.type == "video" || it.isM3u8) && (it.duration == 0L || it.isM3u8) }
            for (result in videoResults) {
                try {
                    val info = VideoInfoFetcher.fetch(result.url, result.isM3u8)
                    if (info != null) {
                        val idx = allResults.indexOfFirst { it.url == result.url }
                        if (idx >= 0) {
                            allResults[idx] = result.copy(
                                duration = info.duration,
                                estimatedSize = info.estimatedSize,
                                thumbnail = info.thumbnail,
                                segmentCount = info.segmentCount,
                                width = info.width,
                                height = info.height
                            )
                            launch(Dispatchers.Main) { adapter.updateData(getFilteredResults()) }
                        }
                    }
                } catch (_: Exception) {}
            }

            // 2. Fetch file size for non-video items (image, audio, download, other), skip M3U8
            val otherResults = allResults.filter { it.type != "video" && !it.isM3u8 && it.estimatedSize == 0L }
            for (result in otherResults) {
                try {
                    val size = VideoInfoFetcher.fetchFileSize(result.url)
                    if (size > 0) {
                        val idx = allResults.indexOfFirst { it.url == result.url }
                        if (idx >= 0) {
                            allResults[idx] = result.copy(estimatedSize = size)
                            launch(Dispatchers.Main) { adapter.updateData(getFilteredResults()) }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun getFilteredResults(): List<SnifferResult> {
        val typeFiltered = if (currentFilter == "all") allResults else allResults.filter { it.type == currentFilter }
        return if (minSizeBytes > 0) {
            typeFiltered.filter { it.estimatedSize >= minSizeBytes || it.estimatedSize == 0L }
        } else {
            typeFiltered
        }
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

    private fun showVideoDownloadDialog(result: SnifferResult) {
        android.util.Log.i("CDNTest", "[视频弹窗] url=${result.url}, isM3u8=${result.isM3u8}, duration=${result.duration}, size=${result.estimatedSize}")
        val fileName = Uri.parse(result.url).lastPathSegment ?: "video"
        val cleanName = fileName.split("?")[0].split("#")[0]

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_video_download, null)
        val ivThumbnail = dialogView.findViewById<android.widget.ImageView>(R.id.ivThumbnail)
        val etFileName = dialogView.findViewById<EditText>(R.id.etFileName)
        val tvDuration = dialogView.findViewById<TextView>(R.id.tvDuration)
        val tvSize = dialogView.findViewById<TextView>(R.id.tvSize)
        val tvResolution = dialogView.findViewById<TextView>(R.id.tvResolution)
        val rgFormat = dialogView.findViewById<RadioGroup>(R.id.rgFormat)

        etFileName.setText(cleanName)
        if (result.duration > 0) tvDuration.text = "时长: ${VideoInfoFetcher.formatDuration(result.duration)}"
        else tvDuration.text = "时长: 获取中..."
        if (result.isM3u8 && result.segmentCount > 0) {
            tvSize.text = "分片: ${result.segmentCount} 个"
        } else if (result.estimatedSize > 0) {
            tvSize.text = "大小: ${DownloadHelper.formatFileSize(result.estimatedSize)}"
        } else {
            tvSize.text = "大小: 获取中..."
        }
        tvResolution.text = if (result.isM3u8) "格式: HLS (M3U8)" else "格式: 直链视频"
        if (result.thumbnail != null) ivThumbnail.setImageBitmap(result.thumbnail)

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
                val name = etFileName.text.toString().trim().ifEmpty { cleanName }
                android.util.Log.i("CDNTest", "[确认下载] name=$name, format=${format.label}, isM3u8=${result.isM3u8}, url=${result.url}")
                if (result.isM3u8) {
                    startM3u8Download(result.url, name, format)
                } else {
                    startDownload(result.url, guessDownloadMimeType(result.url), name)
                }
            }
            .setNeutralButton("复制链接") { _, _ -> copyToClipboard(result.url) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startM3u8Download(url: String, fileName: String, format: TsOutputFormat) {
        android.util.Log.i("CDNTest", "[startM3u8Download] url=$url, fileName=$fileName, format=${format.label}")
        DownloadEngine.enqueueM3u8(this, url, fileName, format) { segmentCount ->
            android.util.Log.i("CDNTest", "[M3U8解析完成] $segmentCount 个分片")
            Toast.makeText(this, "M3U8 解析完成，共 $segmentCount 个分片", Toast.LENGTH_SHORT).show()
        }
        Toast.makeText(this, "开始下载: $fileName", Toast.LENGTH_SHORT).show()
    }

    private fun copyToClipboard(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("url", url))
        Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetTextI18n")
    private fun showPreviewDialog(result: SnifferResult) {
        val fileName = Uri.parse(result.url).lastPathSegment?.split("?")?.firstOrNull() ?: "未知"
        PreviewPlayerActivity.start(this, result.url, result.type, fileName, result.isM3u8)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    override fun onDestroy() {
        scope.cancel()
        AppLogger.flush()
        super.onDestroy()
    }
}
