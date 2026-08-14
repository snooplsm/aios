package com.aios.runtime.whispercpp

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
import com.aios.model.GenerationChunk
import com.aios.model.IModelCallback
import com.aios.model.InferenceResult
import com.aios.model.ModelRequest
import com.aios.runtime.IAiosRuntimeProvider
import com.aios.runtime.RuntimeArtifact
import com.aios.runtime.common.RuntimeMemoryTrimPolicy
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** CPU whisper.cpp provider with incoming-call decode priority. */
class WhisperRuntimeService : Service() {
    private companion object {
        const val TAG = "AiosWhisperRuntime"
        const val BROKER_PACKAGE = "com.aios.modelbroker"
        const val RUNTIME_ID = "whisper_cpp"
        const val IMPLEMENTATION_VERSION = "1.9.6"
        const val PROVIDER_API_VERSION = 2
        const val ERROR_INVALID_REQUEST = 2
        const val ERROR_BUSY = 3
        const val ERROR_RUNTIME_FAILED = 5
        const val SAMPLE_RATE_HZ = 16_000
        const val VAD_FRAME_MILLIS = 100
        const val VAD_FRAME_SAMPLES = SAMPLE_RATE_HZ * VAD_FRAME_MILLIS / 1_000
        const val VAD_FRAME_BYTES = VAD_FRAME_SAMPLES * 2
        const val ENDPOINT_SILENCE_MILLIS = 600
        const val ENDPOINT_SILENCE_FRAMES = ENDPOINT_SILENCE_MILLIS / VAD_FRAME_MILLIS
        const val CALL_WINDOW_MILLIS = 2_000
        const val MEDIA_WINDOW_MILLIS = 4_000
        const val CALL_WINDOW_BYTES = SAMPLE_RATE_HZ * CALL_WINDOW_MILLIS / 1_000 * 2
        const val MEDIA_WINDOW_BYTES = SAMPLE_RATE_HZ * MEDIA_WINDOW_MILLIS / 1_000 * 2
        const val MIN_FINAL_SAMPLES = SAMPLE_RATE_HZ / 2
        const val MAX_PENDING_WINDOWS = 4
        const val MAX_SESSIONS = 2
        const val HASH_BUFFER_BYTES = 1024 * 1024
        const val THREAD_COUNT = 4
        const val MIN_RMS = 0.005f
        val MODEL_DIRECTORY = File("/product/etc/aios/models")
        const val EMULATOR_FIXTURE_DIRECTORY = "emulator-models"
    }

    private data class ModelIdentity(val path: String, val digest: String)
    private data class ModelHolder(val identity: ModelIdentity, val nativeContext: Long)

    private class AsrSession(
        val id: Long,
        val artifact: RuntimeArtifact,
        val request: ModelRequest,
        val callback: IModelCallback,
        val createdAtElapsed: Long,
    ) {
        val cancelled = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val inputStarted = AtomicBoolean(false)
        val pendingWindows = AtomicInteger(0)
        val sequence = AtomicLong(0L)
        val decodedWindows = AtomicInteger(0)
        val englishWindows = AtomicInteger(0)
        val spanishWindows = AtomicInteger(0)
        val turn = StreamingAsrTurnAccumulator()
        val decodeCancellation = DecodeCancellationFence()
        lateinit var deathRecipient: IBinder.DeathRecipient
        @Volatile var input: ParcelFileDescriptor? = null
        @Volatile var reader: Thread? = null

        val priority: Int
            get() = when (request.workload) {
                "call_rx" -> 3
                "call_tx" -> 2
                else -> 0
            }

        val isMedia: Boolean
            get() = request.workload == "media_background"
    }

    private class DecodeWindow(
        val session: AsrSession,
        val samples: FloatArray?,
        val startMillis: Long,
        val endMillis: Long,
        val endOfTurn: Boolean,
        val endOfStream: Boolean,
        val order: Long,
    ) : Comparable<DecodeWindow> {
        override fun compareTo(other: DecodeWindow): Int {
            val priorityOrder = other.session.priority.compareTo(session.priority)
            return if (priorityOrder != 0) priorityOrder else order.compareTo(other.order)
        }
    }

