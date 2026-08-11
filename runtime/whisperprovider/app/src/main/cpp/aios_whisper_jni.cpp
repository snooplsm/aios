#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <new>
#include <string>

#include "whisper.h"

namespace {

void throw_java(JNIEnv * env, const char * type, const char * message) {
    jclass klass = env->FindClass(type);
    if (klass != nullptr) {
        env->ThrowNew(klass, message);
    }
}

whisper_context * checked_context(JNIEnv * env, jlong value) {
    auto * context = reinterpret_cast<whisper_context *>(value);
    if (context == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "native context is absent");
    }
    return context;
}

struct decode_cancellation {
    std::atomic<bool> cancelled{false};
};

decode_cancellation * checked_cancellation(JNIEnv * env, jlong value) {
    auto * cancellation = reinterpret_cast<decode_cancellation *>(value);
    if (cancellation == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "decode cancellation is absent");
    }
    return cancellation;
}

bool abort_decode(void * user_data) {
    auto * cancellation = static_cast<decode_cancellation *>(user_data);
    return cancellation != nullptr
            && cancellation->cancelled.load(std::memory_order_acquire);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_aios_runtime_whispercpp_NativeWhisper_create(
        JNIEnv * env, jobject, jstring model_path) {
    if (model_path == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "model path is absent");
        return 0;
    }
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) {
        return 0;
    }
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = true;
    whisper_context * context = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    if (context == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "whisper model initialization failed");
        return 0;
    }
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aios_runtime_whispercpp_NativeWhisper_createCancellation(
        JNIEnv * env, jobject) {
    auto * cancellation = new (std::nothrow) decode_cancellation();
    if (cancellation == nullptr) {
        throw_java(env, "java/lang/OutOfMemoryError", "cannot allocate decode cancellation");
        return 0;
    }
    return reinterpret_cast<jlong>(cancellation);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aios_runtime_whispercpp_NativeWhisper_cancel(
        JNIEnv * env, jobject, jlong cancellation_value) {
    decode_cancellation * cancellation = checked_cancellation(env, cancellation_value);
    if (cancellation != nullptr) {
        cancellation->cancelled.store(true, std::memory_order_release);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aios_runtime_whispercpp_NativeWhisper_destroyCancellation(
        JNIEnv * env, jobject, jlong cancellation_value) {
    decode_cancellation * cancellation = checked_cancellation(env, cancellation_value);
    if (cancellation != nullptr) delete cancellation;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_aios_runtime_whispercpp_NativeWhisper_transcribe(
        JNIEnv * env,
        jobject,
        jlong context_value,
        jfloatArray samples,
        jstring language,
        jint thread_count,
        jlong cancellation_value) {
    whisper_context * context = checked_context(env, context_value);
    decode_cancellation * cancellation =
            checked_cancellation(env, cancellation_value);
    if (context == nullptr || cancellation == nullptr
            || samples == nullptr || language == nullptr) {
        if (!env->ExceptionCheck()) {
            throw_java(env, "java/lang/IllegalArgumentException", "transcription input is absent");
        }
        return nullptr;
    }
    const jsize sample_count = env->GetArrayLength(samples);
    if (sample_count <= 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "audio window is empty");
        return nullptr;
    }
    jfloat * pcm = env->GetFloatArrayElements(samples, nullptr);
    const char * language_chars = env->GetStringUTFChars(language, nullptr);
    if (pcm == nullptr || language_chars == nullptr) {
        if (pcm != nullptr) env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
        if (language_chars != nullptr) env->ReleaseStringUTFChars(language, language_chars);
        return nullptr;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, std::min(static_cast<int>(thread_count), 8));
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.language = language_chars;
    // The "auto" language value already selects a language before decoding.
    // detect_language=true is whisper.cpp's detect-only mode and returns before
    // transcription, which would yield language metadata with no text.
    params.detect_language = false;
    params.abort_callback = abort_decode;
    params.abort_callback_user_data = cancellation;

    const int status = whisper_full(context, params, pcm, sample_count);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    env->ReleaseStringUTFChars(language, language_chars);
    if (status != 0) {
        if (cancellation->cancelled.load(std::memory_order_acquire)) return nullptr;
        throw_java(env, "java/lang/IllegalStateException", "whisper decode failed");
        return nullptr;
    }

    std::string transcript;
    const int segment_count = whisper_full_n_segments(context);
    for (int index = 0; index < segment_count; ++index) {
        const char * text = whisper_full_get_segment_text(context, index);
        if (text != nullptr) transcript.append(text);
    }
    const int language_id = whisper_full_lang_id(context);
    const char * detected = language_id >= 0 ? whisper_lang_str(language_id) : nullptr;
    if (detected == nullptr) detected = "und";

    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(2, string_class, nullptr);
    env->SetObjectArrayElement(result, 0, env->NewStringUTF(detected));
    env->SetObjectArrayElement(result, 1, env->NewStringUTF(transcript.c_str()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aios_runtime_whispercpp_NativeWhisper_destroy(
        JNIEnv * env, jobject, jlong context_value) {
    whisper_context * context = checked_context(env, context_value);
    if (context != nullptr) whisper_free(context);
}
