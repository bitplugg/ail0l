package com.arm.aichat.internal

import android.content.Context
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.UnsupportedArchitectureException
import com.arm.aichat.internal.InferenceEngineImpl.Companion.getInstance
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException






internal class InferenceEngineImpl private constructor(
    private val nativeLibDir: String
) : InferenceEngine {

    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName

        @Volatile
        private var instance: InferenceEngine? = null

        internal fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }

                try {
                    Log.i(TAG, "Instantiating InferenceEngineImpl,,,")
                    InferenceEngineImpl(nativeLibDir).also { instance = it }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load native library from $nativeLibDir", e)
                    throw e
                }
            }
    }

    @FastNative
    private external fun init(nativeLibDir: String)

    @FastNative
    private external fun load(modelPath: String): Int

    @FastNative
    private external fun applyConfig(nCtx: Int, nThreads: Int, flashAttn: Boolean, nBatch: Int)

    @FastNative
    private external fun applySampler(temp: Float, topK: Int, topP: Float): Int

    @FastNative
    private external fun nativeSetLoraAdapter(path: String, scale: Float): Int

    @FastNative
    private external fun nativeSetMmproj(path: String): Int

    @FastNative
    private external fun nativeAnalyzeImage(path: String, prompt: String): String?

    @FastNative
    private external fun nativeSaveContextCache(path: String): Boolean

    @FastNative
    private external fun nativeLoadContextCache(path: String): Boolean

    @FastNative
    private external fun reshapeContext(systemPrompt: String, roles: Array<String>, contents: Array<String>): Int

    @FastNative
    private external fun prepare(): Int

    @FastNative
    private external fun systemInfo(): String

    @FastNative
    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    @FastNative
    private external fun processSystemPrompt(systemPrompt: String): Int

    @FastNative
    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

    @FastNative
    private external fun generateNextToken(): String?

    @FastNative
    private external fun unload()

    @FastNative
    private external fun shutdown()

    private val _state =
        MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private var _readyForSystemPrompt = false
    @Volatile
    private var _cancelGeneration = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        startInit()
    }

    private fun startInit() {
        llamaScope.launch {
            try {
                check(_state.value is InferenceEngine.State.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = InferenceEngine.State.Initializing
                Log.i(TAG, "Loading native library...")
                System.loadLibrary("aiia-llama")
                init(nativeLibDir)
                _state.value = InferenceEngine.State.Initialized
                Log.i(TAG, "Native library loaded! System info: \n${systemInfo()}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                _state.value = InferenceEngine.State.Error(e)
            }
        }
    }

    override suspend fun ensureInitialized() = withContext(llamaDispatcher) {
        when {
            _state.value is InferenceEngine.State.Initialized ||
                _state.value is InferenceEngine.State.ModelReady -> return@withContext
            _state.value is InferenceEngine.State.Initializing -> {

                while (_state.value is InferenceEngine.State.Initializing) delay(50)
                if (_state.value is InferenceEngine.State.Initialized) return@withContext
            }
            _state.value is InferenceEngine.State.Uninitialized -> {

                delay(50)
                while (_state.value is InferenceEngine.State.Initializing) delay(50)
                if (_state.value is InferenceEngine.State.Initialized) return@withContext
            }
            else -> {  }
        }

        _state.value = InferenceEngine.State.Uninitialized
        startInit()
        while (_state.value is InferenceEngine.State.Initializing) delay(50)
        check(_state.value is InferenceEngine.State.Initialized) {
            "cannot load model in error!"
        }
    }

    override suspend fun loadModel(pathToModel: String) =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }

            try {
                Log.i(TAG, "Checking access to model file... \n$pathToModel")
                File(pathToModel).let {
                    require(it.exists()) { "File not found" }
                    require(it.isFile) { "Not a valid file" }
                    require(it.canRead()) { "Cannot read file" }
                }

                Log.i(TAG, "Loading model... \n$pathToModel")
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel
                load(pathToModel).let {
                    if (it != 0) throw UnsupportedArchitectureException()
                }
                prepare().let {
                    if (it != 0) throw IOException("Failed to prepare resources")
                }
                Log.i(TAG, "Model loaded!")
                _readyForSystemPrompt = true

                _cancelGeneration = false
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, (e.message ?: "Error loading model") + "\n" + pathToModel, e)
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }

    override suspend fun configure(nCtx: Int, nThreads: Int, flashAttn: Boolean, nBatch: Int) =
        withContext(llamaDispatcher) {
            applyConfig(
                nCtx.coerceAtLeast(128),
                nThreads.coerceAtLeast(-1),
                flashAttn,
                nBatch.coerceAtLeast(64)
            )
        }

    override suspend fun setSamplerParams(temp: Float, topK: Int, topP: Float) =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Sampler params require a loaded model (${_state.value.javaClass.simpleName})"
            }
            val rc = applySampler(temp, topK, topP)
            if (rc != 0) {
                RuntimeException("Failed to apply sampler params: $rc")
            }
        }

    override suspend fun setLoraAdapter(path: String, scale: Float): Unit = withContext(llamaDispatcher) {
        check(_state.value is InferenceEngine.State.ModelReady) {
            "LoRA adapter requires a loaded model (${_state.value.javaClass.simpleName})"
        }
        if (path.isBlank()) {
            nativeSetLoraAdapter("", 0.0f)
        } else {
            require(File(path).isFile) { "LoRA file not found: $path" }
            val rc = nativeSetLoraAdapter(path, scale.coerceIn(0.0f, 2.0f))
            check(rc == 0) { "Failed to apply LoRA adapter: $rc" }
        }
        Unit
    }

    override suspend fun setMmproj(path: String): Unit = withContext(llamaDispatcher) {
        if (path.isBlank()) {
            nativeSetMmproj("")
        } else {
            require(File(path).isFile) { "mmproj file not found: $path" }
            check(nativeSetMmproj(path) == 0) { "Failed to configure mmproj" }
        }
        Unit
    }

    override suspend fun analyzeImage(path: String, prompt: String): String = withContext(llamaDispatcher) {
        require(File(path).isFile) { "Image file not found: $path" }
        nativeAnalyzeImage(path, prompt).orEmpty()
    }

    override suspend fun saveContextCache(path: String): Boolean = withContext(llamaDispatcher) {
        path.isNotBlank() && _state.value is InferenceEngine.State.ModelReady && nativeSaveContextCache(path)
    }

    override suspend fun loadContextCache(path: String): Boolean = withContext(llamaDispatcher) {
        path.isNotBlank() && _state.value is InferenceEngine.State.ModelReady && nativeLoadContextCache(path)
    }

    override suspend fun hydrateContext(systemPrompt: String, roles: List<String>, contents: List<String>) =
        withContext(llamaDispatcher) {
            require(roles.size == contents.size) { "roles/contents length mismatch" }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot hydrate context in ${_state.value.javaClass.simpleName}!"
            }
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            reshapeContext(
                systemPrompt,
                roles.toTypedArray(),
                contents.toTypedArray()
            ).let { result ->
                if (result != 0) {
                    RuntimeException("Failed to reshape context: $result").also {
                        _state.value = InferenceEngine.State.Error(it)
                        throw it
                    }
                }
            }
            Log.i(TAG, "Context hydrated: ${roles.size} messages")
            _state.value = InferenceEngine.State.ModelReady
        }

    override suspend fun setSystemPrompt(prompt: String) =
        withContext(llamaDispatcher) {
            require(prompt.isNotBlank()) { "Cannot process empty system prompt!" }
            check(_readyForSystemPrompt) { "System prompt must be set ** RIGHT AFTER ** model loaded!" }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
            }

            Log.i(TAG, "Sending system prompt...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            processSystemPrompt(prompt).let { result ->
                if (result != 0) {
                    RuntimeException("Failed to process system prompt: $result").also {
                        _state.value = InferenceEngine.State.Error(it)
                        throw it
                    }
                }
            }
            Log.i(TAG, "System prompt processed! Awaiting user prompt...")
            _state.value = InferenceEngine.State.ModelReady
        }

    override fun sendUserPrompt(
        message: String,
        predictLength: Int,
    ): Flow<String> = flow {
        require(message.isNotEmpty()) { "User prompt discarded due to being empty!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        try {
            Log.i(TAG, "Sending user prompt...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt

            processUserPrompt(message, predictLength).let { result ->
                if (result != 0) {
                    Log.e(TAG, "Failed to process user prompt: $result")
                    return@flow
                }
            }

            Log.i(TAG, "User prompt processed. Generating assistant prompt...")
            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { utf8token ->
                    if (utf8token.isNotEmpty()) emit(utf8token)
                } ?: break
            }
            if (_cancelGeneration) {
                Log.i(TAG, "Assistant generation aborted per requested.")
            } else {
                Log.i(TAG, "Assistant generation complete. Awaiting user prompt...")
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Assistant generation's flow collection cancelled.")
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Benchmark request discarded due to: $state"
            }
            Log.i(TAG, "Start benchmark (pp: $pp, tg: $tg, pl: $pl, nr: $nr)")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.Benchmarking
            benchModel(pp, tg, pl, nr).also {
                _state.value = InferenceEngine.State.ModelReady
            }
        }

    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is InferenceEngine.State.ModelReady -> {
                    Log.i(TAG, "Unloading model and free resources...")
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.UnloadingModel

                    unload()

                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "Model unloaded!")
                    Unit
                }

                is InferenceEngine.State.Error -> {
                    Log.i(TAG, "Resetting error states...")
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "States reset!")
                    Unit
                }

                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            when(_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                is InferenceEngine.State.Initialized -> shutdown()
                else -> { unload(); shutdown() }
            }
        }
        llamaScope.cancel()
    }
}
