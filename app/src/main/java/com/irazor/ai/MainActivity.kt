package com.irazor.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.spec.KeySpec
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts/decrypts small secrets (API keys) using a hardware-backed
 * (or software-backed, if the device lacks StrongBox/TEE) AES-256-GCM
 * key stored in the Android Keystore. The raw key material is generated
 * inside the keystore and is never exported, unlike a hardcoded XOR key
 * that ships inside the APK and can be read by anyone who decompiles it.
 */
private object SecureKeyStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS        = "irazor_api_key_v1"
    private const val TRANSFORMATION   = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS     = 128
    private const val GCM_IV_BYTES     = 12

    private fun getOrCreateSecretKey(): javax.crypto.SecretKey {
        val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }

        val keyGenerator = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv // 12 bytes for GCM
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            android.util.Log.e("IRazor", "SecureKeyStore.encrypt failed: ${e.message}")
            ""
        }
    }

    fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_BYTES) return ""
            val iv = combined.copyOfRange(0, GCM_IV_BYTES)
            val ct = combined.copyOfRange(GCM_IV_BYTES, combined.size)
            val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            "" // wrong format (e.g. legacy XOR) — caller falls back
        }
    }
}

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREF_FILE              = "irazor_prefs"
        const val PREF_LANG              = "app_lang"
        const val WEB_ROOT_DIR           = "irazor_web"

        // ── API key storage — Android Keystore-backed AES-256-GCM ──────────
        // Replaces the old reversible XOR obfuscation. The AES key never
        // leaves the device's secure hardware (or software) keystore, so
        // even a rooted-device / decompiled-APK attacker cannot recover it
        // without also having the key material, which is not stored anywhere
        // in the app. Legacy XOR-encoded values are migrated transparently.
        private val LEGACY_XOR_KEY = byteArrayOf(0x4B, 0x72, 0x61, 0x7A, 0x6F, 0x72, 0x21)

        private fun legacyXorDecode(encoded: String): String? {
            if (encoded.isBlank()) return null
            return try {
                val xored = Base64.decode(encoded, Base64.NO_WRAP)
                val bytes = ByteArray(xored.size) { i ->
                    (xored[i].toInt() xor LEGACY_XOR_KEY[i % LEGACY_XOR_KEY.size].toInt()).toByte()
                }
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) { null }
        }

        fun encodeApiKey(raw: String): String = SecureKeyStore.encrypt(raw)

        /**
         * Decodes a stored key. Handles three cases:
         *  1. New Keystore-encrypted values (normal path).
         *  2. Legacy XOR-encoded values from older installs — decoded once,
         *     then the caller should re-save via [encodeApiKey] to migrate.
         *  3. Empty / invalid input -> "".
         */
        fun decodeApiKey(encoded: String): String {
            if (encoded.isBlank()) return ""
            val viaKeystore = SecureKeyStore.decrypt(encoded)
            if (viaKeystore.isNotEmpty()) return viaKeystore
            // Fall back to legacy format so existing users don't lose their key
            return legacyXorDecode(encoded) ?: ""
        }

        /**
         * Call once after decoding, from any place holding a Context, to
         * silently upgrade a legacy XOR-stored key to Keystore-backed AES.
         * Safe to call repeatedly — it's a no-op once migrated.
         */
        fun migrateApiKeyIfNeeded(context: android.content.Context) {
            try {
                val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                val stored = prefs.getString("api_key_enc", "") ?: return
                if (stored.isBlank()) return
                if (SecureKeyStore.decrypt(stored).isNotEmpty()) return // already new format
                val legacy = legacyXorDecode(stored) ?: return
                if (legacy.isBlank()) return
                prefs.edit().putString("api_key_enc", SecureKeyStore.encrypt(legacy)).apply()
                android.util.Log.i("IRazor", "API key migrated to Keystore-backed encryption")
            } catch (e: Exception) {
                android.util.Log.w("IRazor", "API key migration skipped: ${e.message}")
            }
        }

        // ── Bundle decryption: bundle.enc → extracts index.html to webRoot ──
        // Format: [16 bytes IV][AES-256-CBC / PKCS5Padding encrypted ZIP]
        // Key: PBKDF2-HMAC-SHA256(password, salt, 65536 iter, 32 bytes)
        fun decryptBundle(context: android.content.Context, webRoot: File): Boolean {
            return try {
                val encBytes = context.assets.open("bundle.enc").use { it.readBytes() }
                android.util.Log.i("IRazor", "bundle.enc size: ${encBytes.size} bytes")

                // Derive key — must match encrypt_bundle.py exactly
                val password  = "IRazorSecretKey2025!".toCharArray()
                val salt      = "IRazorSalt1234567890".toByteArray(Charsets.UTF_8)
                val spec: KeySpec = PBEKeySpec(password, salt, 65536, 256)
                val factory   = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                val keyBytes  = factory.generateSecret(spec).encoded
                val secretKey = SecretKeySpec(keyBytes, "AES")

                // Split IV (first 16 bytes) and ciphertext
                val iv         = encBytes.copyOfRange(0, 16)
                val ciphertext = encBytes.copyOfRange(16, encBytes.size)

                // Decrypt
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
                val zipBytes = cipher.doFinal(ciphertext)
                android.util.Log.i("IRazor", "Decrypted ZIP size: ${zipBytes.size} bytes")

                // Extract ZIP → webRoot
                webRoot.mkdirs()
                val webRootCanonical = webRoot.canonicalPath + File.separator
                val zis = ZipInputStream(zipBytes.inputStream())
                var entry = zis.nextEntry
                var extracted = 0
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(webRoot, entry.name)
                        // Zip Slip guard: reject entries that would land outside webRoot
                        val outCanonical = outFile.canonicalPath
                        if (!outCanonical.startsWith(webRootCanonical)) {
                            android.util.Log.e("IRazor", "Blocked unsafe zip entry: ${entry.name}")
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                        android.util.Log.i("IRazor", "Extracted: ${entry.name} (${outFile.length()} bytes)")
                        extracted++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                zis.close()
                android.util.Log.i("IRazor", "Bundle extraction complete: $extracted file(s)")
                extracted > 0
            } catch (e: Exception) {
                android.util.Log.e("IRazor", "decryptBundle failed: ${e.javaClass.simpleName}: ${e.message}")
                false
            }
        }

        // ── NDK / JNI ──────────────────────────────────────────────────────
        init {
            try { System.loadLibrary("irazor_native") }
            catch (e: UnsatisfiedLinkError) {
                android.util.Log.w("IRazor", "Native lib not found: ${e.message}")
            }
        }
    }

    // ── JNI (optional native engine) ──────────────────────────────────────
    // NOTE: Registered via RegisterNatives in native_bridge.cpp JNI_OnLoad.
    external fun nativeInitEngine(): Boolean
    external fun nativeProcessBuffer(buffer: ByteArray, extension: String?, flags: Int): String?
    external fun nativeRenderFrame()
    external fun nativeDispatchCompute(input: ByteArray, operation: Int): ByteArray?
    external fun nativeGetGpuInfo(): String?

    // ── Splash views ─────────────────────────────────────────────────────
    private lateinit var logoGlow:     View
    private lateinit var logoView:     android.widget.ImageView
    private lateinit var titleText:    TextView
    private lateinit var badgeText:    TextView
    private lateinit var subtitleText: TextView
    private lateinit var statusText:   TextView
    private lateinit var splashRoot:   View

    // ── WebView ────────────────────────────────────────────────────────────
    private lateinit var webView: WebView
    var isArabic = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var webViewReady      = false
    private var nativeEngineReady = false

    private var pendingBundleBytes: ByteArray? = null
    private var pendingBundleExt:   String?    = null

    private var webViewFileChooserCallback: ValueCallback<Array<Uri>>? = null

    // ── Google Sign-In ─────────────────────────────────────────────────────
    private val GOOGLE_CLIENT_ID = "236344436425-17pdph0ddc9jcgmtapkvqkn3p9kk34i6.apps.googleusercontent.com"
    private var googleIdToken: String? = null
    private lateinit var googleSignInClient: GoogleSignInClient

    private var googleSignInLaunched = false

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        googleSignInLaunched = false
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        handleGoogleSignInResult(task)
    }

    // ── File pickers ───────────────────────────────────────────────────────
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

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                ).apply {
                    data = Uri.parse("package:$packageName")
                }
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                android.util.Log.w("IRazor", "Manage storage intent failed: ${e.message}")
            }
        }

        // Log signing certificate SHA-1 for Google Sign-In debugging
        logSigningCertSha1()

        // One-time, idempotent upgrade of any legacy XOR-stored API key
        // to Keystore-backed AES-256-GCM.
        migrateApiKeyIfNeeded(this)

        splashRoot = findViewById(R.id.splash_root)

        logoGlow     = findViewById(R.id.logo_glow)
        logoView     = findViewById<android.widget.ImageView>(R.id.logo_view)
        titleText    = findViewById(R.id.title_text)
        badgeText    = findViewById(R.id.badge_text)
        subtitleText = findViewById(R.id.subtitle_text)
        statusText   = findViewById(R.id.status_text)

        isArabic = false
        getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(PREF_LANG, "en").apply()

        subtitleText.text = "Sign in with Google to continue"

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(GOOGLE_CLIENT_ID)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        val googleBtn = findViewById<com.google.android.gms.common.SignInButton>(R.id.google_sign_in_btn)
        googleBtn.setOnClickListener { signInWithGoogle() }

        scope.launch(Dispatchers.IO) {
            nativeEngineReady = try { nativeInitEngine() }
            catch (_: UnsatisfiedLinkError) { false }
            android.util.Log.i("IRazor", "Native engine ready: $nativeEngineReady")
        }

        // Skip splash if already installed, else brief delay for new users
        val alreadyInstalled = File(filesDir, "$WEB_ROOT_DIR/index.html").exists()
        if (alreadyInstalled) {
            showWebView()
            splashRoot.visibility = View.GONE
        } else {
            scope.launch {
                delay(2000)
                showWebView()
                splashRoot.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Google Sign-In ─────────────────────────────────────────────────────

    private fun logSigningCertSha1() {
        try {
            val pm = packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                PackageManager.GET_SIGNING_CERTIFICATES else 0
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                pm.getPackageInfo(packageName, flags)
            else
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                info.signingInfo?.apkContentsSigners else info.signatures
            sigs?.firstOrNull()?.let { sig ->
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(sig.toByteArray()))
                val digest = MessageDigest.getInstance("SHA-1").digest(cert.encoded)
                val sha1 = digest.joinToString(":") { "%02X".format(it) }
                android.util.Log.i("IRazor", "APK SHA-1: $sha1")
                android.util.Log.i("IRazor", "To fix Google Sign-In error 10, register this SHA-1 at https://console.cloud.google.com/apis/credentials")
            }
        } catch (e: Exception) {
            android.util.Log.w("IRazor", "Failed to get SHA-1: ${e.message}")
        }
    }

    fun signInWithGoogle() {
        if (googleSignInLaunched) return
        googleSignInLaunched = true
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    fun switchAccount() {
        googleSignInClient.signOut().addOnCompleteListener {
            googleIdToken = null
            signInWithGoogle()
        }
    }

    private fun handleGoogleSignInResult(task: Task<GoogleSignInAccount>) {
        try {
            val account = task.getResult(Exception::class.java)
            googleIdToken = account?.idToken
            val displayName = account?.displayName ?: "User"

            toast("Signed in as $displayName")
            subtitleText.text = "Welcome, $displayName ✓"

            if (webViewReady) injectGoogleToken()
        } catch (e: ApiException) {
            val msg = when (e.statusCode) {
                10 -> "Google Sign-In: SHA-1 mismatch. Logcat shows your SHA-1. Register it at console.cloud.google.com"
                else -> "Google Sign-In error ${e.statusCode}: ${e.localizedMessage?.take(60)}"
            }
            android.util.Log.e("IRazor", msg)
            toast(msg.take(60))
            statusText.text = msg.take(100)
            statusText.visibility = View.VISIBLE
        } catch (e: Exception) {
            android.util.Log.e("IRazor", "Google Sign-In failed", e)
            toast("Sign-In failed: ${e.message?.take(60)}")
        }
    }

    private fun injectGoogleToken() {
        val token = googleIdToken ?: return
        val js = """
            (function(){
                window.__GOOGLE_ID_TOKEN__ = '${token.replace("'", "\\'")}';
                window.dispatchEvent(new CustomEvent('google-sign-in', { detail: { token: '${
                    token.replace("'", "\\'")
                }' } }));
                console.log('[IRazor] Google token injected');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ── WebView setup ──────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebView() {
        setContentView(R.layout.activity_webview)
        webView = findViewById(R.id.webview)

        // Always English
        isArabic = false

        configureWebView()
        webViewReady = true

        // Deliver any bundle that was loaded before WebView was ready
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
            // SECURITY: these were previously `true`, which lets the local
            // file:// page make same-origin-policy-free requests to ANY
            // file:// or http(s):// origin — a well-known WebView privilege
            // escalation vector. The app's own assets are same-origin under
            // webRoot and still load fine with these disabled.
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs      = false
            cacheMode                        = WebSettings.LOAD_DEFAULT
            databaseEnabled                  = true
            loadsImagesAutomatically         = true
            mediaPlaybackRequiresUserGesture = false
            // SECURITY: the app only talks to https:// endpoints; never allow
            // an https page to silently load http:// (unencrypted) content.
            mixedContentMode                 = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort                  = true
            loadWithOverviewMode             = true
            setSupportZoom(true)
            builtInZoomControls              = true
            displayZoomControls              = false
            defaultTextEncodingName          = "UTF-8"
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
                    // Show visible error so user knows what happened
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
                // SECURITY: never blindly trust an invalid/self-signed certificate.
                // Doing so (the previous handler.proceed()) let an attacker on the
                // network perform a man-in-the-middle attack against every HTTPS
                // request the app makes (API keys, chat content, etc.). The app
                // only talks to file:// (local assets) and known https:// hosts,
                // neither of which should ever produce a real SSL error, so we
                // cancel and surface the problem instead of silently bypassing it.
                android.util.Log.e("IRazor", "SSL error blocked: ${error.primaryError} for ${error.url}")
                handler.cancel()
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectBridgeConfig()
                if (googleIdToken != null) injectGoogleToken()
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }

        loadApp()
    }

    private fun loadApp() {
        val webRoot   = File(filesDir, WEB_ROOT_DIR)
        val indexFile = File(webRoot, "index.html")

        if (!indexFile.exists()) {
            // Try 1: Decrypt bundle.enc from assets
            android.util.Log.i("IRazor", "index.html not found — attempting bundle.enc decryption…")
            updateStatus("Decrypting bundle…")
            if (!decryptBundle(this, webRoot)) {
                // Try 2: Copy index.html directly from assets (no bundle.enc)
                android.util.Log.i("IRazor", "bundle.enc not available — copying index.html from assets…")
                updateStatus("Loading…")
                try {
                    webRoot.mkdirs()
                    assets.open("index.html").use { input ->
                        FileOutputStream(indexFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("IRazor", "Failed to copy index.html: ${e.message}")
                }
            }

            if (!indexFile.exists()) {
                android.util.Log.e("IRazor", "index.html missing after all recovery attempts")
                webView.loadDataWithBaseURL(null, buildFallbackHtml(), "text/html", "UTF-8", null)
                return
            }
        }

        val fileUrl = android.net.Uri.fromFile(indexFile).toString()
        android.util.Log.i("IRazor", "Loading: $fileUrl")
        webView.loadUrl(fileUrl)
    }

    private fun updateStatus(msg: String) {
        try { statusText.text = msg } catch (_: Exception) {}
    }

    // ── Bridge injection ───────────────────────────────────────────────────

    private fun injectBridgeConfig() {
        val rawKey   = decodeApiKey(
            getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getString("api_key_enc", "") ?: ""
        )
        // Safe JSON string — escapes backslash, quotes, newlines
        fun jsStr(s: String) = s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")

        val gpuInfoRaw = try { nativeGetGpuInfo() ?: "null" }
                         catch (_: Throwable) { "null" }
        // Validate gpuInfo is valid JSON — fallback to null if not
        val gpuInfo = try {
            org.json.JSONObject(gpuInfoRaw); gpuInfoRaw   // valid JSON object
        } catch (_: Throwable) {
            try { gpuInfoRaw.toDouble(); gpuInfoRaw }     // valid number
            catch (_: Throwable) { "null" }              // invalid — use null
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

    // ── Download handling ──────────────────────────────────────────────────

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

    // ── Bundle delivery to WebView ─────────────────────────────────────────

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

    // ── Language (kept for API compat, always English) ─────────────────────

    fun getCurrentLang(): String = "en"

    fun setLanguage(arabic: Boolean) {
        // Always English — ignore
        isArabic = false
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "if(window.onNativeLangChange) window.onNativeLangChange('en');", null
            )
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────

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
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            moveTaskToBack(true)
        }
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
          <h2>⚡ IRazor AI</h2>
          <p>App files not found. Please restart the app.</p>
        </div></body></html>
    """.trimIndent()
}
