package com.vis.expressivekeyboard.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.vis.expressivekeyboard.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val sizeBytes: Long,
    val fileName: String,
)

object ModelCatalog {
    private const val HF = "https://huggingface.co/litert-community"
    val models = listOf(
        ModelInfo(
            "qwen3.5-0.8b", "Qwen3.5 0.8B", "Recommended · the smallest model that corrects reliably in all 6 languages",
            "$HF/Qwen3.5-0.8B/resolve/main/Qwen3.5-0.8B_int8.litertlm", 964_566_992, "Qwen3.5-0.8B_int8.litertlm",
        ),
        ModelInfo(
            "lfm2.5-350m", "LFM2.5 350M", "Tiny & fast, but too small for reliable grammar fixes",
            "$HF/LFM2.5-350M/resolve/main/LFM2.5-350M_int4.litertlm", 247_747_696, "LFM2.5-350M_int4.litertlm",
        ),
        ModelInfo(
            "gemma4-e2b", "Gemma 4 E2B", "Best quality · 140 languages · needs 8 GB+ RAM",
            "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", 2_588_147_712, "gemma-4-E2B-it.litertlm",
        ),
    )
}

enum class AiTask(val label: String, val instruction: String) {
    FIX(
        "Fix",
        "You are a proofreader. Correct spelling, grammar, punctuation, accents and capitalization of the user's message. " +
            "ALWAYS answer in the same language as the message - never translate. Keep the original meaning, wording, tone and slang; " +
            "change as little as possible. If the message is already correct, repeat it unchanged. " +
            "Reply with ONLY the corrected message, no quotes, no explanations.",
    ),
    FORMAL("Formal", "Rewrite the user's message in a polite, professional tone, in the same language as the message (never translate). Reply with ONLY the rewritten message."),
    FRIENDLY("Friendly", "Rewrite the user's message in a warm, casual, friendly tone, in the same language as the message (never translate). Reply with ONLY the rewritten message."),
    SHORTER("Shorter", "Rewrite the user's message to be shorter and more concise, keeping the meaning and the same language (never translate). Reply with ONLY the rewritten message."),
    EXPAND("Elaborate", "Rewrite the user's message with a little more detail, keeping the meaning, tone and the same language (never translate). Reply with ONLY the rewritten message."),
    EMOJIFY("Emojify", "Add a few fitting emojis to the user's message without changing its words. Reply with ONLY the resulting message."),
}

enum class AiState { NO_MODEL, DOWNLOADING, IMPORTING, IDLE, LOADING, READY, ERROR }

