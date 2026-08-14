package com.aios.runtime.sherpatts

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.aios.model.AudioStreamFormat
import com.aios.model.IModelCallback
import com.aios.model.InferenceResult
import com.aios.model.ModelRequest
import com.aios.runtime.IAiosRuntimeProvider
import com.aios.runtime.RuntimeArtifact
import com.aios.runtime.common.RuntimeMemoryTrimPolicy
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** Digest-locked, CPU-only Supertonic 3 provider for English/Spanish call speech. */
class SherpaTtsRuntimeService : Service() {
    private companion object {
        const val TAG = "AiosTtsRuntime"
        const val BROKER_PACKAGE = "com.aios.modelbroker"
        const val RUNTIME_ID = "sherpa_onnx_tts"
        const val IMPLEMENTATION_VERSION = "1.13.7"
        const val PROVIDER_API_VERSION = 2
        const val MODEL_ID = "supertonic3-en-es-int8"
        const val SOURCE_ARCHIVE_SHA256 =
            "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"
        const val ERROR_INVALID_REQUEST = 2
        const val ERROR_BUSY = 3
        const val ERROR_RUNTIME_FAILED = 5
        const val SAMPLE_RATE_HZ = 44_100
        const val PCM_ENCODING_16_BIT = 2
        const val MAX_TEXT_CHARS = 2_048
        const val MAX_DESCRIPTOR_BYTES = 1024 * 1024L
        const val HASH_BUFFER_BYTES = 1024 * 1024
        const val PCM_BLOCK_SAMPLES = 4_096
        const val SPEAKER_ID = 0
        // The call path favors response latency without dropping below the
        // pinned Sherpa integration's default Supertonic denoising depth.
        const val CALL_NUM_STEPS = 5
        // Keep the first native callback short enough to begin call playback
        // before a complete multi-sentence response has been synthesized.
        const val CALL_MAX_CHUNK_CODEPOINTS = 64
        val CONFIGURATION_DIRECTORY = File("/product/etc/aios")
        val MODEL_DIRECTORY = File(CONFIGURATION_DIRECTORY, "models")
        const val EMULATOR_FIXTURE_DIRECTORY = "emulator-config"

        data class ExpectedMember(val sizeBytes: Long, val sha256: String)

        val EXPECTED_MEMBERS = mapOf(
            "duration_predictor.int8.onnx" to ExpectedMember(
                3_700_147L,
                "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db",
            ),
            "text_encoder.int8.onnx" to ExpectedMember(
                36_416_150L,
                "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff",
            ),
            "vector_estimator.int8.onnx" to ExpectedMember(
                78_400_833L,
                "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd",
            ),
            "vocoder.int8.onnx" to ExpectedMember(
                25_991_073L,
                "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152",
            ),
            "tts.json" to ExpectedMember(
                8_253L,
                "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09",
            ),
            "unicode_indexer.bin" to ExpectedMember(
                262_144L,
                "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30",
            ),
            "voice.bin" to ExpectedMember(
                517_168L,
                "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8",
            ),
            "LICENSE" to ExpectedMember(
                1_070L,
                "0dfe0d0ba84416fe3879d9a34f4909d8d0137c78d1e95834177b0414ac096fa2",
            ),
        )
    }

    private data class BundleFiles(
        val durationPredictor: File,
        val textEncoder: File,
        val vectorEstimator: File,
        val vocoder: File,
        val ttsJson: File,
        val unicodeIndexer: File,
        val voiceStyle: File,
    )

    private data class EngineIdentity(val descriptorPath: String, val descriptorDigest: String)
    private data class EngineHolder(val identity: EngineIdentity, val tts: OfflineTts)

    private class TtsSession(
        val id: Long,
        val artifact: RuntimeArtifact,
        val request: ModelRequest,
        val callback: IModelCallback,
        val createdAtElapsed: Long,
    ) {
        val cancelled = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val outputAttached = AtomicBoolean(false)
        val textSubmitted = AtomicBoolean(false)
        lateinit var deathRecipient: IBinder.DeathRecipient
        @Volatile var sink: ParcelFileDescriptor? = null
    }

