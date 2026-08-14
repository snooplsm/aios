package com.aios.runtime.litertlm

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
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.gson.JsonObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Crash-isolated LiteRT-LM provider. The public Model Broker remains the only
 * policy and artifact-selection authority; this process only executes work.
 */
class LiteRtLmRuntimeService : Service() {
    private companion object {
        const val TAG = "AiosLiteRtLmRuntime"
        const val BROKER_PACKAGE = "com.aios.modelbroker"
        const val RUNTIME_ID = "litert_lm"
        const val IMPLEMENTATION_VERSION = "0.15.1"
        const val PROVIDER_API_VERSION = 2
        const val ERROR_INVALID_REQUEST = 2
        const val ERROR_BUSY = 3
        const val ERROR_RUNTIME_FAILED = 5
        const val MAX_SESSIONS = 1
        const val MAX_MEDIA_BYTES = 32 * 1024 * 1024
        const val HASH_BUFFER_BYTES = 1024 * 1024
        const val MAX_CALLBACK_MESSAGE_CHARS = 256
        val MODEL_DIRECTORY = File("/product/etc/aios/models")
        const val EMULATOR_FIXTURE_DIRECTORY = "emulator-models"
    }

    private data class EngineIdentity(
        val path: String,
        val digest: String,
        val backend: String,
        val vision: Boolean,
        val audio: Boolean,
    )

    private data class EngineHolder(val identity: EngineIdentity, val engine: Engine)

    private data class VerifiedModelIdentity(
        val path: String,
        val digest: String,
        val sizeBytes: Long,
        val lastModifiedMillis: Long,
        val fileKey: String,
    )

    private class ProviderSession(
        val id: Long,
        val artifact: RuntimeArtifact,
        val request: ModelRequest,
        val callback: IModelCallback,
        val createdAtElapsed: Long,
    ) {
        val cancelled = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val sequence = AtomicLong(0L)
        val response = StringBuilder()
        lateinit var deathRecipient: IBinder.DeathRecipient
        @Volatile var conversation: Conversation? = null
    }

