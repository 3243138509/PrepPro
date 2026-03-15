package com.PrepPro.mobile.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class LlamaClassifier(private val context: Context) {
    companion object {
        const val ROUTE_QA = "qa"
        const val ROUTE_CODE = "code"
    }

    private val native = LlamaClassifierNative()
    private val modelManager = LlamaModelManager(context)
    private val loaded = AtomicBoolean(false)

    suspend fun classify(problemText: String): String = withContext(Dispatchers.Default) {
        val normalized = normalizeRoute(runCatching { classifyInternal(problemText) }.getOrDefault(ROUTE_QA))
        normalized
    }

    suspend fun tryDownloadEnhancedModel(): Boolean = withContext(Dispatchers.IO) {
        modelManager.downloadEnhancedModelIfConfigured()
    }

    fun release() {
        if (loaded.compareAndSet(true, false)) {
            runCatching { native.nativeUnloadModel() }
        }
    }

    private fun classifyInternal(problemText: String): String {
        ensureModelLoaded()
        return native.nativeClassifyRoute(buildClassifierPrompt(problemText))
    }

    private fun ensureModelLoaded() {
        if (loaded.get() && native.nativeIsModelLoaded()) {
            return
        }
        val modelPath = modelManager.resolveActiveModelPath()
            ?: throw IllegalStateException("llama model file unavailable")
        val ok = native.nativeLoadModel(modelPath)
        if (!ok) {
            throw IllegalStateException("failed to load llama model")
        }
        loaded.set(true)
    }

    private fun buildClassifierPrompt(problemText: String): String {
        return """
            你是题目分类器。请判断题目更适合问答还是代码实现。
            只允许输出 qa 或 code，不能输出其他文本。

            题目内容：
            $problemText
        """.trimIndent()
    }

    private fun normalizeRoute(raw: String): String {
        val text = raw.trim().lowercase()
        return if (text.contains(ROUTE_CODE)) ROUTE_CODE else ROUTE_QA
    }
}

