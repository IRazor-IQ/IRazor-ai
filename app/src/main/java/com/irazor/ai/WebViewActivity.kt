package com.irazor.ai

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.*
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream

class WebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var isArabic = true

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val OPENCODE_API  = "https://opencode.ai/zen/v1/chat/completions"
        private const val API_KEY_PREF  = "api_key_enc"   
        private const val PREF_FILE     = "irazor_prefs"
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                when {
                    data.clipData != null -> {
                        val clip = data.clipData!!
                        Array(clip.itemCount) { clip.getItemAt(it).uri }
                    }
                    data.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            }
        } else null
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
    }

    private val storagePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this,
            if (isArabic) "صلاحية التخزين مرفوضة" else "Storage permission denied",
            Toast.LENGTH_SHORT).show()
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview)

        webView = findViewById(R.id.webview)

        isArabic = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getString(MainActivity.PREF_LANG, "ar") == "ar"

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            try {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                ).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                android.util.Log.w("IRazor", "Manage storage intent failed: ${e.message}")
            }
        }

        setupWebView()
        loadApp()
    }

    fun getCurrentLang(): String = if (isArabic) "ar" else "en"

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = true
            allowContentAccess               = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs      = true
            cacheMode                        = WebSettings.LOAD_DEFAULT
            databaseEnabled                  = true
            loadsImagesAutomatically         = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort                  = true
            loadWithOverviewMode             = true
            setSupportZoom(false)
            userAgentString                  = "IRazorAI/4.0 Android WebView"
        }

        webView.addJavascriptInterface(AndroidCompilerBridge(this), "AndroidCompilerBridge")


        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                msg?.let {
                    android.util.Log.d("IRazor_JS",
                        "[${it.messageLevel()}] ${it.message()} @ ${it.sourceId()}:${it.lineNumber()}")
                }
                return true
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                return try {

                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

                        val mimes = params.acceptTypes
                            ?.flatMap { it.split(",") }
                            ?.map { it.trim() }
                            ?.filter { it.contains("/") && it != "*/*" }
                            ?.toTypedArray()
                        if (!mimes.isNullOrEmpty()) {
                            putExtra(Intent.EXTRA_MIME_TYPES, mimes)
                        }
                    }
                    val chooser = Intent.createChooser(intent, "اختر ملف")
                    filePickerLauncher.launch(chooser)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileChooserCallback = null
                    Toast.makeText(this@WebViewActivity,
                        if (isArabic) "لم يتم العثور على تطبيق لاختيار الملفات"
                        else "No file picker app found",
                        Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return when {
                    url.startsWith("file://")              -> false
                    url.startsWith("https://opencode.ai") -> false
                    url.startsWith("blob:")               -> false
                    else                                  -> true
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame)
                    android.util.Log.e("IRazor", "WebView error: ${error.description}")
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectBridgeConfig()
            }
        }


        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }
    }

    private fun handleDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        try {
            when {
                url.startsWith("blob:") -> {
                    val js = """
                        (function(){
                            fetch('$url')
                                .then(r=>r.arrayBuffer())
                                .then(buf=>{
                                    AndroidCompilerBridge.saveBlobDownload(
                                        JSON.stringify(Array.from(new Uint8Array(buf))),
                                        '${contentDisposition.replace("'","\\'")}',
                                        '$mimetype'
                                    );
                                });
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js, null)
                }
                url.startsWith("data:") -> AndroidCompilerBridge(this).saveDataUrlDownload(url, contentDisposition)
                else -> {
                    val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    val req = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimetype)
                        addRequestHeader("User-Agent", userAgent)
                        setDescription("IRazor AI")
                        setTitle(filename)
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                    }
                    (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    Toast.makeText(this,
                        if (isArabic) "⬇️ جاري التحميل: $filename" else "⬇️ Downloading: $filename",
                        Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this,
                if (isArabic) "فشل التحميل: ${e.message}" else "Download failed: ${e.message}",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadApp() {
        val indexFile = File(filesDir, "${MainActivity.WEB_ROOT_DIR}/index.html")
        if (!indexFile.exists()) {
            webView.loadData(buildFallbackHtml(), "text/html", "UTF-8"); return
        }
        webView.loadUrl("file://${indexFile.absolutePath}")
    }

    private fun injectBridgeConfig() {
        val rawKey = MainActivity.decodeApiKey(
            getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getString(API_KEY_PREF, "") ?: ""
        )
        val lang = if (isArabic) "ar" else "en"
        val js = """
            (function(){
                window.__IRAZOR_CONFIG__ = {
                    apiUrl: '${OPENCODE_API}',
                    apiKey: '${rawKey.replace("'","\\'")}',
                    model: 'big-pickle',
                    version: '4.0',
                    platform: 'android',
                    offline: true,
                    lang: '$lang'
                };
                const _origFetch = window.fetch;
                window.fetch = function(url, opts={}) {
                    if(typeof url==='string' && url.includes('opencode.ai')){
                        opts.headers = opts.headers||{};
                        if(!opts.headers['authorization']&&!opts.headers['Authorization']){
                            const k = window.AndroidCompilerBridge
                                ? window.AndroidCompilerBridge.getApiKey()
                                : window.__IRAZOR_CONFIG__.apiKey;
                            if(k) opts.headers['Authorization']='Bearer '+k;
                        }
                    }
                    return _origFetch.call(this,url,opts);
                };
                console.log('[IRazor] Bridge v4.0 injected. lang=$lang');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    fun triggerFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        filePickerLauncher.launch(Intent.createChooser(intent, "Select file"))
    }

    internal fun nativeProcessBuffer(buffer: ByteArray, extension: String?, flags: Int): String? = null
    internal fun nativeGetGpuInfo(): String? = null
    internal fun nativeDispatchCompute(input: ByteArray, operation: Int): ByteArray? = null
    internal fun nativeRenderFrame() {}

    @Deprecated("Needed for pre-API33")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun buildFallbackHtml() = """
        <!DOCTYPE html><html dir="${if (isArabic) "rtl" else "ltr"}" lang="${if (isArabic) "ar" else "en"}">
        <head><meta charset="UTF-8">
        <meta name="viewport" content="width=device-width,initial-scale=1.0">
        <title>IRazor AI</title>
        <style>body{background:#0a0a0f;color:#e2e8f0;font-family:sans-serif;
        display:flex;align-items:center;justify-content:center;height:100vh;margin:0;
        text-align:center;padding:20px;}
        .card{background:#1e1e2e;padding:32px;border-radius:16px;border:1px solid #2d2d44;max-width:360px;}
        h2{color:#60a5fa;margin:0 0 12px;}p{color:#94a3b8;margin:0;line-height:1.6;}</style></head>
        <body><div class="card"><h2> IRazor AI</h2>
        <p>${if (isArabic) "ملفات التطبيق غير موجودة. يرجى إعادة تشغيل التطبيق."
             else "App files not found. Please restart the app."}</p>
        </div></body></html>
    """.trimIndent()
}