/** On-device LLM via Google LiteRT-LM (.litertlm models). The runtime applies each model's own chat template. */
object AiEngine {
    private const val TAG = "AiEngine"
    private lateinit var ctx: Context
    val state = mutableStateOf(AiState.NO_MODEL)
    val message = mutableStateOf("")
    val progress = mutableFloatStateOf(0f)
    /** Last ~60 diagnostic lines, shown in AI settings so problems can be reported. */
    val diag = androidx.compose.runtime.mutableStateListOf<String>()
    private fun log(msg: String) {
        Log.i(TAG, msg)
        val line = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date()) + "  " + msg
        main.post { diag.add(line); while (diag.size > 60) diag.removeAt(0) }
    }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Raw output of the last request (shown in settings when a result is rejected). */
    val lastRaw = mutableStateOf("")

    private fun newDispatcher() = Executors.newSingleThreadExecutor { r -> Thread(r, "llm") }.asCoroutineDispatcher()
    @Volatile private var dispatcher = newDispatcher()
    /** Incremented whenever a stuck engine is abandoned, so its late completion can't clear [busy]. */
    @Volatile private var runGen = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** Long-lived scope for callers (UI) to run AI work in, so recomposition can't cancel it. */
    val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: Engine? = null
    private var loadedPath: String? = null
    private var unloadJob: Job? = null
    private var pollJob: Job? = null

    fun init(c: Context) {
        ctx = c.applicationContext
        // models for the previous runtime (.task) can't be used any more: free the space
        modelsDir().listFiles { f -> f.name.endsWith(".task") || f.name.endsWith(".task.part") }?.forEach { it.delete() }
        if (!Prefs.modelPath.value.endsWith(".litertlm")) Prefs.modelPath.value = installedModels().firstOrNull()?.absolutePath ?: ""
        refreshState()
        val pending = Prefs.raw().getLong("download_id", -1L)
        if (pending >= 0) pollDownload(pending, Prefs.raw().getString("download_model", "") ?: "")
    }

    fun modelsDir(): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }

    /** LiteRT-LM only ships 64-bit libraries, so 32-bit installs run without AI. */
    val supported: Boolean by lazy { File(ctx.applicationInfo.nativeLibraryDir, "liblitertlm_jni.so").exists() }

    fun hasModel(): Boolean {
        if (!supported) return false
        val p = Prefs.modelPath.value
        return p.endsWith(".litertlm") && File(p).exists()
    }

    fun installedModels(): List<File> = modelsDir().listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }?.sortedBy { it.name } ?: emptyList()

    fun refreshState() {
        if (state.value == AiState.DOWNLOADING || state.value == AiState.IMPORTING || state.value == AiState.LOADING) return
        state.value = when {
            !hasModel() -> AiState.NO_MODEL
            engine != null -> AiState.READY
            else -> AiState.IDLE
        }
    }

    fun modelDisplayName(): String {
        val p = Prefs.modelPath.value
        if (!hasModel()) return "No model"
        val f = File(p).name
        return ModelCatalog.models.firstOrNull { it.fileName == f }?.name ?: f.removeSuffix(".litertlm")
    }

    // ---------------------------------------------------------------- downloads

    fun startDownload(model: ModelInfo) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        File(modelsDir(), model.fileName + ".part").delete()
        val req = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Expressive Keyboard AI model")
            .setDescription(model.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, "models", model.fileName + ".part")
        val id = dm.enqueue(req)
        Prefs.raw().edit().putLong("download_id", id).putString("download_model", model.id).apply()
        pollDownload(id, model.id)
    }

    fun cancelDownload() {
        val id = Prefs.raw().getLong("download_id", -1L)
        if (id >= 0) ctx.getSystemService(DownloadManager::class.java).remove(id)
        Prefs.raw().edit().remove("download_id").apply()
        pollJob?.cancel()
        state.value = AiState.NO_MODEL
        refreshState()
    }

    private fun pollDownload(id: Long, modelId: String) {
        val model = ModelCatalog.models.firstOrNull { it.id == modelId } ?: return
        val dm = ctx.getSystemService(DownloadManager::class.java)
        state.value = AiState.DOWNLOADING
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                var status = -1; var done = 0L; var total = model.sizeBytes; var reason = 0
                dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (c.moveToFirst()) {
                        status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).let { if (it > 0) total = it }
                        reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    }
                }
                progress.floatValue = (done.toFloat() / total).coerceIn(0f, 1f)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        delay(1500) // let DownloadManager finish writing
                        val part = File(modelsDir(), model.fileName + ".part")
                        val dest = File(modelsDir(), model.fileName)
                        dest.delete()
                        if (part.renameTo(dest)) {
                            Prefs.modelPath.value = dest.absolutePath
                            Prefs.modelId.value = model.id
                            message.value = "${model.name} ready"
                        } else message.value = "Could not save the model file"
                        Prefs.raw().edit().remove("download_id").apply()
                        state.value = AiState.NO_MODEL
                        refreshState()
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED, -1 -> {
                        Prefs.raw().edit().remove("download_id").apply()
                        state.value = AiState.ERROR
                        message.value = if (status == -1) "Download cancelled" else "Download failed (code $reason)"
                        delay(2500); state.value = AiState.NO_MODEL; refreshState()
                        return@launch
                    }
                }
                delay(500)
            }
        }
    }

    /** Copy a user-picked .litertlm model into app storage. */
    fun importModel(uri: Uri) {
        state.value = AiState.IMPORTING
        progress.floatValue = 0f
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var name = "imported.litertlm"; var size = -1L
                    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                        }
                    }
                    require(name.endsWith(".litertlm")) { "Please pick a .litertlm model file" }
                    val dest = File(modelsDir(), name)
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        dest.outputStream().use { out ->
                            val buf = ByteArray(1 shl 20); var copied = 0L
                            while (true) {
                                val r = input.read(buf); if (r < 0) break
                                out.write(buf, 0, r); copied += r
                                if (size > 0) progress.floatValue = copied.toFloat() / size
                            }
                        }
                    }
                    dest
                }
            }
            state.value = AiState.NO_MODEL
            result.onSuccess { Prefs.modelPath.value = it.absolutePath; Prefs.modelId.value = "custom"; message.value = "Imported ${it.name}" }
                .onFailure { message.value = "Import failed: ${it.message}" }
            refreshState()
        }
    }

    fun selectModel(file: File) {
        Prefs.modelPath.value = file.absolutePath
        Prefs.modelId.value = ModelCatalog.models.firstOrNull { it.fileName == file.name }?.id ?: "custom"
        reset()
    }

    /** Drops the current engine right now (used when the backend or model changes, or from the Reset button). */
    fun reset() {
        log("reset requested")
        if (busy.get()) abandonStuckEngine() else scope.launch { unload() }
        state.value = AiState.NO_MODEL
        refreshState()
    }

    fun deleteModel(file: File) {
        scope.launch {
            if (loadedPath == file.absolutePath) unload()
            file.delete()
            if (Prefs.modelPath.value == file.absolutePath) Prefs.modelPath.value = installedModels().firstOrNull()?.absolutePath ?: ""
            refreshState()
        }
    }

    // ---------------------------------------------------------------- inference

    /** True while a request is running; new requests are refused instead of queueing up. */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var activeConv: Conversation? = null

    private fun cpuThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    private fun loadLocked(): Engine {
        val path = Prefs.modelPath.value
        engine?.let { if (loadedPath == path) return it }
        closeLocked()
        if (!hasModel()) throw IllegalStateException("No AI model installed")
        scope.launch { state.value = AiState.LOADING }
        val gpu = Prefs.aiBackend.value == "gpu"
        val f = File(path)
        log("load start: ${f.name} (${f.length() / 1_000_000} MB, readable=${f.canRead()}) backend=${if (gpu) "GPU" else "CPU x${cpuThreads()}"} " +
            "device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} api=${android.os.Build.VERSION.SDK_INT}")
        fun create(b: Backend): Engine {
            val t0 = System.currentTimeMillis()
            val eng = Engine(EngineConfig(modelPath = path, backend = b, maxNumTokens = 1024, cacheDir = ctx.cacheDir.path))
            log("engine created (${b.name}) in ${System.currentTimeMillis() - t0} ms, initializing…")
            eng.initialize()
            log("initialize done in ${System.currentTimeMillis() - t0} ms")
            return eng
        }
        val e = try {
            create(if (gpu) Backend.GPU() else Backend.CPU(threadCount = cpuThreads()))
        } catch (t: Throwable) {
            log("load failed: ${t.javaClass.simpleName}: ${t.message}")
            if (gpu) { Prefs.aiBackend.value = "cpu"; log("falling back to CPU"); create(Backend.CPU(threadCount = cpuThreads())) } else throw t
        }
        engine = e
        loadedPath = path
        scope.launch { state.value = AiState.READY }
        return e
    }

    private fun closeLocked() {
        runCatching { engine?.close() }
        engine = null; loadedPath = null
    }

    /** Correction examples per language; only the detected language's examples are shown to the model. */
    private val fixExamples: Map<String, List<Pair<String, String>>> = mapOf(
        "en" to listOf(
            "i dont no what your talking about" to "I don't know what you're talking about",
            "me and him goes to the store yesterday" to "He and I went to the store yesterday",
        ),
        "it" to listOf(
            "perche non sei venuto ieri sera alla festa" to "Perché non sei venuto ieri sera alla festa?",
            "ieri sono andato al mare con i mio amici" to "Ieri sono andato al mare con i miei amici",
        ),
        "es" to listOf(
            "ayer fuimos al cine y la pelicula fue muy buena" to "Ayer fuimos al cine y la película fue muy buena.",
            "mañana voy a ir a la playa con mis amigo" to "Mañana voy a ir a la playa con mis amigos",
        ),
        "fr" to listOf(
            "je suis aller au marche hier avec mon amis" to "Je suis allé au marché hier avec mon ami",
        ),
        "de" to listOf(
            "ich habe gestern ein buch gekauft das sehr interesant ist" to "Ich habe gestern ein Buch gekauft, das sehr interessant ist.",
        ),
        "pt" to listOf(
            "ontem eu fui no cinema com meus amigo" to "Ontem eu fui ao cinema com meus amigos",
        ),
    )
    private val langNames = mapOf("en" to "English", "it" to "Italian", "es" to "Spanish", "fr" to "French", "de" to "German", "pt" to "Portuguese")

    private fun detectLanguage(text: String): String? = com.vis.expressivekeyboard.engine.Dictionary.detectLanguage(text)

    /**
     * Runs one request in a fresh conversation (synchronous, output capped so it always ends).
     * Called on the llm thread only.
     */
    private fun runOnce(system: String, ex: List<Pair<String, String>>, text: String, maxOut: Int): String {
        val e = loadLocked()
        val conv = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                initialMessages = ex.flatMap { (u, a) -> listOf(Message.user(u), Message.model(a)) },
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                extraContext = mapOf("enable_thinking" to false),
                thinkingConfig = ThinkingConfig(enableThinking = false),
                maxOutputToken = maxOut,
            ),
        )
        activeConv = conv
        try {
            return conv.sendMessage(text).toString()
        } finally {
            activeConv = null
            runCatching { conv.close() }
        }
    }

    /**
     * Executes [block] on the llm thread with a hard deadline. On timeout the native generation is
     * cancelled and null is returned immediately, so the UI never spins forever.
     */
    private suspend fun <T> guarded(timeoutMs: Long, block: () -> T): T? = withContext(kotlinx.coroutines.NonCancellable) { guardedInner(timeoutMs, block) }

    private suspend fun <T> guardedInner(timeoutMs: Long, block: () -> T): T? {
        if (!busy.compareAndSet(false, true)) {
            message.value = "AI is still finishing the previous request"
            return null
        }
        val gen = runGen
        val job = scope.async(dispatcher) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                block()
            } finally {
                if (gen == runGen) busy.set(false)
            }
        }
        val loadingExtra = if (engine == null) 120_000L else 0L // loading budget (big models on GPU take a while); a hung load is abandoned after this
        val result = runCatching { kotlinx.coroutines.withTimeoutOrNull(timeoutMs + loadingExtra) { job.await() } }
        result.exceptionOrNull()?.let { t ->
            Log.e(TAG, "inference failed", t)
            message.value = "AI error: ${t.message ?: t.javaClass.simpleName}"
            refreshState()
            return null
        }
        val v = result.getOrNull()
        if (v == null && !job.isCompleted) {
            // ask the native side to stop, give it a moment, then abandon it if it won't
            runCatching { activeConv?.cancelProcess() }
            val finished = kotlinx.coroutines.withTimeoutOrNull(3_000) { runCatching { job.await() }; true } == true
            if (!finished) abandonStuckEngine()
            else message.value = "The AI took too long and was stopped."
            log("request timed out after ${timeoutMs + loadingExtra} ms (stopped on cancel: $finished)")
        }
        return v
    }

    /**
     * The native call never returned (seen with the GPU backend on some phones). We can't kill it,
     * so we leave that thread and engine behind, start fresh, and fall back to CPU.
     */
    private fun abandonStuckEngine() {
        log("abandoning stuck engine (backend=${Prefs.aiBackend.value})")
        runGen++
        dispatcher = newDispatcher()
        engine = null; loadedPath = null; activeConv = null
        busy.set(false)
        val wasGpu = Prefs.aiBackend.value == "gpu"
        if (wasGpu) Prefs.aiBackend.value = "cpu"
        message.value = if (wasGpu) "The GPU stopped responding, switched the AI to CPU. Try again."
        else "The AI stopped responding and was reset. Try again."
        state.value = AiState.ERROR
        scope.launch { delay(2500); refreshState() }
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch { delay(2 * 60_000L); unload() }
    }

    suspend fun unload() {
        if (busy.get()) { scheduleUnload(); return }
        val d = dispatcher
        kotlinx.coroutines.withTimeoutOrNull(10_000) { withContext(d) { closeLocked() } } ?: abandonStuckEngine()
        refreshState()
    }

    /** Warm the model up in the background so the first correction is fast. */
    fun preload() {
        if (!hasModel() || !Prefs.aiEnabled.value || busy.get()) return
        scope.launch {
            guarded(90_000) { loadLocked(); Unit }
            scheduleUnload()
        }
    }

    /** Rewrites [text] for [task]. Returns null if unavailable or the output was rejected (see [lastRaw]/[message]). */
    suspend fun process(text: String, task: AiTask): String? {
        if (!hasModel() || text.isBlank()) return null
        val input = text.trim()
        message.value = ""
        val maxOut = (input.length / 2 + 48).coerceIn(48, 320)
        val lang = detectLanguage(input)
        log("request[$task] lang=${lang ?: "?"}: ${input.length} chars")
        val t0 = System.currentTimeMillis()
        val system = task.instruction
        val ex = when {
            task != AiTask.FIX -> emptyList()
            lang != null -> fixExamples[lang].orEmpty()
            // unsure: examples from every active language (never from a language you don't use)
            else -> com.vis.expressivekeyboard.engine.Dictionary.activeCodes().flatMap { fixExamples[it].orEmpty().take(1) }
        }
        val raw = guarded(25_000) { runOnce(system, ex, input, maxOut) }
        log("request done in ${System.currentTimeMillis() - t0} ms: ${raw?.let { "${it.length} chars" } ?: "null (${message.value})"}")
        scheduleUnload()
        refreshState()
        if (raw == null) return null
        lastRaw.value = raw
        return clean(raw, input, task)
    }

    private fun clean(raw: String, input: String, task: AiTask): String? {
        var s = raw.replace(Regex("(?s)<think>.*?</think>"), "").replace("<think>", "").replace("</think>", "")
        for (stop in listOf("<|im_end|>", "<end_of_turn>", "<|eot_id|>", "<|end|>", "<|im_start|>")) {
            val i = s.indexOf(stop); if (i >= 0) s = s.substring(0, i)
        }
        s = s.trim()
        if (!input.contains('\n')) s = s.split("\n\n").first().trim()
        s = s.replace(Regex("^(Corrected( text| message)?|Rewritten( text| message)?|Output|Here is[^:]*|Testo corretto|Corretto)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        if (!input.startsWith("\"") && s.length > 2 && s.first() in "\"“«" && s.last() in "\"”»") s = s.substring(1, s.length - 1).trim()
        if (s.isEmpty()) { message.value = "The model returned an empty answer"; return null }
        if (task == AiTask.FIX) {
            val ratio = s.length.toFloat() / input.length.coerceAtLeast(1)
            if (input.length > 12 && (ratio < 0.5f || ratio > 1.8f)) { message.value = "Rejected: the model rewrote too much"; return null }
            if (overlap(input, s) < 0.5f) { message.value = "Rejected: the model changed the meaning"; return null }
            // keep a chatty, punctuation-free style if the user wrote that way
            if (!input.endsWith(".") && s.endsWith(".") && !s.dropLast(1).contains(Regex("[.!?]")) && input.length < 60) s = s.dropLast(1)
            s = dictionaryPass(s, input)
        }
        return s
    }

    /**
     * Small models sometimes emit non-words ("buonsimo", "Máñana"). Any word the keyboard's
     * dictionaries for your languages don't know is spell-corrected with the dictionary engine.
     */
    private fun dictionaryPass(text: String, input: String): String {
        val dict = com.vis.expressivekeyboard.engine.Dictionary
        if (!dict.ready) return text
        val inputWords = words(input).toSet()
        return Regex("[\\p{L}']+").replace(text) { m ->
            val w = m.value
            // only touch words the model invented: never a word you typed (it may be another language)
            if (w.length < 3 || w.lowercase().replace("'", "") in inputWords || dict.isValid(w) || !dict.looksLikeWord(w)) return@replace w
            val r = dict.suggest(w, com.vis.expressivekeyboard.engine.Ctx(null, false))
            val fix = r.autoCorrect ?: r.candidates.firstOrNull { it.kind != com.vis.expressivekeyboard.engine.Suggestion.Kind.COMPLETION && it.dist <= 1.2f }?.word
            // and the fix must stay close to something you actually wrote
            if (fix != null && inputWords.any { lev(it, fix.lowercase().replace("'", "")) <= 2 }) fix else w
        }
    }

    private fun words(t: String) = Regex("[\\p{L}']+").findAll(t.lowercase()).map { it.value.replace("'", "") }.toList()

    private fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    /** Fraction of input words with a close match in the output (guards against hallucinated rewrites). */
    private fun overlap(input: String, output: String): Float {
        val a = words(input); val b = words(output)
        if (a.isEmpty()) return 1f
        val matched = a.count { w -> b.any { o -> o == w || lev(o, w) <= (if (w.length > 5) 2 else 1) } }
        return matched.toFloat() / a.size
    }

    /** Predicts the next word for [context]. Skipped (not queued) if the AI is busy. */
    suspend fun predictNext(context: String): String? {
        if (!hasModel() || context.isBlank() || busy.get()) return null
        val raw = guarded(4_000) {
            runOnce(
                "You are a keyboard autocomplete. Given the beginning of a message, reply with ONLY the single most likely next word, in the same language.",
                emptyList(), context.takeLast(300), 6,
            )
        }
        scheduleUnload()
        val word = raw?.replace(Regex("(?s)<think>.*?</think>"), "")?.trim()?.let { Regex("^[\\p{L}']+").find(it)?.value } ?: return null
        return word.takeIf { it.length in 1..20 }
    }
}