    private val nextSessionId = AtomicLong(1L)
    private val sessions = ConcurrentHashMap<Long, TtsSession>()
    private val runtimeExecutor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "aios-sherpa-tts").apply { priority = Thread.NORM_PRIORITY }
    }
    private val engineLock = Any()
    @Volatile private var engineHolder: EngineHolder? = null
    @Volatile private var stopping = false

    private val binder = object : IAiosRuntimeProvider.Stub() {
        override fun getProviderApiVersion(): Int {
            enforceBrokerCaller()
            return PROVIDER_API_VERSION
        }

        override fun getRuntimeId(): String {
            enforceBrokerCaller()
            return RUNTIME_ID
        }

        override fun getImplementationVersion(): String {
            enforceBrokerCaller()
            return IMPLEMENTATION_VERSION
        }

        override fun getSupportedBackends(): Array<String> {
            enforceBrokerCaller()
            return arrayOf("cpu")
        }

        override fun createSession(
            artifact: RuntimeArtifact?,
            request: ModelRequest?,
            callback: IModelCallback?,
        ): Long {
            enforceBrokerCaller()
            if (stopping || artifact == null || request == null || callback == null
                || !validRequest(artifact, request)) {
                notifyError(callback, ERROR_INVALID_REQUEST, "invalid TTS runtime request")
                return -1L
            }
            val id = nextSessionId.getAndIncrement()
            val session = TtsSession(
                id, artifact, request, callback, SystemClock.elapsedRealtime())
            session.deathRecipient = IBinder.DeathRecipient { cancelInternal(id) }
            try {
                callback.asBinder().linkToDeath(session.deathRecipient, 0)
            } catch (_: RemoteException) {
                return -1L
            }
            sessions[id] = session
            Log.i(TAG, "SESSION_CREATED id=$id model=${artifact.modelId} " +
                "language=${request.language} backend=${artifact.backend}")
            return id
        }

        override fun submitText(sessionId: Long, text: String?, endOfInput: Boolean) {
            enforceBrokerCaller()
            val session = requireSession(sessionId)
            val outputReady = synchronized(session) { session.sink != null }
            if (!endOfInput || text == null || text.isBlank()
                || text.length > MAX_TEXT_CHARS || !outputReady
                || !session.textSubmitted.compareAndSet(false, true)) {
                fail(session, ERROR_INVALID_REQUEST,
                    "TTS requires one bounded final text after output attachment")
                return
            }
            Log.i(TAG, "TEXT_SUBMITTED id=$sessionId chars=${text.length}")
            runtimeExecutor.execute { synthesize(session, text) }
        }

        override fun submitAudio(
            sessionId: Long,
            pcmStream: ParcelFileDescriptor?,
            format: AudioStreamFormat?,
            endOfInput: Boolean,
        ) {
            enforceBrokerCaller()
            closeDescriptor(pcmStream)
            fail(requireSession(sessionId), ERROR_INVALID_REQUEST,
                "TTS provider accepts only text input")
        }

        override fun attachAudioOutput(
            sessionId: Long,
            pcmSink: ParcelFileDescriptor?,
            format: AudioStreamFormat?,
        ) {
            enforceBrokerCaller()
            val session = requireSession(sessionId)
            if (pcmSink == null || format == null
                || format.sampleRateHz != SAMPLE_RATE_HZ
                || format.channelCount != 1
                || format.pcmEncoding != PCM_ENCODING_16_BIT
                || format.direction != "synthesis"
                || !session.outputAttached.compareAndSet(false, true)) {
                closeDescriptor(pcmSink)
                fail(session, ERROR_INVALID_REQUEST,
                    "TTS requires one 44.1 kHz mono PCM16 output")
                return
            }
            val owned = try {
                ParcelFileDescriptor.dup(pcmSink.fileDescriptor)
            } catch (_: IOException) {
                null
            } finally {
                closeDescriptor(pcmSink)
            }
            if (owned == null) {
                fail(session, ERROR_INVALID_REQUEST, "cannot duplicate TTS output")
                return
            }
            synchronized(session) {
                if (session.cancelled.get() || session.completed.get()) {
                    closeDescriptor(owned)
                } else {
                    session.sink = owned
                }
            }
        }

        override fun submitMedia(
            sessionId: Long,
            media: ParcelFileDescriptor?,
            mimeType: String?,
            endOfInput: Boolean,
        ) {
            enforceBrokerCaller()
            closeDescriptor(media)
            fail(requireSession(sessionId), ERROR_INVALID_REQUEST,
                "TTS provider accepts only text input")
        }

        override fun cancel(sessionId: Long) {
            enforceBrokerCaller()
            cancelInternal(sessionId)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopping = true
        sessions.keys.toList().forEach(::cancelInternal)
        runtimeExecutor.shutdownNow()
        val terminated = try {
            runtimeExecutor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (terminated) closeEngine()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (RuntimeMemoryTrimPolicy.isMemoryPressure(level)) {
            runtimeExecutor.execute {
                if (sessions.isEmpty()) closeEngine()
            }
        }
    }

    private inner class PcmStreamingCallback(
        private val session: TtsSession,
        private val output: OutputStream,
    ) : Function1<FloatArray, Int> {
        var sampleCount = 0L
            private set
        var failure: IOException? = null
            private set
        private var firstBlockLogged = false
        private var chunkCount = 0

        override fun invoke(samples: FloatArray): Int {
            if (session.cancelled.get() || session.completed.get()
                || SystemClock.elapsedRealtime() >=
                    session.request.deadlineElapsedRealtimeMillis) {
                return 0
            }
            return try {
                writePcm16(output, samples)
                chunkCount += 1
                Log.i(TAG, "AUDIO_CHUNK id=${session.id} chunk=$chunkCount elapsed_ms=" +
                    (SystemClock.elapsedRealtime() - session.createdAtElapsed) +
                    " samples=${samples.size}")
                if (!firstBlockLogged) {
                    firstBlockLogged = true
                    Log.i(TAG, "FIRST_AUDIO id=${session.id} elapsed_ms=" +
                        (SystemClock.elapsedRealtime() - session.createdAtElapsed) +
                        " samples=${samples.size}")
                }
                sampleCount += samples.size
                1
            } catch (error: IOException) {
                failure = error
                0
            }
        }
    }

    private fun synthesize(session: TtsSession, text: String) {
        if (session.cancelled.get() || session.completed.get()) return
        Log.i(TAG, "SYNTHESIS_START id=${session.id}")
        val descriptor = synchronized(session) { session.sink }
        if (descriptor == null) {
            fail(session, ERROR_INVALID_REQUEST, "TTS output disappeared")
            return
        }
        var callbackFailure: IOException? = null
        var sampleCount = 0L
        try {
            val tts = ensureEngine(session.artifact)
            Log.i(TAG, "ENGINE_READY id=${session.id} elapsed_ms=" +
                (SystemClock.elapsedRealtime() - session.createdAtElapsed))
            check(tts.sampleRate() == SAMPLE_RATE_HZ) { "unexpected TTS sample rate" }
            check(tts.numSpeakers() > SPEAKER_ID) { "configured TTS speaker is absent" }
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                val config = GenerationConfig(
                    sid = SPEAKER_ID,
                    speed = 1.0f,
                    numSteps = CALL_NUM_STEPS,
                    extra = mapOf(
                        "lang" to session.request.language,
                        "max_len" to CALL_MAX_CHUNK_CODEPOINTS.toString(),
                    ),
                )
                val callback = PcmStreamingCallback(session, output)
                val audio = tts.generateWithConfigAndCallback(text, config, callback)
                sampleCount = callback.sampleCount
                callbackFailure = callback.failure
                check(audio.sampleRate == SAMPLE_RATE_HZ) { "generated TTS rate mismatch" }
            }
            synchronized(session) {
                if (session.sink === descriptor) session.sink = null
            }
            if (session.cancelled.get() || session.completed.get()) return
            callbackFailure?.let { throw it }
            check(SystemClock.elapsedRealtime() <
                session.request.deadlineElapsedRealtimeMillis) { "TTS deadline expired" }
            check(sampleCount > 0L) { "TTS produced no PCM" }
            complete(session, sampleCount)
        } catch (error: Exception) {
            Log.e(TAG, "SYNTHESIS_FAILED id=${session.id}", error)
            synchronized(session) {
                if (session.sink === descriptor) session.sink = null
            }
            closeDescriptor(descriptor)
            if (!session.cancelled.get()) {
                fail(session, ERROR_RUNTIME_FAILED,
                    (error::class.java.simpleName + ": TTS synthesis failed").take(256))
            }
        }
    }

    private fun ensureEngine(artifact: RuntimeArtifact): OfflineTts {
        val startedAt = SystemClock.elapsedRealtime()
        val descriptor = verifiedDescriptorFile(artifact)
        val identity = EngineIdentity(descriptor.absolutePath, artifact.modelDigest)
        synchronized(engineLock) {
            engineHolder?.let { holder ->
                if (holder.identity == identity) return holder.tts
            }
            check(sessions.values.none { session ->
                session.textSubmitted.get() && !session.completed.get()
                    && session.artifact.modelDigest != artifact.modelDigest
            }) { "cannot switch TTS bundles while synthesis is active" }
            closeEngineLocked()
            val files = verifiedBundleFiles(descriptor)
            Log.i(TAG, "ENGINE_INITIALIZE_START model=${artifact.modelId}")
            val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            Log.i(TAG, "ENGINE_CONFIGURATION model=${artifact.modelId} " +
                "threads=$threadCount call_num_steps=$CALL_NUM_STEPS " +
                "call_max_chunk_codepoints=$CALL_MAX_CHUNK_CODEPOINTS")
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = files.durationPredictor.absolutePath,
                        textEncoder = files.textEncoder.absolutePath,
                        vectorEstimator = files.vectorEstimator.absolutePath,
                        vocoder = files.vocoder.absolutePath,
                        ttsJson = files.ttsJson.absolutePath,
                        unicodeIndexer = files.unicodeIndexer.absolutePath,
                        voiceStyle = files.voiceStyle.absolutePath,
                    ),
                    numThreads = threadCount,
                    debug = false,
                    provider = "cpu",
                ),
            )
            val tts = OfflineTts(config = config)
            check(tts.sampleRate() == SAMPLE_RATE_HZ) { "bundle is not 44.1 kHz Supertonic" }
            Log.i(TAG, "ENGINE_INITIALIZE_DONE model=${artifact.modelId} elapsed_ms=" +
                (SystemClock.elapsedRealtime() - startedAt))
            return tts.also { engineHolder = EngineHolder(identity, it) }
        }
    }

    private fun verifiedDescriptorFile(artifact: RuntimeArtifact): File {
        val descriptor = File(artifact.modelPath).canonicalFile
        check(configurationDirectoryForDescriptor(descriptor) != null && descriptor.isFile) {
            "TTS descriptor is outside the read-only model directory"
        }
        check(artifact.sizeBytes in 1..MAX_DESCRIPTOR_BYTES
            && descriptor.length() == artifact.sizeBytes) { "TTS descriptor size mismatch" }
        check(MessageDigest.isEqual(
            artifact.modelDigest.toByteArray(StandardCharsets.US_ASCII),
            sha256(descriptor).toByteArray(StandardCharsets.US_ASCII),
        )) { "TTS descriptor digest mismatch" }
        return descriptor
    }

    private fun verifiedBundleFiles(descriptor: File): BundleFiles {
        val root = JSONObject(descriptor.readText(StandardCharsets.UTF_8))
        check(root.getInt("schema_version") == 1
            && root.getString("model_id") == MODEL_ID
            && root.getString("source_archive_sha256") == SOURCE_ARCHIVE_SHA256) {
            "TTS bundle identity mismatch"
        }
        val records = root.getJSONArray("members")
        check(records.length() == EXPECTED_MEMBERS.size) { "TTS bundle member count mismatch" }
        val found = mutableMapOf<String, File>()
        val seen = mutableSetOf<String>()
        val configuration = configurationDirectoryForDescriptor(descriptor)
            ?: error("TTS descriptor configuration disappeared")
        val modelDirectory = File(configuration, "models").canonicalFile
        val modelPrefix = modelDirectory.path + File.separator
        repeat(records.length()) { index ->
            val record = records.getJSONObject(index)
            val name = record.getString("name")
            val expected = EXPECTED_MEMBERS[name]
                ?: error("unexpected TTS bundle member")
            check(seen.add(name)) { "duplicate TTS bundle member" }
            check(record.getLong("size_bytes") == expected.sizeBytes
                && record.getString("sha256") == expected.sha256
                && record.getString("relative_path") == "models/$MODEL_ID/$name") {
                "TTS bundle member lock mismatch"
            }
            val file = File(configuration, record.getString("relative_path")).canonicalFile
            check(file.path.startsWith(modelPrefix) && file.isFile
                && file.length() == expected.sizeBytes
                && MessageDigest.isEqual(
                    expected.sha256.toByteArray(StandardCharsets.US_ASCII),
                    sha256(file).toByteArray(StandardCharsets.US_ASCII),
                )) { "TTS bundle member verification failed" }
            found[name] = file
        }
        check(found.keys == EXPECTED_MEMBERS.keys) { "TTS bundle is incomplete" }
        return BundleFiles(
            durationPredictor = found.getValue("duration_predictor.int8.onnx"),
            textEncoder = found.getValue("text_encoder.int8.onnx"),
            vectorEstimator = found.getValue("vector_estimator.int8.onnx"),
            vocoder = found.getValue("vocoder.int8.onnx"),
            ttsJson = found.getValue("tts.json"),
            unicodeIndexer = found.getValue("unicode_indexer.bin"),
            voiceStyle = found.getValue("voice.bin"),
        )
    }

    private fun configurationDirectoryForDescriptor(descriptor: File): File? =
        allowedConfigurationDirectories().firstOrNull { configuration ->
            val models = File(configuration, "models").canonicalFile
            descriptor.path.startsWith(models.path + File.separator)
        }

    private fun allowedConfigurationDirectories(): List<File> {
        val directories = mutableListOf(CONFIGURATION_DIRECTORY.canonicalFile)
        if (allowsEmulatorModelFixtures()) {
            directories += File(filesDir, EMULATOR_FIXTURE_DIRECTORY).canonicalFile
        }
        return directories
    }

    private fun allowsEmulatorModelFixtures(): Boolean =
        BuildConfig.ALLOW_EMULATOR_MODEL_FIXTURES && (
            Build.HARDWARE.equals("ranchu", ignoreCase = true)
                || Build.HARDWARE.equals("goldfish", ignoreCase = true)
                || Build.PRODUCT.contains("sdk", ignoreCase = true)
                || Build.FINGERPRINT.startsWith("generic")
        )

    private fun complete(session: TtsSession, sampleCount: Long) {
        if (!session.completed.compareAndSet(false, true)) return
        Log.i(TAG, "SYNTHESIS_DONE id=${session.id} samples=$sampleCount elapsed_ms=" +
            (SystemClock.elapsedRealtime() - session.createdAtElapsed))
        sessions.remove(session.id, session)
        session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
        val output = JSONObject()
            .put("schema_version", 1)
            .put("sample_rate_hz", SAMPLE_RATE_HZ)
            .put("sample_count", sampleCount)
            .put("speaker_id", SPEAKER_ID)
            .toString()
        val result = InferenceResult().apply {
            requestId = session.request.requestId
            capability = session.request.capability
            modelId = session.artifact.modelId
            modelDigest = session.artifact.modelDigest
            language = session.request.language
            outputJson = output
            elapsedMillis = SystemClock.elapsedRealtime() - session.createdAtElapsed
        }
        try {
            session.callback.onCompleted(result)
        } catch (_: RemoteException) {
            // Broker/client teardown already owns cleanup.
        }
    }

    private fun fail(session: TtsSession, code: Int, message: String) {
        if (!session.completed.compareAndSet(false, true)) return
        Log.e(TAG, "SESSION_FAILED id=${session.id} code=$code elapsed_ms=" +
            (SystemClock.elapsedRealtime() - session.createdAtElapsed) + " message=$message")
        sessions.remove(session.id, session)
        session.cancelled.set(true)
        session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
        val descriptor = synchronized(session) {
            val current = session.sink
            session.sink = null
            current
        }
        closeDescriptor(descriptor)
        notifyError(session.callback, code, message)
    }

    private fun cancelInternal(sessionId: Long) {
        val session = sessions.remove(sessionId) ?: return
        session.cancelled.set(true)
        session.completed.set(true)
        session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
        val descriptor = synchronized(session) {
            val current = session.sink
            session.sink = null
            current
        }
        closeDescriptor(descriptor)
    }

    private fun closeEngine() {
        synchronized(engineLock) { closeEngineLocked() }
    }

    private fun closeEngineLocked() {
        val holder = engineHolder ?: return
        engineHolder = null
        try {
            holder.tts.release()
        } catch (_: RuntimeException) {
            // Provider-process teardown remains the final native isolation boundary.
        }
    }

    private fun requireSession(sessionId: Long): TtsSession =
        sessions[sessionId] ?: throw SecurityException("TTS runtime session is absent")

    private fun enforceBrokerCaller() {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid())
        if (packages == null || packages.size != 1 || packages[0] != BROKER_PACKAGE) {
            throw SecurityException("only AIOS Model Broker may call the TTS runtime provider")
        }
    }

    private fun validRequest(artifact: RuntimeArtifact, request: ModelRequest): Boolean {
        return artifact.modelId == MODEL_ID
            && artifact.modelDigest.matches(Regex("[0-9a-f]{64}"))
            && artifact.sizeBytes in 1..MAX_DESCRIPTOR_BYTES
            && artifact.backend == "cpu"
            && request.requestId.isNotEmpty()
            && request.capability == "speech_synthesis"
            && request.workload == "call_agent"
            && request.language in setOf("en", "es")
            && request.maxOutputTokens == 0
            && request.deadlineElapsedRealtimeMillis > SystemClock.elapsedRealtime()
            && !request.allowFallback
    }

    private fun writePcm16(output: OutputStream, samples: FloatArray) {
        var offset = 0
        val bytes = ByteArray(minOf(samples.size, PCM_BLOCK_SAMPLES) * 2)
        while (offset < samples.size) {
            val count = minOf(PCM_BLOCK_SAMPLES, samples.size - offset)
            for (index in 0 until count) {
                val source = samples[offset + index]
                val finite = if (source.isFinite()) source.coerceIn(-1.0f, 1.0f) else 0.0f
                val pcm = (finite * 32767.0f).roundToInt().coerceIn(-32768, 32767)
                bytes[index * 2] = (pcm and 0xff).toByte()
                bytes[index * 2 + 1] = ((pcm ushr 8) and 0xff).toByte()
            }
            output.write(bytes, 0, count * 2)
            offset += count
        }
    }

    private fun sha256(path: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(path), HASH_BUFFER_BYTES).use { stream ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun notifyError(callback: IModelCallback?, code: Int, message: String) {
        if (callback == null) return
        try {
            callback.onError(code, message.take(256))
        } catch (_: RemoteException) {
            // Broker/client is already gone.
        }
    }

    private fun closeDescriptor(descriptor: ParcelFileDescriptor?) {
        try {
            descriptor?.close()
        } catch (_: IOException) {
            // Best effort during cancellation or failed output delivery.
        }
    }
}
