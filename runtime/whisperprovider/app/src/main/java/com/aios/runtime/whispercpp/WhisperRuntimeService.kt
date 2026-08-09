package com.aios.runtime.whispercpp

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import com.aios.model.AudioStreamFormat
import com.aios.model.GenerationChunk
import com.aios.model.IModelCallback
import com.aios.model.InferenceResult
import com.aios.model.ModelRequest
import com.aios.runtime.IAiosRuntimeProvider
import com.aios.runtime.RuntimeArtifact
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
import kotlin.math.sqrt

/** CPU whisper.cpp provider with incoming-call decode priority. */
class WhisperRuntimeService : Service() {
    private companion object {
        const val BROKER_PACKAGE = "com.aios.modelbroker"
        const val RUNTIME_ID = "whisper_cpp"
        const val IMPLEMENTATION_VERSION = "1.9.2"
        const val PROVIDER_API_VERSION = 2
        const val ERROR_INVALID_REQUEST = 2
        const val ERROR_BUSY = 3
        const val ERROR_RUNTIME_FAILED = 5
        const val SAMPLE_RATE_HZ = 16_000
        const val WINDOW_SECONDS = 4
        const val WINDOW_SAMPLES = SAMPLE_RATE_HZ * WINDOW_SECONDS
        const val WINDOW_BYTES = WINDOW_SAMPLES * 2
        const val MIN_FINAL_SAMPLES = SAMPLE_RATE_HZ / 2
        const val MAX_PENDING_WINDOWS = 4
        const val MAX_SESSIONS = 2
        const val HASH_BUFFER_BYTES = 1024 * 1024
        const val THREAD_COUNT = 4
        const val MIN_RMS = 0.005f
        val MODEL_DIRECTORY = File("/product/etc/aios/models")
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
        lateinit var deathRecipient: IBinder.DeathRecipient
        @Volatile var input: ParcelFileDescriptor? = null
        @Volatile var reader: Thread? = null

        val priority: Int
            get() = if (request.workload == "call_rx") 3 else 2
    }

