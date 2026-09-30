package com.arm.aichat.internal

import android.content.Context
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.InferenceOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Owns one native model. The mutex also protects operations across suspended Flow emissions. */
internal class InferenceEngineImpl private constructor(nativeLibDir: String) : InferenceEngine {
    companion object {
        private const val TAG = "PocketAI.Engine"
        @Volatile private var instance: InferenceEngineImpl? = null

        internal fun getInstance(context: Context): InferenceEngine = synchronized(this) {
            val current = instance
            if (current != null) {
                check(!current.closing && !current.destroyed) { "Previous inference engine is closing; retry shortly" }
                current
            } else InferenceEngineImpl(context.applicationInfo.nativeLibraryDir).also { instance = it }
        }
    }

    private external fun init(nativeLibDir: String)
    private external fun configureNative(threads: Int, contextSize: Int, batchSize: Int, gpuLayers: Int, temperature: Float)
    private external fun load(modelPath: String): Int
    private external fun prepare(): Int
    private external fun nativeDiagnostics(): String
    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String
    private external fun processSystemPrompt(systemPrompt: String): Int
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun finishGeneration()
    private external fun requestCancel()
    private external fun resetCancellation()
    private external fun setThreadLimitNative(threads: Int)
    private external fun unload()
    private external fun shutdown()

    private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val mutex = Mutex()
    private val nativeControlLock = Any()
    @Volatile private var nativeLoaded = false
    @Volatile private var cancelled = false
    @Volatile private var destroyed = false
    @Volatile private var closing = false
    @Volatile private var thermalThreadLimit = 32
    private var modelLoaded = false

    private val initialization = scope.async {
        try {
            _state.value = InferenceEngine.State.Initializing
            System.loadLibrary("ai-chat")
            init(nativeLibDir)
            synchronized(nativeControlLock) { nativeLoaded = true }
            setThreadLimitNative(thermalThreadLimit)
            _state.value = InferenceEngine.State.Initialized
            Log.i(TAG, "Native inference initialized")
        } catch (e: Throwable) {
            val error = IOException("Native inference initialization failed", e)
            _state.value = InferenceEngine.State.Error(error)
            throw error
        }
    }

    private suspend fun awaitInitialization() {
        check(!closing && !destroyed) { "Inference engine is closing or has been destroyed" }
        initialization.await()
    }

    override suspend fun configure(options: InferenceOptions): Unit = withContext<Unit>(dispatcher) {
        awaitInitialization()
        mutex.withLock {
            check(!closing && !destroyed) { "Inference engine is closing or has been destroyed" }
            check(!modelLoaded) { "Unload the model before changing inference options" }
            configureNative(options.threads, options.contextSize, options.batchSize, options.gpuLayers, options.temperature)
            setThreadLimitNative(thermalThreadLimit)
            _state.value = InferenceEngine.State.Initialized
        }
    }

    override suspend fun diagnostics(): String = withContext(dispatcher) {
        awaitInitialization()
        mutex.withLock {
            check(!closing && !destroyed) { "Inference engine is closing or has been destroyed" }
            nativeDiagnostics()
        }
    }

    override fun cancelGeneration() {
        cancelled = true
        synchronized(nativeControlLock) { if (nativeLoaded) requestCancel() }
    }

    override fun setThreadLimit(threads: Int) {
        require(threads in 1..32) { "Thread limit must be between 1 and 32" }
        thermalThreadLimit = threads
        synchronized(nativeControlLock) { if (nativeLoaded && !closing) setThreadLimitNative(threads) }
    }

    private fun startOperation() {
        cancelled = false
        resetCancellation()
    }

    override suspend fun loadModel(pathToModel: String): Unit = withContext<Unit>(dispatcher) {
        awaitInitialization()
        mutex.withLock {
            check(!closing && !destroyed) { "Inference engine is closing or has been destroyed" }
            check(!modelLoaded) { "Unload the current model first" }
            val file = File(pathToModel)
            require(file.isFile && file.canRead()) { "The model file cannot be read" }
            startOperation()
            _state.value = InferenceEngine.State.LoadingModel
            try {
                if (load(pathToModel) != 0) {
                    if (cancelled) throw CancellationException("Model loading cancelled")
                    throw IOException("Model loading failed: invalid GGUF, unsupported architecture, or insufficient memory")
                }
                currentCoroutineContext().ensureActive()
                if (cancelled) throw CancellationException("Model loading cancelled")
                val prepareResult = prepare()
                if (cancelled) throw CancellationException("Model preparation cancelled")
                if (prepareResult != 0) throw IOException("Model context allocation failed; try a smaller context or CPU mode")
                currentCoroutineContext().ensureActive()
                if (cancelled) throw CancellationException("Model loading cancelled")
                modelLoaded = true
                _state.value = InferenceEngine.State.ModelReady
                Log.i(TAG, "Model ready")
            } catch (e: Throwable) {
                unload()
                modelLoaded = false
                _state.value = if (e is CancellationException) InferenceEngine.State.Initialized
                    else InferenceEngine.State.Error(asException(e))
                throw e
            }
        }
    }

