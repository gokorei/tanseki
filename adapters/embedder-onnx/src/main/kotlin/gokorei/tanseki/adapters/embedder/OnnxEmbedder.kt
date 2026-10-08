package gokorei.tanseki.adapters.embedder

import gokorei.tanseki.core.domain.UnavailableException
import gokorei.tanseki.core.ports.Embedder
import gokorei.tanseki.core.ports.RetryPolicy
import gokorei.tanseki.core.ports.retry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.LongBuffer
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Local ONNX [Embedder].
 *
 * The ONNX Runtime is accessed **reflectively** and declared as a `runtimeOnly`
 * dependency. This is deliberate: putting the ~50 MB `onnxruntime` jar on the
 * Kotlin *compile* classpath makes the Kotlin compiler take minutes per compile
 * (observed: 15 min for one file), while the adapter only needs a handful of ORT
 * API calls. Reflection keeps the build fast and confines the coupling to this
 * class; deploy with `onnxruntime` on the runtime classpath.
 *
 * - The session is created lazily, so construction never fails on a missing model.
 * - Handles rank-3 `[1, seq, dim]` transformer output (mean-pooled), rank-2
 *   `[1, dim]`, or a flat vector.
 */
class OnnxEmbedder(
    private val modelPath: Path,
    private val tokenizer: Tokenizer,
    override val model: String,
    override val dimensions: Int,
    private val maxLength: Int = 256,
    private val intraOpThreads: Int = 1,
    private val retryPolicy: RetryPolicy =
        RetryPolicy(
            maxAttempts = 3,
            baseDelay = 50.milliseconds,
            maxDelay = 2.seconds,
            jitter = 0.2
        )
) : Embedder, AutoCloseable {
    // A read/write lock rather than a monitor: embedding readers run concurrently,
    // while close() takes the write side and therefore cannot close a native
    // session another thread is inside. A plain monitor would serialise every
    // embed; an unsynchronised close would free native state out from under one.
    private val sessionLock = ReentrantReadWriteLock()

    @Volatile
    private var session: Any? = null

    @Volatile
    private var closed = false

    private val environmentClass by lazy { Class.forName("ai.onnxruntime.OrtEnvironment") }
    private val sessionOptionsClass by lazy { Class.forName("ai.onnxruntime.OrtSession\$SessionOptions") }
    private val tensorClass by lazy { Class.forName("ai.onnxruntime.OnnxTensor") }

    private val environment: Any by lazy {
        environmentClass.getMethod("getEnvironment").invoke(null)!!
    }

    /** Reflective handles, cached so the embed hot path does not re-resolve every call. */
    private val methods = java.util.concurrent.ConcurrentHashMap<MethodKey, java.lang.reflect.Method>()

    private fun method(owner: Class<*>, name: String, vararg parameterTypes: Class<*>): java.lang.reflect.Method =
        methods.computeIfAbsent(MethodKey(owner, name, parameterTypes.toList())) {
            owner.getMethod(name, *parameterTypes)
        }

    private val sessionOptionsConstructor by lazy { sessionOptionsClass.getConstructor() }

    private val setIntraOpThreadsMethod by lazy {
        sessionOptionsClass.getMethod("setIntraOpNumThreads", Int::class.javaPrimitiveType!!)
    }

    private val createSessionMethod by lazy {
        environmentClass.getMethod("createSession", String::class.java, sessionOptionsClass)
    }

    private val createTensorMethod by lazy {
        tensorClass.getMethod("createTensor", environmentClass, LongBuffer::class.java, LongArray::class.java)
    }

    private data class MethodKey(
        val owner: Class<*>,
        val name: String,
        val parameterTypes: List<Class<*>>
    )

    override fun embed(texts: List<String>): List<FloatArray> =
        runBlocking {
            texts.map { text -> retrySafeEmbedderRead(retryPolicy) { embedOne(text) } }
        }

    private fun embedOne(text: String): FloatArray {
        ensureSession()
        val readLock = sessionLock.readLock()
        readLock.lock()
        try {
            // Re-checked under the read lock: a close() that ran between the
            // volatile read above and this point has already set the flag, so the
            // caller is told instead of handed a closed native session.
            if (closed) throw UnavailableException("ONNX embedder is closed")
            val session = checkNotNull(session) { "ONNX session is unavailable" }
            return embedOneWithSession(session, text)
        } finally {
            readLock.unlock()
        }
    }

    private fun embedOneWithSession(
        session: Any,
        text: String
    ): FloatArray {
        val ids = tokenizer.encode(text, maxLength)
        val mask = LongArray(ids.size) { 1L }
        val shape = longArrayOf(1, ids.size.toLong())

        val inputIds = createTensorMethod.invoke(null, environment, LongBuffer.wrap(ids), shape)
        val attentionMask = createTensorMethod.invoke(null, environment, LongBuffer.wrap(mask), shape)
        val tokenTypeIds = createTensorMethod.invoke(null, environment, LongBuffer.wrap(LongArray(ids.size)), shape)

        try {
            @Suppress("UNCHECKED_CAST")
            val inputNames = method(session.javaClass, "getInputNames").invoke(session) as Set<String>
            val inputs = buildInputs(inputNames, inputIds, attentionMask, tokenTypeIds)

            val result = method(session.javaClass, "run", Map::class.java).invoke(session, inputs)
            try {
                val tensor = method(result.javaClass, "get", Int::class.javaPrimitiveType!!).invoke(result, 0)
                return pool(tensor)
            } finally {
                closeQuietly(result)
            }
        } finally {
            closeQuietly(inputIds)
            closeQuietly(attentionMask)
            closeQuietly(tokenTypeIds)
        }
    }

    /**
     * Creates the native session on first use. The hot path is a volatile read;
     * the write lock is only taken for the actual creation, and a close racing a
     * caller is caught by the read-lock re-check in [embedOne].
     */
    private fun ensureSession() {
        if (session != null) return
        val writeLock = sessionLock.writeLock()
        writeLock.lock()
        try {
            if (closed) throw UnavailableException("ONNX embedder is closed")
            if (session != null) return
            val options = sessionOptionsConstructor.newInstance()
            setIntraOpThreadsMethod.invoke(options, intraOpThreads)
            session = createSessionMethod.invoke(environment, modelPath.toString(), options)
        } finally {
            writeLock.unlock()
        }
    }

    private fun buildInputs(
        inputNames: Set<String>,
        inputIds: Any,
        attentionMask: Any,
        tokenTypeIds: Any
    ): Map<String, Any> {
        val idsName = idsInputName(inputNames)
        val inputs = linkedMapOf<String, Any>()
        inputs[idsName] = inputIds
        if ("attention_mask" in inputNames) inputs["attention_mask"] = attentionMask
        if ("token_type_ids" in inputNames) inputs["token_type_ids"] = tokenTypeIds
        return inputs
    }

    private fun pool(tensor: Any): FloatArray {
        val info = method(tensor.javaClass, "getInfo").invoke(tensor)
        val shape = method(info.javaClass, "getShape").invoke(info) as LongArray
        val value = method(tensor.javaClass, "getValue").invoke(tensor)
        return poolEmbedding(shape, value, dimensions)
    }

    override fun close() {
        val writeLock = sessionLock.writeLock()
        writeLock.lock()
        try {
            closed = true
            val current = session
            session = null
            closeQuietly(current)
        } finally {
            writeLock.unlock()
        }
    }

    private fun closeQuietly(target: Any?) {
        if (target == null) return
        runCatching { method(target.javaClass, "close").invoke(target) }
    }
}

