#include <jni.h>
#include <string>

#include "../third_party/llama.cpp/llama_bridge.h"

namespace {

jstring to_jstring(JNIEnv* env, const std::string& text) {
    return env->NewStringUTF(text.c_str());
}

std::string to_std_string(JNIEnv* env, jstring text) {
    if (text == nullptr) {
        return "";
    }
    const char* raw = env->GetStringUTFChars(text, nullptr);
    if (raw == nullptr) {
        return "";
    }
    std::string out(raw);
    env->ReleaseStringUTFChars(text, raw);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_PrepPro_mobile_local_LlamaClassifierNative_nativeLoadModel(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath) {
    std::string err;
    const bool ok = preppro::load_model(to_std_string(env, modelPath), err);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_PrepPro_mobile_local_LlamaClassifierNative_nativeUnloadModel(
    JNIEnv* /* env */,
    jobject /* this */) {
    preppro::unload_model();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_PrepPro_mobile_local_LlamaClassifierNative_nativeIsModelLoaded(
    JNIEnv* /* env */,
    jobject /* this */) {
    return preppro::is_model_loaded() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_PrepPro_mobile_local_LlamaClassifierNative_nativeClassifyRoute(
    JNIEnv* env,
    jobject /* this */,
    jstring prompt) {
    std::string err;
    const std::string route = preppro::classify_route(to_std_string(env, prompt), err);
    return to_jstring(env, route);
}

