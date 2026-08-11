package com.aios.phone.notifications

/** Android-free presentation policy for bounded, private live-call updates. */
object CallNotificationSemantics {
    const val MAX_CONTENT_CHARS = 160
    private const val CALLER_PREFIX = "Caller: "

    data class Presentation(
        val contentText: String,
        val subText: String?,
        val containsPrivateTranscript: Boolean,
    )

    fun present(
        ringing: Boolean,
        aiHandling: Boolean,
        riskHeadline: String?,
        latestIncomingTranscript: String?,
    ): Presentation {
        if (ringing) return Presentation("Incoming call", null, false)

        val status = when {
            aiHandling && !riskHeadline.isNullOrBlank() ->
                "AI receptionist \u00b7 ${riskHeadline.trim()}"
            aiHandling -> "AI receptionist is handling this call"
            !riskHeadline.isNullOrBlank() -> "Ongoing call \u00b7 ${riskHeadline.trim()}"
            else -> "Ongoing call"
        }
        val callerText = normalize(
            latestIncomingTranscript,
            MAX_CONTENT_CHARS - CALLER_PREFIX.length,
        ) ?: return Presentation(status.take(MAX_CONTENT_CHARS), null, false)
        return Presentation(
            contentText = CALLER_PREFIX + callerText,
            subText = status.take(MAX_CONTENT_CHARS),
            containsPrivateTranscript = true,
        )
    }

    private fun normalize(value: String?, maximumChars: Int): String? {
        if (value == null || maximumChars <= 0) return null
        val output = StringBuilder(maximumChars)
        var offset = 0
        var pendingSpace = false
        while (offset < value.length) {
            val codePoint = Character.codePointAt(value, offset)
            offset += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint) ||
                Character.getType(codePoint) == Character.FORMAT.toInt()
            ) {
                pendingSpace = output.isNotEmpty()
                continue
            }
            val codePointChars = Character.charCount(codePoint)
            val separatorChars = if (pendingSpace && output.isNotEmpty()) 1 else 0
            if (output.length + separatorChars + codePointChars > maximumChars) break
            if (separatorChars == 1) output.append(' ')
            output.appendCodePoint(codePoint)
            pendingSpace = false
        }
        return output.toString().takeIf(String::isNotBlank)
    }
}
