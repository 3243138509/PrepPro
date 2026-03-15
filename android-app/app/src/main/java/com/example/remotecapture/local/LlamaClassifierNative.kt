package com.PrepPro.mobile.local

internal class LlamaClassifierNative {
    external fun nativeLoadModel(modelPath: String): Boolean
    external fun nativeUnloadModel()
    external fun nativeIsModelLoaded(): Boolean
    external fun nativeClassifyRoute(prompt: String): String

    companion object {
        init {
            System.loadLibrary("llama_classifier")
        }
    }
}

