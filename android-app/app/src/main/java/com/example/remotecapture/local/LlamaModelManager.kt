package com.PrepPro.mobile.local

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class LlamaModelManager(private val context: Context) {
    data class ModelMeta(
        val bundledAssetPath: String,
        val downloadedFileName: String,
        val remoteUrl: String,
        val sha256: String,
    )

    private val prefs by lazy {
        context.getSharedPreferences("llama_model_prefs", Context.MODE_PRIVATE)
    }

    fun resolveActiveModelPath(): String? {
        val downloaded = resolveDownloadedModelPath()
        if (downloaded != null) {
            return downloaded
        }
        return ensureBundledModelCopied()
    }

    fun loadMeta(): ModelMeta {
        val text = context.assets.open("llama/model_meta.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(text)
        return ModelMeta(
            bundledAssetPath = json.optString("bundledAssetPath", "llama/classifier_model.gguf"),
            downloadedFileName = json.optString("downloadedFileName", "classifier_downloaded.gguf"),
            remoteUrl = json.optString("remoteUrl", ""),
            sha256 = json.optString("sha256", ""),
        )
    }

    fun downloadEnhancedModelIfConfigured(connectTimeoutMs: Int = 5000, readTimeoutMs: Int = 30000): Boolean {
        val meta = loadMeta()
        val url = meta.remoteUrl.trim()
        if (url.isEmpty()) return false

        val target = File(context.filesDir, "llama/${meta.downloadedFileName}")
        target.parentFile?.mkdirs()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
        }
        return try {
            conn.connect()
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            prefs.edit().putLong("downloadedTs", System.currentTimeMillis()).apply()
            true
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun resolveDownloadedModelPath(): String? {
        val meta = loadMeta()
        val file = File(context.filesDir, "llama/${meta.downloadedFileName}")
        if (file.exists() && file.isFile && file.length() > 0L) {
            return file.absolutePath
        }
        return null
    }

    private fun ensureBundledModelCopied(): String? {
        val meta = loadMeta()
        val target = File(context.filesDir, "llama/classifier_bundled.gguf")
        if (target.exists() && target.length() > 0L) {
            return target.absolutePath
        }
        target.parentFile?.mkdirs()
        return try {
            context.assets.open(meta.bundledAssetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            target.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}

