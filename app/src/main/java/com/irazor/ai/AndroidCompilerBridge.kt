package com.irazor.ai

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.PowerManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import com.google.firebase.firestore.FirebaseFirestore

import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.tasks.Tasks

class AndroidCompilerBridge(activity: AppCompatActivity) {

    private val ref = WeakReference(activity)
    private val ctx: Context get() = ref.get()?.applicationContext ?: throw IllegalStateException("Activity lost")
    private val prefs get() = ctx.getSharedPreferences(MainActivity.PREF_FILE, Context.MODE_PRIVATE)

    @JavascriptInterface
    fun isOnline(): Boolean {
        val activity = ref.get() ?: return false
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @JavascriptInterface
    fun getApiKey(): String = MainActivity.decodeApiKey(
        prefs.getString("api_key_enc", "") ?: ""
    )

    @JavascriptInterface
    fun saveApiKey(rawKey: String) {
        prefs.edit().putString("api_key_enc", MainActivity.encodeApiKey(rawKey)).apply()
    }

    @JavascriptInterface
    fun getApiUrl(): String = "https://opencode.ai/zen/v1/chat/completions"

    @JavascriptInterface
    fun getVersion(): String = "5.0"

    @JavascriptInterface
    fun getDevice(): String = Build.MODEL

    @JavascriptInterface
    fun getLang(): String {
        val activity = ref.get() ?: return "en"
        return if (activity is MainActivity) activity.getCurrentLang() else "en"
    }

    @JavascriptInterface
    fun showToast(message: String) {
        ref.get()?.runOnUiThread {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun showNotification(title: String, text: String) {
        try {
            val activity = ref.get() ?: return
            val channelId = "irazor_build"
            val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(channelId, "Build Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "APK Builder completion alerts"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
            // Tap notification → open app
            val intent = Intent(activity, activity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                ctx, 0, intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                else android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .build()
            nm.notify(1001, notification)
        } catch (e: Exception) {
            // Fallback to toast if notification fails
            showToast(text)
        }
    }

    @JavascriptInterface
    fun pickFile() {
        ref.get()?.runOnUiThread {
            if (ref.get() is MainActivity) {
                (ref.get() as MainActivity).triggerFilePicker()
            } else if (ref.get() is WebViewActivity) {
                (ref.get() as WebViewActivity).triggerFilePicker()
            }
        }
    }

    @JavascriptInterface
    fun getPlatformInfo(): String {
        val activity = ref.get() ?: return """{"error":"no_activity"}"""
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return """{
            "os": "android",
            "apiLevel": ${Build.VERSION.SDK_INT},
            "device": "${Build.MODEL}",
            "brand": "${Build.BRAND}",
            "manufacturer": "${Build.MANUFACTURER}",
            "hardwareAccelerated": true,
            "memoryClass": ${am.memoryClass},
            "nativeEngine": true,
            "ndk": "r27"
        }""".replace("\n", " ")
    }

    @JavascriptInterface
    fun nativeProcessBuffer(b64Data: String, ext: String, flags: Int): String {
        val activity = ref.get() ?: return """{"error":"no_activity"}"""
        return try {
            val raw = Base64.decode(b64Data, Base64.NO_WRAP)
            activity.let {
                when (it) {
                    is MainActivity -> it.nativeProcessBuffer(raw, ext, flags)
                    is WebViewActivity -> it.nativeProcessBuffer(raw, ext, flags)
                    else -> null
                }
            } ?: """{"error":"null_result"}"""
        } catch (e: Exception) {
            """{"error":"${e.message}"}"""
        }
    }

    @JavascriptInterface
    fun nativeGetGpuInfo(): String {
        val activity = ref.get() ?: return """{"error":"no_activity"}"""
        return try {
            when (activity) {
                is MainActivity -> activity.nativeGetGpuInfo()
                is WebViewActivity -> activity.nativeGetGpuInfo()
                else -> null
            } ?: """{"error":"no_gpu_info"}"""
        } catch (e: Exception) {
            """{"error":"${e.message}"}"""
        }
    }

    @JavascriptInterface
    fun nativeDispatchCompute(b64Input: String, operation: Int): String {
        val activity = ref.get() ?: return """{"error":"no_activity"}"""
        return try {
            val input = Base64.decode(b64Input, Base64.NO_WRAP)
            val output = when (activity) {
                is MainActivity -> activity.nativeDispatchCompute(input, operation)
                is WebViewActivity -> activity.nativeDispatchCompute(input, operation)
                else -> null
            }
            if (output != null) Base64.encodeToString(output, Base64.NO_WRAP) else "null"
        } catch (e: Exception) {
            """{"error":"${e.message}"}"""
        }
    }

    @JavascriptInterface
    fun readFile(path: String): String {
        return try {
            val file = File(path)
            if (!file.exists()) return org.json.JSONObject().apply { put("error", "path does not exist: $path") }.toString()
            if (!file.isFile) return org.json.JSONObject().apply { put("error", "not a file: $path") }.toString()
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            org.json.JSONObject().apply { put("error", e.message ?: "read failed") }.toString()
        }
    }

    @JavascriptInterface
    fun writeFile(path: String, content: String): Boolean {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(Base64.decode(content, Base64.NO_WRAP))
            true
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun listDir(path: String): String {
        return try {
            val dir = File(path)
            if (!dir.exists()) return """{"error":"path does not exist: $path"}"""
            if (!dir.isDirectory) return """{"error":"not a directory: $path"}"""
            val fileList = dir.listFiles()
            if (fileList == null || fileList.isEmpty()) {
                // Fallback: use shell ls to bypass any Java IO restriction
                val proc = Runtime.getRuntime().exec(arrayOf("ls", "-la", path))
                proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                val out = proc.inputStream.bufferedReader().readText()
                val arr = org.json.JSONArray()
                out.lines().filter { it.isNotBlank() && !it.startsWith("total") }
                    .forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size < 9) return@forEach
                        val name = parts.drop(8).joinToString(" ")
                        if (name == "." || name == "..") return@forEach
                        val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                        val obj = org.json.JSONObject()
                        obj.put("name", name)
                        obj.put("path", fullPath)
                        obj.put("isDir", line.startsWith("d"))
                        obj.put("size", parts.getOrNull(4)?.toLongOrNull() ?: 0L)
                        arr.put(obj)
                    }
                return arr.toString()
            }
            val arr = org.json.JSONArray()
            fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                .forEach { file ->
                    val obj = org.json.JSONObject()
                    obj.put("name", file.name)
                    obj.put("path", file.absolutePath)
                    obj.put("isDir", file.isDirectory)
                    obj.put("size", file.length())
                    arr.put(obj)
                }
            return arr.toString()
        } catch (e: Exception) {
            return org.json.JSONObject().apply { put("error", e.message ?: "unknown") }.toString()
        }
    }

    @JavascriptInterface
    fun renderFrame() {
        try {
            val activity = ref.get()
            when (activity) {
                is MainActivity -> activity.nativeRenderFrame()
                is WebViewActivity -> activity.nativeRenderFrame()
            }
        } catch (_: UnsatisfiedLinkError) {}
    }

    @JavascriptInterface
    fun saveBlobDownload(byteArrayJson: String, contentDisposition: String, mimetype: String) {
        try {
            val arr = org.json.JSONArray(byteArrayJson)
            val bytes = ByteArray(arr.length()) { arr.getInt(it).toByte() }
            val name = guessName(contentDisposition, mimetype)
            saveToDownloads(name, bytes)
            ref.get()?.runOnUiThread {
                Toast.makeText(ctx, "Downloaded: $name", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            ref.get()?.runOnUiThread {
                Toast.makeText(ctx, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun saveDataUrlDownload(dataUrl: String, contentDisposition: String) {
        try {
            val commaIdx = dataUrl.indexOf(',')
            val header = dataUrl.substring(0, commaIdx)
            val data = dataUrl.substring(commaIdx + 1)
            val mimetype = header.substringAfter("data:").substringBefore(";")
            val bytes = if (header.contains("base64"))
                Base64.decode(data, Base64.DEFAULT)
            else data.toByteArray(Charsets.UTF_8)
            val name = guessName(contentDisposition, mimetype)
            saveToDownloads(name, bytes)
            ref.get()?.runOnUiThread {
                Toast.makeText(ctx, "Downloaded: $name", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            ref.get()?.runOnUiThread {
                Toast.makeText(ctx, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Called from JS: saveFile(base64Content, filename)
     * Saves any file to Downloads. Works API 21+.
     */
    @JavascriptInterface
    fun saveFile(b64Content: String, filename: String) {
        try {
            val bytes = Base64.decode(b64Content, Base64.DEFAULT)
            saveToDownloads(filename, bytes)
            ref.get()?.runOnUiThread {
                Toast.makeText(ctx, "Saved: $filename", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            ref.get()?.runOnUiThread {
                Toast.makeText(ctx, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Called from JS: saveTextFile(textContent, filename)
     * Plain text — no base64 needed.
     */
    @JavascriptInterface
    fun saveTextFile(content: String, filename: String) {
        try {
            saveToDownloads(filename, content.toByteArray(Charsets.UTF_8))
            ref.get()?.runOnUiThread {
                Toast.makeText(ctx, "Saved: $filename", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            ref.get()?.runOnUiThread {
                Toast.makeText(ctx, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun deleteFile(path: String): Boolean {
        return try { File(path).deleteRecursively() } catch (e: Exception) { false }
    }

    @JavascriptInterface
    fun createDir(path: String): Boolean {
        return try { File(path).mkdirs() } catch (e: Exception) { false }
    }

    @JavascriptInterface
    fun extractZipToDevice(inputZipPath: String, outputDirPath: String): Boolean {
        return try {
            val zipFile = File(inputZipPath)
            if (!zipFile.exists()) return false
            val outputDir = File(outputDirPath)
            outputDir.mkdirs()
            val outputDirCanonical = outputDir.canonicalPath + File.separator
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val targetFile = File(outputDir, entry.name)
                    // SECURITY (Zip Slip): reject entries like "../../etc/foo"
                    // that would write outside outputDir.
                    val targetCanonical = targetFile.canonicalPath
                    if (!targetCanonical.startsWith(outputDirCanonical)) {
                        android.util.Log.w("IRazor", "Blocked unsafe zip entry: ${entry.name}")
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun listDirRecursive(path: String, maxDepth: Int): String {
        val arr = org.json.JSONArray()
        fun shellList(dir: File): Array<File> = try {
            val proc = Runtime.getRuntime().exec(arrayOf("ls", "-la", dir.absolutePath))
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            proc.inputStream.bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("total") }
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 9) return@mapNotNull null
                    val name = parts.drop(8).joinToString(" ")
                    if (name == "." || name == "..") return@mapNotNull null
                    File("${dir.absolutePath}/$name")
                }.toTypedArray()
        } catch (e: Exception) { emptyArray() }

        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth) return
            var children = dir.listFiles()
            if (children == null || children.isEmpty()) children = shellList(dir)
            if (children.isEmpty()) return
            children.sortedWith(compareBy({ !it.isDirectory }, { it.name })).forEach { f ->
                val obj = org.json.JSONObject()
                obj.put("name", f.name)
                obj.put("path", f.absolutePath)
                obj.put("isDir", f.isDirectory)
                obj.put("size", f.length())
                obj.put("depth", depth)
                arr.put(obj)
                if (f.isDirectory && depth < maxDepth) walk(f, depth + 1)
            }
        }
        val root = File(path)
        if (!root.exists()) return "[]"
        walk(root, 0)
        return arr.toString()
    }

    @JavascriptInterface
    fun executeShell(command: String): String {
        return try {
            // ── Smart pre-check: intercept commands that always fail on Android ──
            val cmd = command.trim()

            // ZIP packaging: redirect to JSZip via a special marker
            // The JS side handles this via createZipFromDir bridge
            if (cmd.matches(Regex(".*\\bzip\\b.*"))) {
                return """{"exit":127,"ok":false,"stderr":"zip: command not available on Android. Use AndroidCompilerBridge.createZipFromDir() or JSZip in JavaScript instead.","stdout":"","hint":"USE_JZIP"}"""
            }

            // apt/apt-get/yum/brew — not available on Android
            if (cmd.matches(Regex("\\s*(apt|apt-get|yum|brew|dnf|pkg)\\s.*"))) {
                return """{"exit":127,"ok":false,"stderr":"Package managers are not available on Android.","stdout":""}"""
            }

            // python/python3/node/npm — try but return helpful error if missing
            // These are allowed to run — just let the process determine exit code

            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return """{"exit":-1,"ok":false,"error":"timeout","stdout":"","stderr":"Command timed out after 30 seconds"}"""
            }
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            val exit   = proc.exitValue()

            // exit 127 = command not found — give a helpful hint
            val hint = if (exit == 127) buildHint(cmd) else ""
            val hintJson = if (hint.isNotEmpty()) ""","hint":"$hint"""" else ""

            org.json.JSONObject().apply {
                put("exit", exit)
                put("stdout", stdout.take(100_000))
                put("stderr", stderr.take(10_000))
                put("ok", exit == 0)
                if (hint.isNotEmpty()) put("hint", hint)
            }.toString()
        } catch (e: Exception) {
            """{"exit":-1,"ok":false,"error":"${e.message?.replace("\"","'")}","stdout":"","stderr":""}"""
        }
    }

    /**
     * Native ZIP creation: packs a directory into a ZIP file and saves to Downloads.
     * Replaces the `zip -r` shell command which doesn't exist on Android.
     * Returns JSON: {"ok":true,"path":"/storage/.../file.zip","size":12345}
     */
    @JavascriptInterface
    fun createZipFromDir(srcDir: String, zipName: String, excludePatterns: String): String {
        return try {
            val src = File(srcDir)
            if (!src.exists()) return """{"ok":false,"error":"Source directory not found: $srcDir"}"""
            val excludes = excludePatterns.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val baos = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(baos).use { zos ->
                fun addFile(file: File, entryName: String) {
                    if (excludes.any { ex -> file.absolutePath.contains(ex.trimStart('*')) }) return
                    if (file.isDirectory) {
                        file.listFiles()?.forEach { addFile(it, "$entryName/${it.name}") }
                    } else {
                        try {
                            zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                            file.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        } catch (_: Exception) {}
                    }
                }
                src.listFiles()?.forEach { addFile(it, it.name) }
            }
            val bytes = baos.toByteArray()
            val safeZipName = if (zipName.endsWith(".zip")) zipName else "$zipName.zip"
            saveToDownloads(safeZipName, bytes)
            val outPath = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/$safeZipName"
            """{"ok":true,"path":"$outPath","size":${bytes.size},"name":"$safeZipName"}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message?.replace("\"", "'")}"}"""
        }
    }

    private fun buildHint(cmd: String): String {
        return when {
            cmd.contains("zip") || cmd.contains("unzip") ->
                "Use JSZip in JavaScript or AndroidCompilerBridge.extractZipToDevice() instead"
            cmd.contains("python") || cmd.contains("python3") ->
                "Python is not installed on Android. Use JavaScript or Kotlin instead"
            cmd.contains("node") || cmd.contains("npm") ->
                "Node.js is not available on Android. Use JavaScript in WebView instead"
            cmd.contains("git ") ->
                "git is not installed. Use AndroidCompilerBridge for file operations instead"
            cmd.contains("curl") || cmd.contains("wget") ->
                "Use fetch() in JavaScript for HTTP requests instead"
            cmd.contains("gradle") || cmd.contains("./gradlew") ->
                "Gradle builds require Android Studio. Cannot build APKs on-device"
            cmd.contains("javac") || cmd.contains("kotlinc") ->
                "Java/Kotlin compilers are not available in this environment"
            else -> "Command not found on Android. Use bridge file operations or JavaScript instead"
        }
    }

    // ── Screen Context (Accessibility Service) ──────────────────────────

    @JavascriptInterface
    fun signIn() {
        ref.get()?.runOnUiThread {
            if (ref.get() is MainActivity) {
                (ref.get() as MainActivity).signInWithGoogle()
            }
        }
    }

    @JavascriptInterface
    fun switchGoogleAccount() {
        ref.get()?.runOnUiThread {
            if (ref.get() is MainActivity) {
                (ref.get() as MainActivity).switchAccount()
            }
        }
    }

    @JavascriptInterface
    fun getScreenContext(): String {
        return ScreenContextRepository.getContext()
    }

    @JavascriptInterface
    fun getForegroundApp(): String {
        return ScreenContextRepository.getForegroundApp()
    }

    // ── Conversation backup (survives uninstall — stored in shared Downloads) ──

    @JavascriptInterface
    fun saveConversationsBackup(jsonData: String) {
        val bytes = jsonData.toByteArray(Charsets.UTF_8)
        try {
            // Always save to internal (may survive if Play Services auto-backup works)
            val intFile = File(ctx.filesDir, "irazor_convs_backup.json")
            intFile.parentFile?.mkdirs()
            intFile.writeText(jsonData, Charsets.UTF_8)
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // Delete existing file in MediaStore
                val existing = ctx.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf("conversations.json"), null
                )
                existing?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        ctx.contentResolver.delete(
                            ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                            null, null
                        )
                    }
                }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "conversations.json")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/IRazor_Backups")
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put("is_pending", 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    val done = ContentValues().apply { put("is_pending", 0) }
                    ctx.contentResolver.update(uri, done, null, null)
                }
            } catch (_: Exception) {}
        } else {
            try {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "IRazor_Backups")
                dir.mkdirs()
                File(dir, "conversations.json").writeBytes(bytes)
            } catch (_: Exception) {}
        }
    }

    @JavascriptInterface
    fun loadConversationsBackup(): String {
        // Try external Downloads/IRazor_Backups/conversations.json first
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cursor = ctx.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf("conversations.json"), null
                )
                cursor?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        return ctx.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        } ?: ""
                    }
                }
            } catch (_: Exception) {}
        } else {
            try {
                val extFile = File(
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "IRazor_Backups"),
                    "conversations.json"
                )
                if (extFile.exists()) return extFile.readText(Charsets.UTF_8)
            } catch (_: Exception) {}
        }
        // Fallback to internal filesDir
        return try {
            val intFile = File(ctx.filesDir, "irazor_convs_backup.json")
            if (intFile.exists()) intFile.readText(Charsets.UTF_8) else ""
        } catch (_: Exception) { "" }
    }

    @JavascriptInterface
    fun isAccessibilityServiceEnabled(): Boolean {
        return ScreenContextRepository.isServiceEnabled()
    }

    @JavascriptInterface
    fun getScreenContextAge(): Long {
        val now = System.currentTimeMillis()
        val then = ScreenContextRepository.getTimestamp()
        return if (then == 0L) -1 else now - then
    }

    @JavascriptInterface
    fun searchInFiles(dir: String, pattern: String, maxResults: Int): String {
        val results = mutableListOf<String>()
        val skipExts = setOf("apk","jar","so","class","db","png","jpg","jpeg","webp","gif","bmp","zip","bin","dex")
        fun escJson(s: String) = s.replace("\\","\\\\").replace("\"","\\\"").take(300)
        fun walk(f: File) {
            if (results.size >= maxResults) return
            if (f.isDirectory) { try { f.listFiles()?.forEach { walk(it) } } catch (_: Exception) {}; return }
            if (f.extension.lowercase() in skipExts || f.length() > 5_000_000) return
            try {
                f.bufferedReader().useLines { lines ->
                    lines.forEachIndexed { i, line ->
                        if (results.size >= maxResults) return@useLines
                        if (line.contains(pattern, ignoreCase = true))
                            results += """{"file":"${escJson(f.absolutePath)}","lineNumber":${i+1},"line":"${escJson(line.trim())}"}"""
                    }
                }
            } catch (_: Exception) {}
        }
        walk(File(dir))
        return results.joinToString(",", "[", "]")
    }

    private fun guessName(disposition: String, mimetype: String): String {
        Regex("""filename[^;=\n]*=(['""]?)([^'""\n]+)\1""").find(disposition)
            ?.groupValues?.get(2)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val ext = when {
            mimetype.contains("python") -> "py"
            mimetype.contains("javascript") -> "js"
            mimetype.contains("html") -> "html"
            mimetype.contains("json") -> "json"
            mimetype.contains("zip") -> "zip"
            mimetype.contains("text") -> "txt"
            else -> "bin"
        }
        return "irazor_${System.currentTimeMillis()}.$ext"
    }

    private fun saveToDownloads(filename: String, bytes: ByteArray) {
        val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "").take(80)
        val mime = when {
            safeName.endsWith(".zip") -> "application/zip"
            safeName.endsWith(".apk") -> "application/vnd.android.package-archive"
            safeName.endsWith(".png") -> "image/png"
            safeName.endsWith(".jpg") || safeName.endsWith(".jpeg") -> "image/jpeg"
            safeName.endsWith(".txt") || safeName.endsWith(".md") -> "text/plain"
            safeName.endsWith(".xml") -> "text/xml"
            safeName.endsWith(".json") -> "application/json"
            safeName.endsWith(".html") || safeName.endsWith(".htm") -> "text/html"
            safeName.endsWith(".js") -> "application/javascript"
            safeName.endsWith(".css") -> "text/css"
            safeName.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Try up to 3 times with progressively more unique filenames
            var name = safeName
            for (attempt in 0 until 3) {
                // Delete existing file with same name
                try {
                    val existing = ctx.contentResolver.query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, null,
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null
                    )
                    existing?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                            val delUri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                            ctx.contentResolver.delete(delUri, null, null)
                        }
                    }
                } catch (_: Exception) {}
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put("is_pending", 1)
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        val done = ContentValues().apply { put("is_pending", 0) }
                        ctx.contentResolver.update(uri, done, null, null)
                    } catch (e: Exception) {
                        ctx.contentResolver.delete(uri, null, null)
                        throw e
                    }
                    return
                }
                // Insert failed — try with unique suffix
                name = safeName.replace(Regex("(\\.\\w+)\$"), "_${System.currentTimeMillis()}$1")
            }
            throw Exception("MediaStore insert failed after 3 attempts")
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            FileOutputStream(File(dir, safeName)).use { it.write(bytes) }
        }
    }

    // ── Firebase Firestore Chat Sync ──────────────────────────────────────────
    @JavascriptInterface
    fun firestoreSaveChats(json: String) {
        val userId = FirebaseAuth.getInstance().uid ?: return
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(userId)
            .collection("chats")
            .document("all_chats")
            .set(mapOf("data" to json, "updatedAt" to System.currentTimeMillis()))
    }

    @JavascriptInterface
    fun firestoreLoadChats(): String {
        return try {
            val userId = FirebaseAuth.getInstance().uid ?: return "null"
            val task = FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .collection("chats")
                .document("all_chats")
                .get()
            val doc = Tasks.await(task, 10, java.util.concurrent.TimeUnit.SECONDS)
            doc.getString("data") ?: "null"
        } catch (e: Exception) {
            "null"
        }
    }

    @JavascriptInterface
    fun firestoreDeleteAllChats() {
        val userId = FirebaseAuth.getInstance().uid ?: return
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(userId)
            .collection("chats")
            .document("all_chats")
            .delete()
    }

    @JavascriptInterface
    fun firebaseSignInWithGoogle(idToken: String) {
        try {
            val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
            FirebaseAuth.getInstance().signInWithCredential(credential)
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        android.util.Log.w("Bridge", "Firebase sign-in failed", task.exception)
                    }
                }
        } catch (e: Exception) {
            android.util.Log.w("Bridge", "Firebase sign-in error", e)
        }
    }

    @JavascriptInterface
    fun getFirebaseUid(): String = FirebaseAuth.getInstance().uid ?: ""

    // ── AI Thinking Service ───────────────────────────────────────────────────
    @JavascriptInterface
    fun startThinkingService() {
        AIThinkingService.start(ctx)
    }

    @JavascriptInterface
    fun updateThinkingService(text: String) {
        AIThinkingService.update(ctx, text)
    }

    @JavascriptInterface
    fun stopThinkingService() {
        AIThinkingService.stop(ctx)
    }
}
