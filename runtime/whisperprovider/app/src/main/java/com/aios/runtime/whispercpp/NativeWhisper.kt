package com.aios.runtime.whispercpp

/** Minimal JNI surface; model and session policy stay in the provider service. */
internal object NativeWhisper {
    init {
        System.loadLibrary("aios_whisper_jni")
    }

    external fun create(modelPath: String): Long

    /** Returns [detectedLanguage, transcript]. */
    external fun transcribe(
        context: Long,
        samples: FloatArray,
        language: String,
        threadCount: Int,
    ): Array<String>

    external fun destroy(context: Long)
}