internal suspend fun <T> retrySafeEmbedderRead(
    policy: RetryPolicy,
    operation: suspend () -> T
): T =
    retry(policy) {
        try {
            operation()
        } catch (error: InvocationTargetException) {
            throw embedderFailure(error)
        } catch (error: IOException) {
            throw UnavailableException("ONNX embedding read failed", error)
        } catch (error: TimeoutException) {
            throw UnavailableException("ONNX embedding read timed out", error)
        }
    }

private fun embedderFailure(error: InvocationTargetException): Throwable {
    val cause = error.cause ?: error
    return when (cause) {
        is CancellationException -> cause
        is IOException -> UnavailableException("ONNX embedding read failed", cause)
        is TimeoutException -> UnavailableException("ONNX embedding read timed out", cause)
        else -> cause
    }
}

internal fun idsInputName(inputNames: Set<String>): String {
    require(inputNames.isNotEmpty()) { "model has no inputs" }
    val idsName =
        when {
            "input_ids" in inputNames -> "input_ids"
            inputNames.size == 1 -> inputNames.first()
            else -> error("model requires an unsupported token input")
        }
    require(inputNames.all { it == idsName || it == "attention_mask" || it == "token_type_ids" }) {
        "model has unsupported inputs: ${inputNames - setOf(idsName, "attention_mask", "token_type_ids")}"
    }
    return idsName
}

internal fun poolEmbedding(shape: LongArray, value: Any, expectedDimensions: Int): FloatArray {
    require(expectedDimensions > 0) { "model dimensions must be positive" }
    val vector =
        when (shape.size) {
            1 -> {
                require(shape[0] == expectedDimensions.toLong()) { "model returned ${shape[0]} dimensions" }
                value as FloatArray
            }

            2 -> {
                require(shape[0] == 1L && shape[1] == expectedDimensions.toLong()) {
                    "model returned shape ${shape.toList()}"
                }
                val batch = value as? Array<*> ?: error("model returned a non-array batch")
                require(batch.size == 1) { "model returned ${batch.size} batches" }
                batch.single() as FloatArray
            }

            3 -> {
                require(shape[0] == 1L && shape[1] > 0L && shape[2] == expectedDimensions.toLong()) {
                    "model returned shape ${shape.toList()}"
                }
                val batch = value as? Array<*> ?: error("model returned a non-array batch")
                require(batch.size == 1) { "model returned ${batch.size} batches" }
                val rows = batch.single() as? Array<*> ?: error("model returned a non-array sequence")
                require(rows.isNotEmpty()) { "model returned an empty sequence" }
                val rowVectors = rows.map { it as? FloatArray ?: error("model returned a non-vector token") }
                require(rowVectors.all { it.size == expectedDimensions }) { "model returned inconsistent dimensions" }
                val pooled = FloatArray(expectedDimensions)
                rowVectors.forEach { row -> row.forEachIndexed { index, value -> pooled[index] += value } }
                FloatArray(expectedDimensions) { pooled[it] / rowVectors.size }
            }

            else -> {
                error("model returned unsupported rank ${shape.size}")
            }
        }
    require(vector.size == expectedDimensions) { "model returned ${vector.size} dimensions" }
    require(vector.all(Float::isFinite)) { "model returned a non-finite embedding" }
    return vector
}
