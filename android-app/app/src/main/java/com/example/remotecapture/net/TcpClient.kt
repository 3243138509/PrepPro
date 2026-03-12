package com.PrepPro.mobile.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.PrepPro.mobile.protocol.Frames
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.Socket
import java.util.UUID

class TcpClient(
    private val host: String,
    private val port: Int,
    private val password: String = ""
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val DEFAULT_READ_TIMEOUT_MS = 8000
        private const val ANALYZE_READ_TIMEOUT_MS = 120000
    }

    data class AnalyzeResult(
        val text: String,
        val ocrText: String,
        val modelNotice: String,
    )

    data class DisplayInfo(
        val id: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    )

    data class ClipboardPush(
        val requestId: String,
        val text: String,
    )

    data class ModelSetting(
        val apiUrl: String,
        val apiKey: String,
        val modelName: String,
    )

    data class ModelSettingsResult(
        val profiles: List<ModelSetting>,
        val activeIndex: Int,
    )

    fun listDisplays(): List<DisplayInfo> {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = DEFAULT_READ_TIMEOUT_MS

            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            Frames.sendJson(output, JSONObject().apply {
                put("type", "AUTH")
                put("password", password)
            })

            val authReply = Frames.readJson(input)
            if (authReply.optString("type") != "AUTH_OK") {
                throw IllegalStateException(authReply.optString("message", "auth failed"))
            }

            Frames.sendJson(output, JSONObject().apply {
                put("type", "LIST_DISPLAYS")
            })

            val response = Frames.readJson(input)
            if (response.optString("type") != "DISPLAYS") {
                throw IllegalStateException(response.optString("message", "list displays failed"))
            }

            val arr = response.optJSONArray("displays")
                ?: throw IllegalStateException("invalid displays payload")
            val result = mutableListOf<DisplayInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    DisplayInfo(
                        id = obj.optInt("id", 1),
                        left = obj.optInt("left", 0),
                        top = obj.optInt("top", 0),
                        width = obj.optInt("width", 0),
                        height = obj.optInt("height", 0)
                    )
                )
            }
            return result
        }
    }

    fun authenticateOnly() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = DEFAULT_READ_TIMEOUT_MS

            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            Frames.sendJson(output, JSONObject().apply {
                put("type", "AUTH")
                put("password", password)
            })

            val authReply = Frames.readJson(input)
            if (authReply.optString("type") != "AUTH_OK") {
                throw IllegalStateException(authReply.optString("message", "auth failed"))
            }
        }
    }

    fun capture(displayId: Int = 1): Bitmap {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = DEFAULT_READ_TIMEOUT_MS

            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            Frames.sendJson(output, JSONObject().apply {
                put("type", "AUTH")
                put("password", password)
            })

            val authReply = Frames.readJson(input)
            if (authReply.optString("type") != "AUTH_OK") {
                throw IllegalStateException(authReply.optString("message", "auth failed"))
            }

            val requestId = UUID.randomUUID().toString()
            Frames.sendJson(output, JSONObject().apply {
                put("type", "CAPTURE")
                put("requestId", requestId)
                put("quality", 75)
                put("displayId", displayId)
            })

            val response = Frames.readJson(input)
            when (response.optString("type")) {
                "IMAGE" -> {
                    if (response.optString("requestId") != requestId) {
                        throw IllegalStateException("requestId mismatch")
                    }
                    val base64 = response.optString("imageBase64")
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    return bmp ?: throw IllegalStateException("decode bitmap failed")
                }
                "ERROR" -> {
                    throw IllegalStateException(response.optString("message", "server error"))
                }
                else -> throw IllegalStateException("unexpected response")
            }
        }
    }

    fun analyzeImage(bitmap: Bitmap, prompt: String): AnalyzeResult {
        val imageBase64 = encodeBitmapToBase64(bitmap)

        repeat(2) { attempt ->
            try {
                return analyzeImageOnce(imageBase64, prompt)
            } catch (ex: SocketTimeoutException) {
                if (attempt == 1) {
                    throw IllegalStateException(
                        "server analyze response timed out (> ${ANALYZE_READ_TIMEOUT_MS / 1000}s)",
                        ex,
                    )
                }
            }
        }

        throw IllegalStateException("analyze request failed")
    }

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val bytesOut = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytesOut)
        if (!ok) throw IllegalStateException("encode bitmap failed")
        return Base64.encodeToString(bytesOut.toByteArray(), Base64.NO_WRAP)
    }

    private fun analyzeImageOnce(imageBase64: String, prompt: String): AnalyzeResult {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = ANALYZE_READ_TIMEOUT_MS

            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            Frames.sendJson(output, JSONObject().apply {
                put("type", "AUTH")
                put("password", password)
            })

            val authReply = Frames.readJson(input)
            if (authReply.optString("type") != "AUTH_OK") {
                throw IllegalStateException(authReply.optString("message", "auth failed"))
            }

            val requestId = UUID.randomUUID().toString()
            Frames.sendJson(output, JSONObject().apply {
                put("type", "ANALYZE_IMAGE")
                put("requestId", requestId)
                put("imageBase64", imageBase64)
                put("prompt", prompt)
            })

            val response = Frames.readJson(input)
            when (response.optString("type")) {
                "ANALYZE_RESULT" -> {
                    if (response.optString("requestId") != requestId) {
                        throw IllegalStateException("requestId mismatch")
                    }
                    return AnalyzeResult(
                        text = response.optString("text", ""),
                        ocrText = response.optString("ocrText", ""),
                        modelNotice = response.optString("modelNotice", ""),
                    )
                }

                "ERROR" -> {
                    throw IllegalStateException(response.optString("message", "server error"))
                }

                else -> throw IllegalStateException("unexpected response")
            }
        }
    }

    fun analyzeText(text: String, prompt: String): AnalyzeResult {
        repeat(2) { attempt ->
            try {
                return analyzeTextOnce(text, prompt)
            } catch (ex: SocketTimeoutException) {
                if (attempt == 1) {
                    throw IllegalStateException(
                        "server analyze response timed out (> ${ANALYZE_READ_TIMEOUT_MS / 1000}s)",
                        ex,
                    )
                }
            }
        }

        throw IllegalStateException("analyze text request failed")
    }

    private fun analyzeTextOnce(text: String, prompt: String): AnalyzeResult {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = ANALYZE_READ_TIMEOUT_MS

            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            Frames.sendJson(output, JSONObject().apply {
                put("type", "AUTH")
                put("password", password)
            })

            val authReply = Frames.readJson(input)
            if (authReply.optString("type") != "AUTH_OK") {
                throw IllegalStateException(authReply.optString("message", "auth failed"))
            }

            val requestId = UUID.randomUUID().toString()
            Frames.sendJson(output, JSONObject().apply {
                put("type", "ANALYZE_TEXT")
                put("requestId", requestId)
                put("text", text)
                put("prompt", prompt)
            })

            val response = Frames.readJson(input)
            when (response.optString("type")) {
                "ANALYZE_RESULT" -> {
                    if (response.optString("requestId") != requestId) {
                        throw IllegalStateException("requestId mismatch")
                    }
                    return AnalyzeResult(
                        text = response.optString("text", ""),
                        ocrText = response.optString("ocrText", ""),
                        modelNotice = response.optString("modelNotice", ""),
                    )
                }

                "ERROR" -> {
                    throw IllegalStateException(response.optString("message", "server error"))
                }

                else -> throw IllegalStateException("unexpected response")
            }
        }
    }

    fun subscribeClipboard(onClipboardText: (ClipboardPush) -> Unit) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = 0

            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            Frames.sendJson(output, JSONObject().apply {
                put("type", "AUTH")
                put("password", password)
            })

            val authReply = Frames.readJson(input)
            if (authReply.optString("type") != "AUTH_OK") {
                throw IllegalStateException(authReply.optString("message", "auth failed"))
            }

            Frames.sendJson(output, JSONObject().apply {
                put("type", "SUBSCRIBE_CLIPBOARD")
            })

            while (true) {
                val response = Frames.readJson(input)
                when (response.optString("type")) {
                    "CLIPBOARD_TEXT" -> {
                        val text = response.optString("text", "")
                        if (text.isBlank()) {
                            continue
                        }
                        onClipboardText(
                            ClipboardPush(
                                requestId = response.optString("requestId", ""),
                                text = text,
                            )
                        )
                    }

                    "ERROR" -> {
                        throw IllegalStateException(response.optString("message", "server error"))
                    }
                }
            }
        }
    }

    fun setClipboardText(text: String) {
        withAuthedSocket(DEFAULT_READ_TIMEOUT_MS) { input, output ->
            val requestId = UUID.randomUUID().toString()
            Frames.sendJson(output, JSONObject().apply {
                put("type", "SET_CLIPBOARD_TEXT")
                put("requestId", requestId)
                put("text", text)
            })

            val response = Frames.readJson(input)
            when (response.optString("type")) {
                "CLIPBOARD_SET_OK" -> {
                    if (response.optString("requestId") != requestId) {
                        throw IllegalStateException("requestId mismatch")
                    }
                }

                "ERROR" -> {
                    throw IllegalStateException(response.optString("message", "server error"))
                }

                else -> throw IllegalStateException("unexpected response")
            }
        }
    }

    fun getModelSettings(): ModelSettingsResult {
        return withAuthedSocket(DEFAULT_READ_TIMEOUT_MS) { input, output ->
            Frames.sendJson(output, JSONObject().apply {
                put("type", "GET_MODEL_SETTINGS")
            })

            val response = Frames.readJson(input)
            parseModelSettingsResponse(response)
        }
    }

    fun detectModels(modelApiUrl: String, modelApiKey: String): List<String> {
        return withAuthedSocket(ANALYZE_READ_TIMEOUT_MS) { input, output ->
            val requestId = UUID.randomUUID().toString()
            Frames.sendJson(output, JSONObject().apply {
                put("type", "DETECT_MODELS")
                put("requestId", requestId)
                put("modelApiUrl", modelApiUrl)
                put("modelApiKey", modelApiKey)
            })

            val response = Frames.readJson(input)
            when (response.optString("type")) {
                "MODEL_NAMES" -> {
                    if (response.optString("requestId") != requestId) {
                        throw IllegalStateException("requestId mismatch")
                    }
                    val arr = response.optJSONArray("models")
                        ?: throw IllegalStateException("invalid models payload")
                    val result = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optString(i)
                        if (item.isNotBlank()) {
                            result.add(item)
                        }
                    }
                    result
                }

                "ERROR" -> throw IllegalStateException(response.optString("message", "server error"))
                else -> throw IllegalStateException("unexpected response")
            }
        }
    }

    fun addModelSetting(
        modelApiUrl: String,
        modelApiKey: String,
        modelName: String,
        setActive: Boolean = true,
    ): ModelSettingsResult {
        return withAuthedSocket(DEFAULT_READ_TIMEOUT_MS) { input, output ->
            val requestId = UUID.randomUUID().toString()
            Frames.sendJson(output, JSONObject().apply {
                put("type", "ADD_MODEL_SETTING")
                put("requestId", requestId)
                put("modelApiUrl", modelApiUrl)
                put("modelApiKey", modelApiKey)
                put("modelName", modelName)
                put("setActive", setActive)
            })

            val response = Frames.readJson(input)
            if (response.optString("requestId") != requestId) {
                throw IllegalStateException("requestId mismatch")
            }
            parseModelSettingsResponse(response)
        }
    }

    fun setActiveModel(index: Int): ModelSettingsResult {
        return withAuthedSocket(DEFAULT_READ_TIMEOUT_MS) { input, output ->
            val requestId = UUID.randomUUID().toString()
            Frames.sendJson(output, JSONObject().apply {
                put("type", "SET_ACTIVE_MODEL")
                put("requestId", requestId)
                put("index", index)
            })

            val response = Frames.readJson(input)
            if (response.optString("requestId") != requestId) {
                throw IllegalStateException("requestId mismatch")
            }
            parseModelSettingsResponse(response)
        }
    }

    fun deleteModelSetting(index: Int): ModelSettingsResult {
        return withAuthedSocket(DEFAULT_READ_TIMEOUT_MS) { input, output ->
            val requestId = UUID.randomUUID().toString()
            Frames.sendJson(output, JSONObject().apply {
                put("type", "DELETE_MODEL_SETTING")
                put("requestId", requestId)
                put("index", index)
            })

            val response = Frames.readJson(input)
            if (response.optString("requestId") != requestId) {
                throw IllegalStateException("requestId mismatch")
            }
            parseModelSettingsResponse(response)
        }
    }

    private fun parseModelSettingsResponse(response: JSONObject): ModelSettingsResult {
        if (response.optString("type") == "ERROR") {
            throw IllegalStateException(response.optString("message", "server error"))
        }
        if (response.optString("type") != "MODEL_SETTINGS") {
            throw IllegalStateException("unexpected response")
        }

        val arr = response.optJSONArray("profiles")
            ?: throw IllegalStateException("invalid model settings payload")
        val profiles = mutableListOf<ModelSetting>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            profiles.add(
                ModelSetting(
                    apiUrl = obj.optString("apiUrl", ""),
                    apiKey = obj.optString("apiKey", ""),
                    modelName = obj.optString("modelName", ""),
                )
            )
        }

        return ModelSettingsResult(
            profiles = profiles,
            activeIndex = response.optInt("activeIndex", 0),
        )
    }

    private inline fun <T> withAuthedSocket(
        readTimeoutMs: Int,
        block: (DataInputStream, DataOutputStream) -> T,
    ): T {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = readTimeoutMs

            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            Frames.sendJson(output, JSONObject().apply {
                put("type", "AUTH")
                put("password", password)
            })

            val authReply = Frames.readJson(input)
            if (authReply.optString("type") != "AUTH_OK") {
                throw IllegalStateException(authReply.optString("message", "auth failed"))
            }

            return block(input, output)
        }
    }
}