    private class DecodeWindow(
        val session: AsrSession,
        val samples: FloatArray?,
        val startMillis: Long,
        val endMillis: Long,
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
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            synchronized(modelLock) {
                if (sessions.isEmpty() && decodeQueue.isEmpty()) closeModelLocked()
            }
        }
    }

    private fun readPcm(session: AsrSession, descriptor: ParcelFileDescriptor) {
        val buffer = ByteArray(WINDOW_BYTES)
        var filled = 0
        var sampleOffset = 0L
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                while (!session.cancelled.get() && !session.completed.get()) {
                    val read = input.read(buffer, filled, buffer.size - filled)
                    if (read < 0) break
                    if (read == 0) continue
                    filled += read
                    if (filled == buffer.size) {
                        enqueueWindow(session, pcm16ToFloat(buffer, filled), sampleOffset)
                        sampleOffset += WINDOW_SAMPLES
                        filled = 0
                    }
                }
                if (!session.cancelled.get() && !session.completed.get()
                    && filled / 2 >= MIN_FINAL_SAMPLES) {
                    enqueueWindow(session, pcm16ToFloat(buffer, filled), sampleOffset)
                    sampleOffset += filled / 2
                }
                if (!session.cancelled.get() && !session.completed.get()) {
                    enqueue(session, null, sampleOffset, sampleOffset, true)
                }
            }
        } catch (error: IOException) {
            if (!session.cancelled.get()) {
                fail(session, ERROR_RUNTIME_FAILED, "PCM stream failed")
            }
        } finally {
            session.input = null
        }
    }

    private fun enqueueWindow(session: AsrSession, samples: FloatArray, offset: Long) {
        val end = offset + samples.size
        if (!hasSpeech(samples)) return
        enqueue(session, samples, offset, end, false)
    }

    private fun enqueue(
        session: AsrSession,
        samples: FloatArray?,
        startSamples: Long,
        endSamples: Long,
        endOfStream: Boolean,
    ) {
        if (session.pendingWindows.incrementAndGet() > MAX_PENDING_WINDOWS) {
            session.pendingWindows.decrementAndGet()
            fail(session, ERROR_BUSY, "ASR fell behind real time")
            return
        }
        decodeQueue.put(
            DecodeWindow(
                session,
                samples,
                startSamples * 1000L / SAMPLE_RATE_HZ,
                endSamples * 1000L / SAMPLE_RATE_HZ,
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
            try {
                val decoded = synchronized(modelLock) {
                    if (session.cancelled.get() || session.completed.get()) {
                        emptyArray()
                    } else {
                        val model = ensureModel(session.artifact)
                        NativeWhisper.transcribe(
                            model.nativeContext,
                            window.samples!!,
                            "auto",
                            THREAD_COUNT,
                        )
                    }
                }
                if (session.cancelled.get() || session.completed.get()) continue
                val language = decoded.getOrElse(0) { "und" }
                val text = decoded.getOrElse(1) { "" }.trim()
                if (language !in setOf("en", "es")) {
                    fail(session, ERROR_INVALID_REQUEST,
                         "detected language is outside English/Spanish policy")
                    continue
                }
                if (language == "en") session.englishWindows.incrementAndGet()
                if (language == "es") session.spanishWindows.incrementAndGet()
                session.decodedWindows.incrementAndGet()
                if (text.isNotEmpty()) {
                    val chunk = GenerationChunk().apply {
                        sequence = session.sequence.getAndIncrement()
                        this.text = text
                        this.language = language
                        isFinal = false
                        confidence = 0.0f
                        sourceStartMillis = window.startMillis
                        sourceEndMillis = window.endMillis
                    }
                    session.callback.onChunk(chunk)
                }
            } catch (_: RemoteException) {
                cancelInternal(session.id)
            } catch (error: Exception) {
                fail(session, ERROR_RUNTIME_FAILED,
                     (error::class.java.simpleName + ": ASR decode failed").take(256))
            }
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
        val context = NativeWhisper.create(model.absolutePath)
        check(context != 0L) { "native model context is absent" }
        return ModelHolder(identity, context).also { currentModel = it }
    }

    private fun complete(session: AsrSession) {
        if (!session.completed.compareAndSet(false, true)) return
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
        sessions.remove(session.id, session)
        session.cancelled.set(true)
        session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
        closeDescriptor(session.input)
        removeQueuedWindows(session)
        notifyError(session.callback, code, message)
    }

    private fun cancelInternal(sessionId: Long) {
        val session = sessions.remove(sessionId) ?: return
        session.cancelled.set(true)
        session.completed.set(true)
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
            && request.workload in setOf("call_rx", "call_tx")
    }

    private fun directionMatches(workload: String, direction: String?): Boolean =
        (workload == "call_rx" && direction == "downlink")
            || (workload == "call_tx" && direction == "uplink")

    private fun verifiedModelFile(artifact: RuntimeArtifact): File {
        val directory = MODEL_DIRECTORY.canonicalFile
        val model = File(artifact.modelPath).canonicalFile
        val prefix = directory.path + File.separator
        check(model.path.startsWith(prefix) && model.isFile) {
            "model path is outside the read-only model directory"
        }
        check(model.length() == artifact.sizeBytes) { "model size mismatch" }
        check(MessageDigest.isEqual(
            artifact.modelDigest.toByteArray(Charsets.US_ASCII),
            sha256(model).toByteArray(Charsets.US_ASCII),
        )) { "model digest mismatch" }
        return model
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

    private fun hasSpeech(samples: FloatArray): Boolean {
        if (samples.isEmpty()) return false
        var sum = 0.0
        for (sample in samples) sum += sample * sample
        return sqrt(sum / samples.size) >= MIN_RMS
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