    private val nextSessionId = AtomicLong(1L)
    private val nextWindowOrder = AtomicLong(1L)
    private val sessions = ConcurrentHashMap<Long, AsrSession>()
    private val decodeQueue = PriorityBlockingQueue<DecodeWindow>()
    private val modelLock = Any()
    @Volatile private var currentModel: ModelHolder? = null
    @Volatile private var stopping = false
    private lateinit var decodeThread: Thread
    private val nativeDecodeSignal = object : DecodeCancellationFence.NativeSignal {
        override fun cancel(token: Long) = NativeWhisper.cancel(token)
        override fun destroy(token: Long) = NativeWhisper.destroyCancellation(token)
    }

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
                notifyError(callback, ERROR_INVALID_REQUEST, "invalid ASR runtime request")
                return -1L
            }
            synchronized(sessions) {
                if (sessions.size >= MAX_SESSIONS) {
                    notifyError(callback, ERROR_BUSY, "ASR runtime capacity reached")
                    return -1L
                }
                val id = nextSessionId.getAndIncrement()
                val session = AsrSession(
                    id, artifact, request, callback, SystemClock.elapsedRealtime())
                session.deathRecipient = IBinder.DeathRecipient { cancelInternal(id) }
                try {
                    callback.asBinder().linkToDeath(session.deathRecipient, 0)
                } catch (_: RemoteException) {
                    return -1L
                }
                sessions[id] = session
                Log.i(TAG, "SESSION_CREATED id=$id model=${artifact.modelId} " +
                    "workload=${request.workload} backend=${artifact.backend}")
                return id
            }
        }

        override fun submitText(sessionId: Long, text: String?, endOfInput: Boolean) {
            enforceBrokerCaller()
            fail(requireSession(sessionId), ERROR_INVALID_REQUEST,
                 "whisper.cpp provider accepts only PCM audio")
        }

        override fun submitAudio(
            sessionId: Long,
            pcmStream: ParcelFileDescriptor?,
            format: AudioStreamFormat?,
            endOfInput: Boolean,
        ) {
            enforceBrokerCaller()
            val session = requireSession(sessionId)
            if (pcmStream == null || format == null || endOfInput
                || format.sampleRateHz != SAMPLE_RATE_HZ
                || format.channelCount != 1 || format.pcmEncoding != 2
                || !directionMatches(session.request.workload, format.direction)
                || !session.inputStarted.compareAndSet(false, true)) {
                closeDescriptor(pcmStream)
                fail(session, ERROR_INVALID_REQUEST, "invalid or duplicate PCM stream")
                return
            }
            val owned = try {
                ParcelFileDescriptor.dup(pcmStream.fileDescriptor)
            } catch (_: IOException) {
                null
            } finally {
                closeDescriptor(pcmStream)
            }
            if (owned == null) {
                fail(session, ERROR_INVALID_REQUEST, "cannot duplicate PCM stream")
                return
            }
            session.input = owned
            Log.i(TAG, "AUDIO_SUBMITTED id=$sessionId sample_rate=${format.sampleRateHz} " +
                "direction=${format.direction}")
            session.reader = thread(
                start = true,
                isDaemon = true,
                name = "aios-whisper-reader-${session.id}",
            ) { readPcm(session, owned) }
        }

        override fun attachAudioOutput(
            sessionId: Long,
            pcmSink: ParcelFileDescriptor?,
            format: AudioStreamFormat?,
        ) {
            enforceBrokerCaller()
            closeDescriptor(pcmSink)
            fail(requireSession(sessionId), ERROR_INVALID_REQUEST,
                "whisper.cpp provider does not implement speech synthesis")
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
                 "whisper.cpp provider accepts only streaming PCM")
        }

        override fun cancel(sessionId: Long) {
            enforceBrokerCaller()
            cancelInternal(sessionId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        decodeThread = thread(start = true, name = "aios-whisper-decode") {
            decodeLoop()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopping = true
        sessions.keys.toList().forEach(::cancelInternal)
        decodeThread.interrupt()
        try {
            decodeThread.join(5_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!decodeThread.isAlive) {
            closeModel()
        }
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (RuntimeMemoryTrimPolicy.isMemoryPressure(level)) {
            synchronized(modelLock) {
                if (sessions.isEmpty() && decodeQueue.isEmpty()) closeModelLocked()
            }
        }
    }

    private fun readPcm(session: AsrSession, descriptor: ParcelFileDescriptor) {
        Log.i(TAG, "PCM_READ_START id=${session.id}")
        val frame = ByteArray(VAD_FRAME_BYTES)
        // Calls publish a replaceable partial about every few spoken words. Offline
        // media keeps larger windows to favor throughput and stable subtitle cues.
        val window = ByteArray(
            if (session.isMedia) MEDIA_WINDOW_BYTES else CALL_WINDOW_BYTES
        )
        var frameFilled = 0
        var windowFilled = 0
        var sampleOffset = 0L
        var windowStartSamples = 0L
        var windowHasSpeech = false
        var turnHasQueuedWindow = false
        val vad = StreamingVadState(ENDPOINT_SILENCE_FRAMES)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                while (!session.cancelled.get() && !session.completed.get()) {
                    val read = input.read(frame, frameFilled, frame.size - frameFilled)
                    if (read < 0) break
                    if (read == 0) continue
                    frameFilled += read
                    if (frameFilled == frame.size) {
                        val speechFrame = Pcm16EnergyVad.hasSpeech(
                            frame, frameFilled, MIN_RMS
                        )
                        val vadEvent = vad.accept(speechFrame)
                        if (vadEvent == StreamingVadState.Event.STARTED) {
                            windowStartSamples = sampleOffset
                        }
                        if (vadEvent != StreamingVadState.Event.IGNORED) {
                            if (speechFrame) {
                                windowHasSpeech = true
                            }
                            System.arraycopy(frame, 0, window, windowFilled, frameFilled)
                            windowFilled += frameFilled
                            if (windowFilled == window.size) {
                                if (windowHasSpeech) {
                                    enqueueWindow(
                                        session,
                                        pcm16ToFloat(window, windowFilled),
                                        windowStartSamples,
                                        // Offline video windows are independent final subtitle
                                        // segments. This bounds accumulated text and replay size.
                                        endOfTurn = session.isMedia,
                                    )
                                    turnHasQueuedWindow = !session.isMedia
                                }
                                windowFilled = 0
                                windowHasSpeech = false
                                windowStartSamples = sampleOffset + VAD_FRAME_SAMPLES
                            }
                            if (vadEvent == StreamingVadState.Event.ENDED) {
                                finishTurn(
                                    session,
                                    window,
                                    windowFilled,
                                    windowStartSamples,
                                    windowHasSpeech,
                                    turnHasQueuedWindow,
                                    sampleOffset + VAD_FRAME_SAMPLES,
                                )
                                windowFilled = 0
                                windowHasSpeech = false
                                turnHasQueuedWindow = false
                            }
                        }
                        sampleOffset += VAD_FRAME_SAMPLES
                        frameFilled = 0
                    }
                }
                if (!session.cancelled.get() && !session.completed.get() && frameFilled >= 2) {
                    val evenBytes = frameFilled - (frameFilled % 2)
                    val speechFrame = Pcm16EnergyVad.hasSpeech(frame, evenBytes, MIN_RMS)
                    if (speechFrame) {
                        val vadEvent = vad.accept(true)
                        if (vadEvent == StreamingVadState.Event.STARTED) {
                            windowStartSamples = sampleOffset
                        }
                    }
                    if (vad.isActive) {
                        if (speechFrame) windowHasSpeech = true
                        System.arraycopy(frame, 0, window, windowFilled, evenBytes)
                        windowFilled += evenBytes
                        sampleOffset += evenBytes / 2
                    }
                }
                if (!session.cancelled.get() && !session.completed.get() && vad.isActive) {
                    finishTurn(
                        session,
                        window,
                        windowFilled,
                        windowStartSamples,
                        windowHasSpeech,
                        turnHasQueuedWindow,
                        sampleOffset,
                    )
                }
                if (!session.cancelled.get() && !session.completed.get()) {
                    enqueue(
                        session,
                        samples = null,
                        startSamples = sampleOffset,
                        endSamples = sampleOffset,
                        endOfTurn = false,
                        endOfStream = true,
                    )
                }
                Log.i(TAG, "PCM_READ_DONE id=${session.id} samples=$sampleOffset")
            }
        } catch (error: IOException) {
            Log.e(TAG, "PCM_READ_FAILED id=${session.id}", error)
            if (!session.cancelled.get()) {
                fail(session, ERROR_RUNTIME_FAILED, "PCM stream failed")
            }
        } finally {
            session.input = null
        }
    }

    private fun finishTurn(
        session: AsrSession,
        window: ByteArray,
        byteCount: Int,
        startSamples: Long,
        hasSpeech: Boolean,
        hasQueuedWindow: Boolean,
        endSamples: Long,
    ) {
        if (hasSpeech && byteCount / 2 >= MIN_FINAL_SAMPLES) {
            enqueueWindow(
                session,
                pcm16ToFloat(window, byteCount),
                startSamples,
                endOfTurn = true,
            )
        } else if (hasQueuedWindow) {
            enqueue(
                session,
                samples = null,
                startSamples = endSamples,
                endSamples = endSamples,
                endOfTurn = true,
                endOfStream = false,
            )
        }
    }

    private fun enqueueWindow(
        session: AsrSession,
        samples: FloatArray,
        offset: Long,
        endOfTurn: Boolean,
    ) {
        val end = offset + samples.size
        enqueue(session, samples, offset, end, endOfTurn, false)
    }

    private fun enqueue(
        session: AsrSession,
        samples: FloatArray?,
        startSamples: Long,
        endSamples: Long,
        endOfTurn: Boolean,
        endOfStream: Boolean,
    ) {
        if (session.isMedia) {
            while (session.pendingWindows.get() >= MAX_PENDING_WINDOWS
                && !session.cancelled.get() && !session.completed.get() && !stopping) {
                try {
                    Thread.sleep(10L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    cancelInternal(session.id)
                    return
                }
            }
            if (session.cancelled.get() || session.completed.get() || stopping) return
        }
        if (session.pendingWindows.incrementAndGet() > MAX_PENDING_WINDOWS) {
            session.pendingWindows.decrementAndGet()
            fail(session, ERROR_BUSY, if (session.isMedia) {
                "offline ASR queue admission failed"
            } else {
                "ASR fell behind real time"
            })
            return
        }
        decodeQueue.put(
            DecodeWindow(
                session,
                samples,
                startSamples * 1000L / SAMPLE_RATE_HZ,
                endSamples * 1000L / SAMPLE_RATE_HZ,
                endOfTurn,
                endOfStream,
                nextWindowOrder.getAndIncrement(),
            )
        )
    }

    private fun decodeLoop() {
        while (!stopping) {
            val window = try {
                decodeQueue.take()
            } catch (_: InterruptedException) {
                if (stopping) return else continue
            }
            val session = window.session
            session.pendingWindows.decrementAndGet()
            if (session.cancelled.get() || session.completed.get()) continue
            if (window.endOfStream) {
                complete(session)
                continue
            }
            if (window.samples == null && window.endOfTurn) {
                emitTurn(session, session.turn.finishTurn())
                continue
            }
            try {
                val decodeStartedAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "DECODE_START id=${session.id} start_ms=${window.startMillis} " +
                    "end_ms=${window.endMillis} samples=${window.samples?.size ?: 0}")
                val decoded = synchronized(modelLock) {
                    if (session.cancelled.get() || session.completed.get()) {
                        emptyArray<String>()
                    } else {
                        val model = ensureModel(session.artifact)
                        val cancellation = NativeWhisper.createCancellation()
                        check(cancellation != 0L) { "native cancellation token is absent" }
                        session.decodeCancellation.attach(cancellation, nativeDecodeSignal)
                        try {
                            NativeWhisper.transcribe(
                                model.nativeContext,
                                window.samples!!,
                                "auto",
                                THREAD_COUNT,
                                cancellation,
                            )
                        } finally {
                            session.decodeCancellation.finish(cancellation, nativeDecodeSignal)
                        }
                    }
                }
                if (session.cancelled.get() || session.completed.get()) continue
                if (decoded == null) continue
                val language = decoded.getOrElse(0) { "und" }
                val text = decoded.getOrElse(1) { "" }.trim()
                Log.i(TAG, "DECODE_DONE id=${session.id} language=$language chars=${text.length} " +
                    "elapsed_ms=${SystemClock.elapsedRealtime() - decodeStartedAt}")
                if (language !in setOf("en", "es")) {
                    fail(session, ERROR_INVALID_REQUEST,
                         "detected language is outside English/Spanish policy")
                    continue
                }
                if (language == "en") session.englishWindows.incrementAndGet()
                if (language == "es") session.spanishWindows.incrementAndGet()
                session.decodedWindows.incrementAndGet()
                emitTurn(
                    session,
                    session.turn.acceptDecoded(
                        text,
                        language,
                        window.startMillis,
                        window.endMillis,
                        window.endOfTurn,
                    ),
                )
            } catch (_: RemoteException) {
                cancelInternal(session.id)
            } catch (error: Exception) {
                Log.e(TAG, "DECODE_FAILED id=${session.id}", error)
                fail(session, ERROR_RUNTIME_FAILED,
                     (error::class.java.simpleName + ": ASR decode failed").take(256))
            }
        }
    }

    private fun emitTurn(
        session: AsrSession,
        emission: StreamingAsrTurnAccumulator.Emission?,
    ) {
        if (emission == null) return
        val chunk = GenerationChunk().apply {
            sequence = session.sequence.getAndIncrement()
            text = emission.text
            language = emission.language
            this.isFinal = emission.finalChunk
            confidence = 0.0f
            sourceStartMillis = emission.startMillis
            sourceEndMillis = emission.endMillis
        }
        try {
            Log.i(TAG, "CHUNK id=${session.id} final=${emission.finalChunk} " +
                "chars=${emission.text.length} source_start_ms=${emission.startMillis} " +
                "source_end_ms=${emission.endMillis}")
            session.callback.onChunk(chunk)
        } catch (_: RemoteException) {
            cancelInternal(session.id)
            return
        }
    }

    private fun ensureModel(artifact: RuntimeArtifact): ModelHolder {
        check(Thread.holdsLock(modelLock)) { "model access is not serialized" }
        val model = verifiedModelFile(artifact)
        val identity = ModelIdentity(model.absolutePath, artifact.modelDigest)
        currentModel?.let { holder ->
            if (holder.identity == identity && holder.nativeContext != 0L) return holder
        }
        check(sessions.values.none { it.decodedWindows.get() > 0
            && !it.completed.get() && it.artifact.modelDigest != artifact.modelDigest }) {
            "cannot switch ASR models while another stream is active"
        }
        closeModelLocked()
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "MODEL_INITIALIZE_START model=${artifact.modelId} bytes=${model.length()}")
        val context = NativeWhisper.create(model.absolutePath)
        check(context != 0L) { "native model context is absent" }
        Log.i(TAG, "MODEL_INITIALIZE_DONE model=${artifact.modelId} elapsed_ms=" +
            (SystemClock.elapsedRealtime() - startedAt))
        return ModelHolder(identity, context).also { currentModel = it }
    }

    private fun complete(session: AsrSession) {
        if (!session.completed.compareAndSet(false, true)) return
        Log.i(TAG, "SESSION_DONE id=${session.id} windows=${session.decodedWindows.get()} " +
            "elapsed_ms=${SystemClock.elapsedRealtime() - session.createdAtElapsed}")
        sessions.remove(session.id, session)
        session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
        closeDescriptor(session.input)
        val language = when {
            session.spanishWindows.get() > session.englishWindows.get() -> "es"
            session.englishWindows.get() > 0 -> "en"
            else -> "und"
        }
        val output = JSONObject()
            .put("schema_version", 1)
            .put("language", language)
            .put("decoded_windows", session.decodedWindows.get())
            .toString()
        val result = InferenceResult().apply {
            requestId = session.request.requestId
            capability = session.request.capability
            modelId = session.artifact.modelId
            modelDigest = session.artifact.modelDigest
            this.language = language
            outputJson = output
            elapsedMillis = SystemClock.elapsedRealtime() - session.createdAtElapsed
        }
        try {
            session.callback.onCompleted(result)
        } catch (_: RemoteException) {
            // Broker/client has already gone away.
        }
    }

    private fun fail(session: AsrSession, code: Int, message: String) {
        if (!session.completed.compareAndSet(false, true)) return
        Log.e(TAG, "SESSION_FAILED id=${session.id} code=$code elapsed_ms=" +
            (SystemClock.elapsedRealtime() - session.createdAtElapsed) + " message=$message")
        sessions.remove(session.id, session)
        session.cancelled.set(true)
        session.decodeCancellation.cancel(nativeDecodeSignal)
        session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
        closeDescriptor(session.input)
        removeQueuedWindows(session)
        notifyError(session.callback, code, message)
    }

    private fun cancelInternal(sessionId: Long) {
        val session = sessions.remove(sessionId) ?: return
        session.cancelled.set(true)
        session.completed.set(true)
        session.decodeCancellation.cancel(nativeDecodeSignal)
        session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
        closeDescriptor(session.input)
        removeQueuedWindows(session)
    }

    private fun removeQueuedWindows(session: AsrSession) {
        decodeQueue.removeIf { window ->
            if (window.session === session) {
                session.pendingWindows.decrementAndGet()
                true
            } else false
        }
    }

    private fun closeModel() {
        synchronized(modelLock) { closeModelLocked() }
    }

    private fun closeModelLocked() {
        check(Thread.holdsLock(modelLock)) { "model access is not serialized" }
        val holder = currentModel ?: return
        currentModel = null
        if (holder.nativeContext != 0L) NativeWhisper.destroy(holder.nativeContext)
    }

    private fun requireSession(sessionId: Long): AsrSession =
        sessions[sessionId] ?: throw SecurityException("ASR runtime session is absent")

    private fun enforceBrokerCaller() {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid())
        if (packages == null || packages.size != 1 || packages[0] != BROKER_PACKAGE) {
            throw SecurityException("only AIOS Model Broker may call the ASR runtime provider")
        }
    }

    private fun validRequest(artifact: RuntimeArtifact, request: ModelRequest): Boolean {
        return artifact.modelId.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))
            && artifact.modelDigest.matches(Regex("[0-9a-f]{64}"))
            && artifact.sizeBytes > 0L && artifact.backend == "cpu"
            && request.requestId.isNotEmpty()
            && request.capability == "streaming_asr"
            && request.language == "und"
            && request.workload in setOf("call_rx", "call_tx", "media_background")
    }

    private fun directionMatches(workload: String, direction: String?): Boolean =
        (workload == "call_rx" && direction == "downlink")
            || (workload == "call_tx" && direction == "uplink")
            || (workload == "media_background" && direction == "media")

    private fun verifiedModelFile(artifact: RuntimeArtifact): File {
        val model = File(artifact.modelPath).canonicalFile
        val allowedDirectories = mutableListOf(MODEL_DIRECTORY.canonicalFile)
        if (allowsEmulatorModelFixtures()) {
            allowedDirectories += File(filesDir, EMULATOR_FIXTURE_DIRECTORY).canonicalFile
        }
        val confined = allowedDirectories.any { directory ->
            model.path.startsWith(directory.path + File.separator)
        }
        check(confined && model.isFile) {
            "model path is outside the read-only model directory"
        }
        check(model.length() == artifact.sizeBytes) { "model size mismatch" }
        check(MessageDigest.isEqual(
            artifact.modelDigest.toByteArray(Charsets.US_ASCII),
            sha256(model).toByteArray(Charsets.US_ASCII),
        )) { "model digest mismatch" }
        return model
    }

    private fun allowsEmulatorModelFixtures(): Boolean =
        BuildConfig.ALLOW_EMULATOR_MODEL_FIXTURES && (
            Build.HARDWARE.equals("ranchu", ignoreCase = true)
                || Build.HARDWARE.equals("goldfish", ignoreCase = true)
                || Build.PRODUCT.contains("sdk", ignoreCase = true)
                || Build.FINGERPRINT.startsWith("generic")
        )

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
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun pcm16ToFloat(bytes: ByteArray, byteCount: Int): FloatArray {
        val count = byteCount / 2
        return FloatArray(count) { index ->
            val low = bytes[index * 2].toInt() and 0xff
            val high = bytes[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort() / 32768.0f
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
            // Best effort during teardown.
        }
    }
}
