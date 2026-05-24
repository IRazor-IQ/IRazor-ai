package com.irazor.ai

import android.content.ContentValues
import android.content.Context
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
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

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
            if (!file.exists() || !file.isFile) return "null"
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            """{"error":"${e.message}"}"""
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
            if (fileList == null) {
                // Fallback: use shell ls to bypass any Java IO restriction
                val proc = Runtime.getRuntime().exec(arrayOf("ls", "-la", path))
                proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                val out = proc.inputStream.bufferedReader().readText()
                // Parse ls -la output into JSON
                val entries = out.lines().filter { it.isNotBlank() && !it.startsWith("total") }
                    .mapNotNull { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size < 9) return@mapNotNull null
                        val name = parts.drop(8).joinToString(" ")
                        if (name == "." || name == "..") return@mapNotNull null
                        val isDir = line.startsWith("d")
                        val size = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                        val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                        """{"name":"$name","path":"$fullPath","isDir":$isDir,"size":$size}"""
                    }
                return entries.joinToString(",", "[", "]")
            }
            fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                .map { file ->
                    """{"name":"${file.name}","path":"${file.absolutePath}","isDir":${file.isDirectory},"size":${file.length()}}"""
                }.joinToString(",", "[", "]")
        } catch (e: Exception) {
            """{"error":"${e.message}"}"""
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
    fun listDirRecursive(path: String, maxDepth: Int): String {
        val results = mutableListOf<String>()
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
            val children = dir.listFiles() ?: shellList(dir)
            children.sortedWith(compareBy({ !it.isDirectory }, { it.name })).forEach { f ->
                results += """{"name":"${f.name}","path":"${f.absolutePath}","isDir":${f.isDirectory},"size":${f.length()},"depth":$depth}"""
                if (f.isDirectory && depth < maxDepth) walk(f, depth + 1)
            }
        }
        val root = File(path)
        if (!root.exists()) return "[]"
        walk(root, 0)
        return results.joinToString(",", "[", "]")
    }

    @JavascriptInterface
    fun executeShell(command: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val finished = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) { proc.destroyForcibly(); return """{"error":"timeout","ok":false}""" }
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()
            val exit   = proc.exitValue()
            org.json.JSONObject().apply {
                put("exit", exit); put("stdout", stdout.take(100_000))
                put("stderr", stderr.take(10_000)); put("ok", exit == 0)
            }.toString()
        } catch (e: Exception) { """{"error":"${e.message}","ok":false}""" }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: throw Exception("MediaStore insert failed")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            FileOutputStream(File(dir, filename)).use { it.write(bytes) }
        }
    }
}
