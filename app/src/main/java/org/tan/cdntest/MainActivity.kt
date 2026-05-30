package org.tan.cdntest

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnCert: ImageButton
    private lateinit var etUrl: EditText
    private var hasSslError = false

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
            userAgentString = UserAgentHelper.getCurrentUa(this@MainActivity)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme
                // 拦截文件下载链接
                if ((scheme == "http" || scheme == "https") && isDownloadUrl(url)) {
                    showDownloadConfirm(url, guessDownloadMimeType(url))
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

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                hasSslError = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = android.view.View.GONE
                etUrl.setText(url)
                updateCertIcon(url)
                AppLogger.i(this@MainActivity, "MainActivity", "页面加载完成: $url")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    progressBar.visibility = android.view.View.GONE
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                hasSslError = true
                AppLogger.w(this@MainActivity, "MainActivity", "SSL证书错误: ${error.url}")
                handler.cancel()
                Toast.makeText(this@MainActivity, "SSL证书错误", Toast.LENGTH_SHORT).show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            }
        }
    }

    private fun isDownloadUrl(url: String): Boolean {
        val lower = url.lowercase()
        val downloadExts = listOf(
            ".apk", ".zip", ".rar", ".7z", ".tar", ".gz",
            ".dmg", ".exe", ".msi", ".deb", ".rpm",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx"
        )
        return downloadExts.any { lower.contains(it) && (lower.contains("?") || lower.endsWith(it)) }
    }

    private fun guessDownloadMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".apk") -> "application/vnd.android.package-archive"
            lower.contains(".zip") -> "application/zip"
            lower.contains(".rar") -> "application/x-rar-compressed"
            lower.contains(".7z") -> "application/x-7z-compressed"
            lower.contains(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun showDownloadConfirm(url: String, mimeType: String) {
        val fileName = Uri.parse(url).lastPathSegment ?: "download"
        AlertDialog.Builder(this)
            .setTitle("下载文件")
            .setMessage(fileName)
            .setPositiveButton("下载") { _, _ ->
                SimpleDownloader(this).download(url, fileName)
            }
            .setNegativeButton("取消", null)
            .show()
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
                if (webView.canGoBack()) {
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

    override fun onResume() {
        super.onResume()
        webView.settings.userAgentString = UserAgentHelper.getCurrentUa(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
