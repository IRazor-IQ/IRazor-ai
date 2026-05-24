package com.irazor.ai

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREF_FILE              = "irazor_prefs"
        const val PREF_INSTALLED_VERSION = "installed_version"
        const val PREF_LANG              = "app_lang"
        private const val BUNDLE_VERSION = 7                    
        private const val BUNDLE_ASSET_ENC = "bundle.enc"
        private const val BUNDLE_ASSET_DEV = "index.html"
        const val WEB_ROOT_DIR           = "irazor_web"


        private const val AES_PASSWORD = "IRazorSecretKey2025!"
        private const val AES_SALT     = "IRazorSalt1234567890"
        private const val PBKDF2_ITER  = 65536
        private const val KEY_BITS     = 256


        private val XOR_KEY = byteArrayOf(0x4B, 0x72, 0x61, 0x7A, 0x6F, 0x72, 0x21)

        fun encodeApiKey(raw: String): String {
            val bytes = raw.toByteArray(Charsets.UTF_8)
            val xored = ByteArray(bytes.size) { i ->
                (bytes[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
            }
            return Base64.encodeToString(xored, Base64.NO_WRAP)
        }

        fun decodeApiKey(encoded: String): String {
            if (encoded.isBlank()) return ""
            return try {
                val xored = Base64.decode(encoded, Base64.NO_WRAP)
                val bytes = ByteArray(xored.size) { i ->
                    (xored[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
                }
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) { "" }
        }


        init {
            try { System.loadLibrary("irazor_native") }
            catch (e: UnsatisfiedLinkError) {
                android.util.Log.w("IRazor", "Native lib not found: ${e.message}")
            }
        }
    }

    internal external fun nativeInitEngine(): Boolean
    internal external fun nativeProcessBuffer(buffer: ByteArray, extension: String?, flags: Int): String?
    internal external fun nativeRenderFrame()
    internal external fun nativeDispatchCompute(input: ByteArray, operation: Int): ByteArray?
    internal external fun nativeGetGpuInfo(): String?

    private lateinit var logoGlow:     View
    private lateinit var logoView:     View
    private lateinit var titleText:    TextView
    private lateinit var badgeText:    TextView
    private lateinit var subtitleText: TextView
    private lateinit var installBtn:   Button
    private lateinit var progressBar:  ProgressBar
    private lateinit var progressText: TextView
    private lateinit var statusText:   TextView
    private lateinit var poweredText:  TextView

   private lateinit var webView: WebView
    var isArabic = false   

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var webViewReady      = false
    private var nativeEngineReady = false

    private var pendingBundleBytes: ByteArray? = null
    private var pendingBundleExt:   String?    = null

    private var webViewFileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val ext   = resolveFileExtension(uri)
                val bytes = readAllBytes(uri)
                withContext(Dispatchers.Main) {
                    if (webViewReady) pushBundleToWebView(bytes, ext)
                    else {
                        pendingBundleBytes = bytes
                        pendingBundleExt   = ext
                        toast("Bundle loaded — will deliver when WebView is ready.")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toast("Failed to read file: ${e.message}")
                }
            }
        }
    }

    private val webViewFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<Uri>? = if (result.resultCode == RESULT_OK) {
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
        webViewFileChooserCallback?.onReceiveValue(uris)
        webViewFileChooserCallback = null
    }

    private val storagePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast("Storage permission denied")
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    fun triggerFilePicker() { filePickerLauncher.launch("*/*") }
    
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logoGlow     = findViewById(R.id.logo_glow)
        logoView     = findViewById(R.id.logo_view)
        titleText    = findViewById(R.id.title_text)
        badgeText    = findViewById(R.id.badge_text)
        subtitleText = findViewById(R.id.subtitle_text)
        installBtn   = findViewById(R.id.install_btn)
        progressBar  = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.progress_text)
        statusText   = findViewById(R.id.status_text)
        poweredText  = findViewById(R.id.powered_text)

        isArabic = false
        getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(PREF_LANG, "en").apply()

        requestStoragePermissionsIfNeeded()
        playEntranceAnim()

        scope.launch(Dispatchers.IO) {
            nativeEngineReady = try { nativeInitEngine() }
            catch (_: UnsatisfiedLinkError) { false }
            android.util.Log.i("IRazor", "Native engine ready: $nativeEngineReady")
        }

        if (isCurrentVersionInstalled()) showWebView()
        else {
            showInstallScreen()
            installBtn.setOnClickListener { startInstall() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun requestStoragePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            storagePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            try {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                ).apply { data = android.net.Uri.parse("package:$packageName") }
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                android.util.Log.w("IRazor", "Manage storage intent failed: ${e.message}")
            }
        }
    }

    private fun playEntranceAnim() {
        val views = listOf(logoGlow, logoView, titleText, badgeText, subtitleText)
        views.forEach { it.alpha = 0f; it.translationY = 40f }
        views.forEachIndexed { i, v ->
            v.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(80L * i).setDuration(420)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        android.animation.ObjectAnimator.ofFloat(logoGlow, "alpha", 0.25f, 0.55f).apply {
            duration = 2000
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode  = android.animation.ObjectAnimator.REVERSE
            start()
        }
    }

    private fun isCurrentVersionInstalled(): Boolean {
        val prefs = getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        if (prefs.getInt(PREF_INSTALLED_VERSION, -1) != BUNDLE_VERSION) return false
        return File(filesDir, "$WEB_ROOT_DIR/index.html").exists()
    }

    private fun showInstallScreen() {
        runOnUiThread {
            installBtn.visibility   = View.VISIBLE
            progressBar.visibility  = View.GONE
            progressText.visibility = View.GONE
            statusText.visibility   = View.GONE

            installBtn.text    = "Install IRazor AI"
            subtitleText.text  = "First launch — tap to install"
            installBtn.alpha = 0f
            installBtn.animate().alpha(1f).setStartDelay(400).setDuration(300).start()
        }
    }

    private fun showProgress(message: String, progress: Int = -1) {
        runOnUiThread {
            installBtn.visibility   = View.GONE
            progressBar.visibility  = View.VISIBLE
            progressText.visibility = View.VISIBLE
            statusText.visibility   = View.VISIBLE
            statusText.text = message
            if (progress >= 0) {
                progressBar.isIndeterminate = false
                android.animation.ObjectAnimator
                    .ofInt(progressBar, "progress", progressBar.progress, progress)
                    .setDuration(200).start()
            } else {
                progressBar.isIndeterminate = true
            }
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            progressBar.visibility  = View.GONE
            progressText.visibility = View.GONE
            statusText.visibility   = View.VISIBLE
            statusText.text         = "Error: $message"
            installBtn.visibility   = View.VISIBLE
            installBtn.text         = "Retry"
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()



    private fun decryptBundleToBytes(encryptedData: ByteArray): ByteArray {
        val iv      = encryptedData.copyOfRange(0, 16)
        val cipher  = encryptedData.copyOfRange(16, encryptedData.size)
        val spec    = PBEKeySpec(
            AES_PASSWORD.toCharArray(),
            AES_SALT.toByteArray(Charsets.UTF_8),
            PBKDF2_ITER, KEY_BITS
        )
        val keyBytes  = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val aes       = Cipher.getInstance("AES/CBC/PKCS5Padding")
        aes.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        return aes.doFinal(cipher)
    }

   private fun startInstall() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    showProgress("Preparing...", 0)

                    val webRoot = File(filesDir, WEB_ROOT_DIR)
                    if (webRoot.exists()) webRoot.deleteRecursively()
                    webRoot.mkdirs()


                    val hasBundleEnc = try {
                        assets.open(BUNDLE_ASSET_ENC).close(); true
                    } catch (_: Exception) { false }

                    if (hasBundleEnc) {

                        showProgress("Loading bundle...", 5)
                        val encBytes = assets.open(BUNDLE_ASSET_ENC).use { it.readBytes() }
                        android.util.Log.i("IRazor", "bundle.enc loaded: ${encBytes.size} bytes")

                        showProgress("Decrypting...", 15)
                        val zipBytes = try {
                            decryptBundleToBytes(encBytes)
                        } catch (e: Exception) {
                            throw Exception("Decryption failed: ${e.message}")
                        }
                        android.util.Log.i("IRazor", "Decrypted ZIP: ${zipBytes.size} bytes")

                        showProgress("Extracting files...", 35)
                        var count = 0
                        zipBytes.inputStream().use { bis ->
                            ZipInputStream(bis).use { zis ->
                                var entry = zis.nextEntry
                                while (entry != null) {
                                    val outFile = File(webRoot, entry.name)
                                    if (entry.isDirectory) {
                                        outFile.mkdirs()
                                    } else {
                                        outFile.parentFile?.mkdirs()
                                        FileOutputStream(outFile).use { out ->
                                            zis.copyTo(out, bufferSize = 65536)
                                        }
                                    }
                                    count++
                                    val pct = 35 + (count.toFloat() / 300 * 55).toInt().coerceIn(0, 55)
                                    showProgress("Extracting... ($count files)", pct)
                                    zis.closeEntry()
                                    entry = zis.nextEntry
                                }
                            }
                        }
                        showProgress("Extracted $count files.", 92)

                    } else {

                        android.util.Log.i("IRazor", "bundle.enc not found — copying raw assets")
                        showProgress("Copying assets...", 10)
                        val assetList = assets.list("") ?: emptyArray()
                        if (assetList.isEmpty()) {
                            throw Exception("No assets found (bundle.enc and index.html both missing)")
                        }
                        var copied = 0
                        assetList.forEachIndexed { i, name ->
                            if (name.isNotBlank()) {
                                try {
                                    assets.open(name).use { src ->
                                        File(webRoot, name).outputStream().use { src.copyTo(it) }
                                    }
                                    copied++
                                } catch (_: Exception) {  }
                            }
                            val pct = 10 + (i.toFloat() / assetList.size.coerceAtLeast(1) * 82).toInt()
                            showProgress("Copying: $name", pct)
                        }
                        if (copied == 0) {
                            throw Exception("No files found in assets")
                        }
                        android.util.Log.i("IRazor", "Copied $copied asset files")
                        showProgress("Copied $copied files.", 92)
                    }


                    showProgress("Finalizing...", 96)
                    getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                        .edit().putInt(PREF_INSTALLED_VERSION, BUNDLE_VERSION).apply()

                    showProgress("Ready!", 100)
                    delay(600)
                    withContext(Dispatchers.Main) { showWebView() }

                } catch (e: Exception) {
                    android.util.Log.e("IRazor", "Install failed", e)
                    showError(e.message ?: "Unknown error")
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebView() {
        setContentView(R.layout.activity_webview)
        webView = findViewById(R.id.webview)


        isArabic = false

        configureWebView()
        webViewReady = true


        pendingBundleBytes?.let { bytes ->
            pendingBundleExt?.let { ext -> pushBundleToWebView(bytes, ext) }
            pendingBundleBytes = null
            pendingBundleExt   = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        with(webView.settings) {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = true
            allowContentAccess               = true
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = true
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs      = true
            cacheMode                        = WebSettings.LOAD_DEFAULT
            databaseEnabled                  = true
            loadsImagesAutomatically         = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort                  = true
            loadWithOverviewMode             = true
            setSupportZoom(true)
            builtInZoomControls              = true
            displayZoomControls              = false
            userAgentString                  = "IRazorAI/1.0 Android WebView"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                forceDark = WebSettings.FORCE_DARK_OFF
            }
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
                webViewFileChooserCallback?.onReceiveValue(null)
                webViewFileChooserCallback = callback
                val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true)
                    val mimes = params.acceptTypes
                        ?.flatMap { it.split(",") }
                        ?.map { it.trim() }
                        ?.filter { it.contains("/") && it != "*/*" }
                        ?.toTypedArray()
                    if (!mimes.isNullOrEmpty()) putExtra(android.content.Intent.EXTRA_MIME_TYPES, mimes)
                }
                webViewFilePickerLauncher.launch(
                    android.content.Intent.createChooser(intent, "Select file")
                )
                return true
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
                val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    error.description.toString() else "Load error"
                android.util.Log.e("IRazor", "WebView error [${request.url}]: $desc")
                if (request.isForMainFrame) {

                    view.loadDataWithBaseURL(null, """
                        <!DOCTYPE html><html><body style="background:#0a0a0f;color:#f87171;
                        font-family:monospace;padding:24px;margin:0">
                        <h3 style="color:#60a5fa">⚡ IRazor — Load Error</h3>
                        <p><b>URL:</b> ${request.url}</p>
                        <p><b>Error:</b> $desc</p>
                        <p style="color:#94a3b8;font-size:12px">Check logcat tag: IRazor</p>
                        </body></html>
                    """.trimIndent(), "text/html", "UTF-8", null)
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError
            ) {

                handler.proceed()
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectBridgeConfig()
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }

        loadApp()
    }

    private fun loadApp() {
        val indexFile = File(filesDir, "$WEB_ROOT_DIR/index.html")
        if (!indexFile.exists()) {
            android.util.Log.e("IRazor", "index.html not found at: ${indexFile.absolutePath}")
            webView.loadDataWithBaseURL(
                null,
                buildFallbackHtml(),
                "text/html",
                "UTF-8",
                null
            )
            return
        }

        val fileUrl = android.net.Uri.fromFile(indexFile).toString()
        android.util.Log.i("IRazor", "Loading: $fileUrl")
        webView.loadUrl(fileUrl)
    }

    private fun injectBridgeConfig() {
        val rawKey   = decodeApiKey(
            getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getString("api_key_enc", "") ?: ""
        )

        fun jsStr(s: String) = s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")

        val gpuInfoRaw = try { nativeGetGpuInfo() ?: "null" }
                         catch (_: Throwable) { "null" }

        val gpuInfo = try {
            org.json.JSONObject(gpuInfoRaw); gpuInfoRaw   
        } catch (_: Throwable) {
            try { gpuInfoRaw.toDouble(); gpuInfoRaw }     
            catch (_: Throwable) { "null" }              
        }

        val webRoot     = File(filesDir, WEB_ROOT_DIR)
        val hdrFiles    = webRoot.listFiles { f -> f.name.endsWith(".hdr") } ?: emptyArray()
        val hdrMapJson  = hdrFiles.joinToString(",", "{", "}") { f ->
            "\"${jsStr(f.name)}\": \"file://${jsStr(f.absolutePath)}\""
        }
        val safeKey     = jsStr(rawKey)
        val safeWebRoot = jsStr(webRoot.absolutePath)

        val js = """
            (function(){
              try {
                window.__IRAZOR_CONFIG__ = {
                  apiUrl:       'https://opencode.ai/zen/v1/chat/completions',
                  apiKey:       '$safeKey',
                  model:        'big-pickle',
                  version:      '1.0',
                  platform:     'android',
                  lang:         'en',
                  nativeEngine: $nativeEngineReady,
                  gpuInfo:      $gpuInfo,
                  ndkVersion:   'r27',
                  hdrAssets:    $hdrMapJson,
                  webRoot:      'file://$safeWebRoot/'
                };
                const _orig = window.fetch;
                window.fetch = function(url, opts) {
                  opts = opts || {};
                  if (typeof url === 'string' && url.indexOf('opencode.ai') !== -1) {
                    opts.headers = opts.headers || {};
                    if (!opts.headers['authorization'] && !opts.headers['Authorization']) {
                      var k = (window.AndroidCompilerBridge && window.AndroidCompilerBridge.getApiKey)
                            ? window.AndroidCompilerBridge.getApiKey()
                            : window.__IRAZOR_CONFIG__.apiKey;
                      if (k) opts.headers['Authorization'] = 'Bearer ' + k;
                    }
                  }
                  return _orig.call(this, url, opts);
                };
                console.log('[IRazor] Bridge v1.0 OK. native=$nativeEngineReady');
              } catch(e) {
                console.error('[IRazor] Bridge inject error: ' + e.message);
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun handleDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        try {
            when {
                url.startsWith("blob:") -> {
                    val safeDisp = contentDisposition.replace("'", "\\'")
                    val safeMime = mimetype.replace("'", "\\'")
                    val js = """
                        (function(){
                            fetch('$url')
                                .then(r => r.arrayBuffer())
                                .then(buf => {
                                    AndroidCompilerBridge.saveBlobDownload(
                                        JSON.stringify(Array.from(new Uint8Array(buf))),
                                        '$safeDisp',
                                        '$safeMime'
                                    );
                                });
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js, null)
                }
                url.startsWith("data:") ->
                    AndroidCompilerBridge(this).saveDataUrlDownload(url, contentDisposition)
                else -> {
                    val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                    val req = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimetype)
                        addRequestHeader("User-Agent", userAgent)
                        setDescription("IRazor AI")
                        setTitle(filename)
                        setNotificationVisibility(
                            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                    }
                    (getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager).enqueue(req)
                    toast("Downloading: $filename")
                }
            }
        } catch (e: Exception) {
            toast("Download failed: ${e.message}")
        }
    }

   private fun pushBundleToWebView(bytes: ByteArray, ext: String) {
        if (!webViewReady || !::webView.isInitialized) return
        val b64  = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val size = bytes.size
        val js = """
            (function(){
                window.dispatchEvent(new CustomEvent('irazor-bundle-loaded', {
                    detail: {
                        data: '$b64',
                        ext:  '$ext',
                        size: $size,
                        mime: 'application/octet-stream'
                    }
                }));
                console.log('[IRazor] Bundle delivered: $ext ($size bytes)');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        toast("Loaded: $ext (${fmtSize(size)})")
    }

    fun getCurrentLang(): String = "en"

    fun setLanguage(arabic: Boolean) {

        isArabic = false
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "if(window.onNativeLangChange) window.onNativeLangChange('en');", null
            )
        }
    }

    private fun resolveFileExtension(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = it.getString(idx)
                    val dot  = name.lastIndexOf('.')
                    if (dot >= 0) return name.substring(dot).lowercase()
                }
            }
        }
        val path = uri.path ?: return ".bin"
        val dot  = path.lastIndexOf('.')
        return if (dot >= 0) path.substring(dot).lowercase() else ".bin"
    }

    private fun readAllBytes(uri: Uri): ByteArray =
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("Cannot open file")

    private fun fmtSize(b: Int): String = when {
        b < 1024    -> "${b}B"
        b < 1048576 -> "${b / 1024}KB"
        else        -> "${"%.1f".format(b / 1048576.0)}MB"
    }

    @Deprecated("Required for pre-API33")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    private fun buildFallbackHtml() = """
        <!DOCTYPE html><html lang="en">
        <head><meta charset="UTF-8">
        <meta name="viewport" content="width=device-width,initial-scale=1.0">
        <title>IRazor AI</title>
        <style>
          body{background:#0a0a0f;color:#e2e8f0;font-family:sans-serif;
               display:flex;align-items:center;justify-content:center;
               height:100vh;margin:0;text-align:center;padding:20px;}
          .card{background:#1e1e2e;padding:32px;border-radius:16px;
                border:1px solid #2d2d44;max-width:360px;}
          h2{color:#60a5fa;margin:0 0 12px;}
          p{color:#94a3b8;margin:0;line-height:1.6;}
        </style></head>
        <body><div class="card">
          <h2>IRazor AI</h2>
          <p>App files not found. Please restart the app.</p>
        </div></body></html>
    """.trimIndent()
}