    private val nextSessionId = AtomicLong(1L)
    private val sessions = ConcurrentHashMap<Long, ProviderSession>()
    private val runtimeExecutor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "aios-litertlm-runtime").apply { priority = Thread.NORM_PRIORITY }
    }
    @Volatile private var engineHolder: EngineHolder? = null
    @Volatile private var verifiedModelIdentity: VerifiedModelIdentity? = null
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
            return arrayOf("gpu", "cpu")
        }

        override fun createSession(
            artifact: RuntimeArtifact?,
            request: ModelRequest?,
            callback: IModelCallback?,
        ): Long {
            enforceBrokerCaller()
            if (stopping || artifact == null || request == null || callback == null
                || !validRequest(artifact, request)) {
                notifyError(callback, ERROR_INVALID_REQUEST, "invalid runtime request")
                return -1L
            }
            if (sessions.size >= MAX_SESSIONS) {
                notifyError(callback, ERROR_BUSY, "runtime session capacity reached")
                return -1L
            }
            val id = nextSessionId.getAndIncrement()
            val session = ProviderSession(
                id, artifact, request, callback, SystemClock.elapsedRealtime())
            session.deathRecipient = IBinder.DeathRecipient { cancelInternal(id) }
            try {
                callback.asBinder().linkToDeath(session.deathRecipient, 0)
            } catch (_: RemoteException) {
                return -1L
            }
            if (sessions.putIfAbsent(id, session) != null) {
                callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
                notifyError(callback, ERROR_RUNTIME_FAILED, "session ID collision")
                return -1L
            }
            Log.i(TAG, "SESSION_CREATED id=$id capability=${request.capability} " +
                "model=${artifact.modelId} backend=${artifact.backend}")
            runtimeExecutor.execute { prepare(session) }
            return id
        }

        override fun submitText(sessionId: Long, text: String?, endOfInput: Boolean) {
            enforceBrokerCaller()
            val session = requireSession(sessionId)
            if (text.isNullOrEmpty() || !endOfInput
                || session.request.capability !in setOf(
                    "text_generation", "call_classification", "call_summary")) {
                fail(session, ERROR_INVALID_REQUEST, "invalid text input")
                return
            }
            Log.i(TAG, "TEXT_SUBMITTED id=$sessionId chars=${text.length}")
            runtimeExecutor.execute {
                startGeneration(session) { conversation, callback ->
                    conversation.sendMessageAsync(
                        text,
                        callback,
                        maxOutputToken = session.request.maxOutputTokens,
                    )
                }
            }
        }

        override fun submitAudio(
            sessionId: Long,
            pcmStream: ParcelFileDescriptor?,
            format: AudioStreamFormat?,
            endOfInput: Boolean,
        ) {
            enforceBrokerCaller()
            closeDescriptor(pcmStream)
            val session = requireSession(sessionId)
            fail(session, ERROR_INVALID_REQUEST,
                 "LiteRT-LM provider does not implement streaming PCM ASR")
        }

        override fun attachAudioOutput(
            sessionId: Long,
            pcmSink: ParcelFileDescriptor?,
            format: AudioStreamFormat?,
        ) {
            enforceBrokerCaller()
            closeDescriptor(pcmSink)
            val session = requireSession(sessionId)
            fail(session, ERROR_INVALID_REQUEST,
                 "LiteRT-LM provider does not implement speech synthesis")
        }

        override fun submitMedia(
            sessionId: Long,
            media: ParcelFileDescriptor?,
            mimeType: String?,
            endOfInput: Boolean,
        ) {
            enforceBrokerCaller()
            val session = requireSession(sessionId)
            val capability = session.request.capability
            val validMedia = when (capability) {
                "image_understanding", "video_understanding" ->
                    mimeType?.startsWith("image/") == true
                "audio_understanding" -> mimeType?.startsWith("audio/") == true
                else -> false
            }
            if (media == null || !endOfInput || !validMedia) {
                closeDescriptor(media)
                fail(session, ERROR_INVALID_REQUEST, "invalid media input")
                return
            }
            val owned = try {
                ParcelFileDescriptor.dup(media.fileDescriptor)
            } catch (_: IOException) {
                null
            } finally {
                closeDescriptor(media)
            }
            if (owned == null) {
                fail(session, ERROR_INVALID_REQUEST, "cannot duplicate media input")
                return
            }
            runtimeExecutor.execute {
                owned.use { descriptor ->
                    try {
                        val bytes = readBounded(descriptor, MAX_MEDIA_BYTES)
                        Log.i(TAG, "MEDIA_READ id=$sessionId mime=$mimeType bytes=${bytes.size}")
                        val prompt = mediaPrompt(session.request.language, capability)
                        val contents = when (capability) {
                            "image_understanding", "video_understanding" -> Contents.of(
                                Content.ImageBytes(bytes), Content.Text(prompt))
                            "audio_understanding" -> Contents.of(
                                Content.AudioBytes(bytes), Content.Text(prompt))
                            else -> throw IOException("unsupported media capability")
                        }
                        startGeneration(session) { conversation, callback ->
                            conversation.sendMessageAsync(
                                contents,
                                callback,
                                maxOutputToken = session.request.maxOutputTokens,
                            )
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "MEDIA_FAILED id=$sessionId", error)
                        fail(session, ERROR_RUNTIME_FAILED, safeError(error))
                    }
                }
            }
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
        runtimeExecutor.execute {
            closeEngine()
        }
        runtimeExecutor.shutdown()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (RuntimeMemoryTrimPolicy.isMemoryPressure(level) && sessions.isEmpty()) {
            runtimeExecutor.execute {
                if (sessions.isEmpty()) closeEngine()
            }
        }
    }

    private fun prepare(session: ProviderSession) {
        if (session.cancelled.get() || session.completed.get()) return
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "PREPARE_START id=${session.id} capability=${session.request.capability}")
        try {
            val model = verifiedModelFile(session.artifact)
            Log.i(TAG, "MODEL_VERIFIED id=${session.id} bytes=${model.length()} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
            val audio = session.request.capability == "audio_understanding"
            // The catalog's text and image/video aliases point at the same
            // multimodal LiteRT-LM package. Keep one vision-capable engine for
            // those roles instead of destroying it when the role changes.
            val vision = !audio
            val identity = EngineIdentity(
                model.absolutePath,
                session.artifact.modelDigest,
                session.artifact.backend,
                vision,
                audio,
            )
            val engine = ensureEngine(identity)
            Log.i(TAG, "ENGINE_READY id=${session.id} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
            if (session.cancelled.get() || session.completed.get()) return
            val instruction = when (session.request.capability) {
                "image_understanding", "video_understanding", "audio_understanding" ->
                    mediaPrompt(session.request.language, session.request.capability)
                "call_summary" -> "Summarize the call faithfully. Do not invent facts."
                else -> "You are an on-device assistant. Do not claim to have used tools."
            }
            session.conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(instruction),
                    automaticToolCalling = false,
                    channels = emptyList(),
                    maxOutputToken = session.request.maxOutputTokens,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                )
            )
            Log.i(TAG, "CONVERSATION_READY id=${session.id} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
        } catch (error: Exception) {
            Log.e(TAG, "PREPARE_FAILED id=${session.id}", error)
            fail(session, ERROR_RUNTIME_FAILED, safeError(error))
        }
    }

    private fun ensureEngine(identity: EngineIdentity): Engine {
        engineHolder?.let { current ->
            if (current.identity == identity && current.engine.isInitialized()) {
                return current.engine
            }
        }
        check(sessions.values.none { item ->
            item.conversation != null && !item.completed.get() && !item.cancelled.get()
        }) { "cannot switch models while another conversation is active" }
        closeEngine()
        val backend = when (identity.backend) {
            "gpu" -> Backend.GPU()
            "cpu" -> Backend.CPU()
            else -> throw IllegalArgumentException("backend is not allowlisted")
        }
        val modelCache = File(cacheDir, identity.digest).apply { mkdirs() }
        val engine = Engine(
            EngineConfig(
                modelPath = identity.path,
                backend = backend,
                visionBackend = backend.takeIf { identity.vision },
                audioBackend = backend.takeIf { identity.audio },
                maxNumTokens = 4096,
                maxNumImages = 1.takeIf { identity.vision },
                cacheDir = modelCache.absolutePath,
            )
        )
        val initializedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "ENGINE_INITIALIZE_START backend=${identity.backend} " +
            "vision=${identity.vision} audio=${identity.audio}")
        engine.initialize()
        Log.i(TAG, "ENGINE_INITIALIZE_DONE backend=${identity.backend} " +
            "elapsed_ms=${SystemClock.elapsedRealtime() - initializedAt}")
        engineHolder = EngineHolder(identity, engine)
        return engine
    }

    private fun startGeneration(
        session: ProviderSession,
        send: (Conversation, MessageCallback) -> Unit,
    ) {
        if (session.cancelled.get() || session.completed.get()) return
        val conversation = session.conversation
        if (conversation == null) {
            fail(session, ERROR_RUNTIME_FAILED, "conversation did not initialize")
            return
        }
        if (!session.started.compareAndSet(false, true)) {
            fail(session, ERROR_BUSY, "session already has an inference in flight")
            return
        }
        try {
            Log.i(TAG, "INFERENCE_START id=${session.id} " +
                "capability=${session.request.capability}")
            send(conversation, callbackFor(session))
        } catch (error: Exception) {
            Log.e(TAG, "INFERENCE_START_FAILED id=${session.id}", error)
            fail(session, ERROR_RUNTIME_FAILED, safeError(error))
        }
    }

    private fun callbackFor(session: ProviderSession) = object : MessageCallback {
        override fun onMessage(message: Message) {
            if (session.completed.get() || session.cancelled.get()) return
            val text = message.toString()
            if (session.sequence.get() == 0L) {
                Log.i(TAG, "FIRST_TOKEN id=${session.id} elapsed_ms=" +
                    (SystemClock.elapsedRealtime() - session.createdAtElapsed))
            }
            synchronized(session.response) { session.response.append(text) }
            val chunk = GenerationChunk().apply {
                sequence = session.sequence.getAndIncrement()
                this.text = text
                language = session.request.language
                isFinal = false
                confidence = 0.0f
                sourceStartMillis = 0L
                sourceEndMillis = 0L
            }
            try {
                session.callback.onChunk(chunk)
            } catch (_: RemoteException) {
                cancelInternal(session.id)
            }
        }

        override fun onDone() {
            if (!session.completed.compareAndSet(false, true)) return
            sessions.remove(session.id, session)
            session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
            val raw = synchronized(session.response) { session.response.toString().trim() }
            Log.i(TAG, "INFERENCE_DONE id=${session.id} chars=${raw.length} elapsed_ms=" +
                (SystemClock.elapsedRealtime() - session.createdAtElapsed))
            val output = if (session.request.capability in setOf(
                    "call_classification", "image_understanding", "video_understanding",
                    "audio_understanding")) {
                stripMarkdownFence(raw)
            } else {
                JsonObject().apply {
                    addProperty("schema_version", 1)
                    addProperty("text", raw)
                }.toString()
            }
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
            } finally {
                runtimeExecutor.execute { closeConversation(session) }
            }
        }

        override fun onError(throwable: Throwable) {
            Log.e(TAG, "INFERENCE_FAILED id=${session.id}", throwable)
            fail(session, ERROR_RUNTIME_FAILED, safeError(throwable))
        }
    }

    private fun fail(session: ProviderSession, code: Int, message: String) {
        if (!session.completed.compareAndSet(false, true)) return
        Log.e(TAG, "SESSION_FAILED id=${session.id} code=$code elapsed_ms=" +
            (SystemClock.elapsedRealtime() - session.createdAtElapsed) + " message=$message")
        sessions.remove(session.id, session)
        session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
        notifyError(session.callback, code, message)
        runtimeExecutor.execute { closeConversation(session) }
    }

    private fun cancelInternal(sessionId: Long) {
        val session = sessions.remove(sessionId) ?: return
        Log.w(TAG, "SESSION_CANCELLED id=$sessionId elapsed_ms=" +
            (SystemClock.elapsedRealtime() - session.createdAtElapsed))
        session.cancelled.set(true)
        session.completed.set(true)
        session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0)
        runtimeExecutor.execute {
            try {
                session.conversation?.cancelProcess()
            } catch (_: RuntimeException) {
                // Cancellation is best effort after native failure.
            }
            closeConversation(session)
        }
    }

    private fun closeConversation(session: ProviderSession) {
        val conversation = session.conversation ?: return
        session.conversation = null
        try {
            conversation.close()
        } catch (_: RuntimeException) {
            // A native error may already have invalidated the conversation.
        }
    }

    private fun closeEngine() {
        val current = engineHolder ?: return
        engineHolder = null
        try {
            current.engine.close()
        } catch (_: RuntimeException) {
            // Provider-process teardown is the final isolation boundary.
        }
    }

    private fun requireSession(sessionId: Long): ProviderSession =
        sessions[sessionId] ?: throw SecurityException("runtime session is absent")

    private fun enforceBrokerCaller() {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid())
        if (packages == null || packages.size != 1 || packages[0] != BROKER_PACKAGE) {
            throw SecurityException("only AIOS Model Broker may call the runtime provider")
        }
    }

    private fun validRequest(artifact: RuntimeArtifact, request: ModelRequest): Boolean {
        return artifact.modelId.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))
            && artifact.modelDigest.matches(Regex("[0-9a-f]{64}"))
            && artifact.sizeBytes > 0L
            && artifact.backend in setOf("gpu", "cpu")
            && request.requestId.isNotEmpty()
            && request.language in setOf("en", "es")
            && request.maxOutputTokens in 1..4096
            && request.capability in setOf(
                "text_generation", "call_classification", "call_summary",
                "image_understanding", "video_understanding", "audio_understanding")
    }

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
        val observed = modelIdentity(model, artifact.modelDigest)
        check(observed.sizeBytes == artifact.sizeBytes) { "model size mismatch" }
        if (verifiedModelIdentity == observed) {
            Log.i(TAG, "MODEL_DIGEST_CACHE_HIT model=${artifact.modelId} " +
                "bytes=${observed.sizeBytes}")
            return model
        }
        val actualDigest = sha256(model)
        check(modelIdentity(model, artifact.modelDigest) == observed) {
            "model changed during digest verification"
        }
        check(MessageDigest.isEqual(
            artifact.modelDigest.toByteArray(Charsets.US_ASCII),
            actualDigest.toByteArray(Charsets.US_ASCII),
        )) { "model digest mismatch" }
        verifiedModelIdentity = observed
        Log.i(TAG, "MODEL_DIGEST_VERIFIED model=${artifact.modelId} " +
            "bytes=${observed.sizeBytes}")
        return model
    }

    private fun modelIdentity(model: File, digest: String): VerifiedModelIdentity {
        val attributes = Files.readAttributes(
            model.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        check(attributes.isRegularFile) { "model is not a regular file" }
        return VerifiedModelIdentity(
            model.absolutePath,
            digest,
            attributes.size(),
            attributes.lastModifiedTime().toMillis(),
            attributes.fileKey()?.toString() ?: "",
        )
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
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun readBounded(descriptor: ParcelFileDescriptor, limit: Int): ByteArray {
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            val bytes = stream.readNBytes(limit + 1)
            if (bytes.size > limit) throw IOException("media input exceeds limit")
            return bytes
        }
    }

    private fun mediaPrompt(language: String, capability: String): String {
        val languageName = if (language == "es") "Spanish" else "English"
        val source = if (capability == "video_understanding") {
                "The image is a chronological 5 by 4 storyboard of twenty keyframes " +
                    "sampled from one video. " +
                "Describe the video across the frames without claiming to have heard audio. "
        } else {
            "Describe only the supplied media. "
        }
        return source + "Return only one compact JSON object with exactly: " +
            "schema_version=1, caption as a non-empty $languageName string, " +
            "tags as up to 64 unique short strings, language=\"$language\", and " +
            "confidence as a number from 0 to 1. Do not use Markdown."
    }

    private fun stripMarkdownFence(value: String): String {
        if (!value.startsWith("```")) return value
        val firstNewline = value.indexOf('\n')
        val lastFence = value.lastIndexOf("```")
        return if (firstNewline >= 0 && lastFence > firstNewline) {
            value.substring(firstNewline + 1, lastFence).trim()
        } else value
    }

    private fun safeError(error: Throwable): String =
        (error::class.java.simpleName + ": " + (error.message ?: "runtime failure"))
            .take(MAX_CALLBACK_MESSAGE_CHARS)

    private fun notifyError(callback: IModelCallback?, code: Int, message: String) {
        if (callback == null) return
        try {
            callback.onError(code, message.take(MAX_CALLBACK_MESSAGE_CHARS))
        } catch (_: RemoteException) {
            // Broker/client is already gone.
        }
    }

    private fun closeDescriptor(descriptor: ParcelFileDescriptor?) {
        try {
            descriptor?.close()
        } catch (_: IOException) {
            // Best effort while rejecting or forwarding input.
        }
    }
}
