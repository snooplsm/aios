package com.aios.phone.telecom

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.aios.phone.model.VoicemailPlaybackState

/** Streams a provider URI directly; voicemail audio is never copied into AIOS storage. */
class VoicemailPlaybackController(
    private val context: Context,
    private val onState: (String?, VoicemailPlaybackState) -> Unit,
    private val onError: () -> Unit,
) {
    private var player: MediaPlayer? = null
    private var currentId: String? = null
    private var state = VoicemailPlaybackState.STOPPED

    fun toggle(voicemailId: String, uri: Uri) {
        val existing = player
        if (currentId == voicemailId && existing != null) {
            when (state) {
                VoicemailPlaybackState.PLAYING -> {
                    existing.pause()
                    update(voicemailId, VoicemailPlaybackState.PAUSED)
                }
                VoicemailPlaybackState.PAUSED -> {
                    existing.start()
                    update(voicemailId, VoicemailPlaybackState.PLAYING)
                }
                else -> Unit
            }
            return
        }
        release()
        currentId = voicemailId
        update(voicemailId, VoicemailPlaybackState.PREPARING)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(context, uri)
                setOnPreparedListener {
                    it.start()
                    update(voicemailId, VoicemailPlaybackState.PLAYING)
                }
                setOnCompletionListener { release() }
                setOnErrorListener { _, _, _ ->
                    release()
                    onError()
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            release()
            onError()
        }
    }

    fun stop() = release()

    private fun update(id: String?, value: VoicemailPlaybackState) {
        currentId = id
        state = value
        onState(id, value)
    }

    private fun release() {
        player?.runCatching { stop() }
        player?.release()
        player = null
        update(null, VoicemailPlaybackState.STOPPED)
    }
}
