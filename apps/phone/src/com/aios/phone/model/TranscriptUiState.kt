package com.aios.phone.model

/** Immutable, Android-free live-transcript row consumed by Phone's UDF state. */
data class TranscriptUiState(
    val direction: String,
    val language: String,
    val text: String,
    val isFinal: Boolean,
    val startMillis: Long,
)