    override suspend fun setSystemPrompt(systemPrompt: String): Unit = withContext<Unit>(dispatcher) {
        awaitInitialization()
        mutex.withLock {
            check(!closing && !destroyed) { "Inference engine is closing or has been destroyed" }
            require(systemPrompt.isNotBlank()) { "System prompt must not be empty" }
            check(modelLoaded && _state.value is InferenceEngine.State.ModelReady) { "Model is not ready" }
            startOperation()
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            try {
                val result = processSystemPrompt(systemPrompt)
                currentCoroutineContext().ensureActive()
                if (cancelled) throw CancellationException("Prompt processing cancelled")
                if (result != 0) throw IOException("System prompt processing failed ($result); reduce its size")
            } finally {
                _state.value = InferenceEngine.State.ModelReady
            }
        }
    }

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
        awaitInitialization()
        require(message.isNotBlank()) { "Message must not be empty" }
        require(predictLength in 1..32768) { "Generation limit must be between 1 and 32768 tokens" }
        mutex.withLock {
            check(!closing && !destroyed) { "Inference engine is closing or has been destroyed" }
            check(modelLoaded && _state.value is InferenceEngine.State.ModelReady) { "Model is not ready" }
            startOperation()
            try {
                _state.value = InferenceEngine.State.ProcessingUserPrompt
                val result = processUserPrompt(message, predictLength)
                currentCoroutineContext().ensureActive()
                if (cancelled) return@withLock
                if (result != 0) throw IOException("Prompt processing failed ($result); reduce its size or reset the conversation")
                _state.value = InferenceEngine.State.Generating
                while (!cancelled) {
                    currentCoroutineContext().ensureActive()
                    val token = generateNextToken() ?: break
                    if (token.isNotEmpty()) emit(token)
                }
            } catch (e: CancellationException) {
                cancelGeneration()
                throw e
            } finally {
                finishGeneration()
                _state.value = InferenceEngine.State.ModelReady
                Log.i(TAG, if (cancelled) "Generation cancelled" else "Generation finished")
            }
        }
    }.flowOn(dispatcher)

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String = withContext(dispatcher) {
        awaitInitialization()
        require(pp in 1..32768 && tg in 1..4096 && pl == 1 && nr in 1..5) { "Benchmark requires one sequence and bounded token/repetition counts" }
        mutex.withLock {
            check(!closing && !destroyed) { "Inference engine is closing or has been destroyed" }
            check(modelLoaded && _state.value is InferenceEngine.State.ModelReady) { "Model is not ready" }
            startOperation()
            _state.value = InferenceEngine.State.Benchmarking
            try { benchModel(pp, tg, pl, nr) }
            finally { _state.value = InferenceEngine.State.ModelReady }
        }
    }

    override suspend fun cleanUp() {
        cancelGeneration()
        withContext(NonCancellable + dispatcher) {
            awaitInitialization()
            mutex.withLock {
                check(!closing && !destroyed) { "Inference engine is closing or has been destroyed" }
                _state.value = InferenceEngine.State.UnloadingModel
                unload()
                modelLoaded = false
                _state.value = InferenceEngine.State.Initialized
                Log.i(TAG, "Model unloaded")
            }
        }
    }

    override suspend fun destroy() {
        closing = true
        cancelGeneration()
        withContext(NonCancellable + dispatcher) {
            try { initialization.await() } catch (_: Exception) { }
            mutex.withLock {
                val loaded = synchronized(nativeControlLock) {
                    nativeLoaded.also { nativeLoaded = false }
                }
                if (!destroyed && loaded) {
                    unload()
                    shutdown()
                }
                modelLoaded = false
                destroyed = true
                _state.value = InferenceEngine.State.Uninitialized
                synchronized(Companion) { if (instance === this@InferenceEngineImpl) instance = null }
            }
        }
        scope.cancel()
    }

    private fun asException(error: Throwable): Exception =
        error as? Exception ?: IOException("Native inference failed", error)
}
