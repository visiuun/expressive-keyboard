package com.vis.expressivekeyboard.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.vis.expressivekeyboard.data.Prefs
import com.vis.expressivekeyboard.engine.Dictionary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

data class VoiceFile(val url: String, val name: String, val size: Long)

data class VoiceModel(
    val id: String,
    val name: String,
    val description: String,
    val kind: Kind,
    val files: List<VoiceFile>,
) {
    enum class Kind { PARAKEET, WHISPER }
    val sizeBytes get() = files.sumOf { it.size }
}

object VoiceCatalog {
    private const val HF = "https://huggingface.co/csukuangfj"
    val models = listOf(
        VoiceModel(
            "parakeet-v3", "Parakeet TDT 0.6B v3",
            "Best accuracy · 25 languages incl. English & Italian · punctuation built in",
            VoiceModel.Kind.PARAKEET,
            listOf(
                VoiceFile("$HF/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/encoder.int8.onnx", "encoder.int8.onnx", 652_184_281),
                VoiceFile("$HF/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/decoder.int8.onnx", "decoder.int8.onnx", 11_845_275),
                VoiceFile("$HF/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/joiner.int8.onnx", "joiner.int8.onnx", 6_355_277),
                VoiceFile("$HF/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/tokens.txt", "tokens.txt", 93_939),
            ),
        ),
        VoiceModel(
            "whisper-base", "Whisper Base",
            "Good accuracy · 99 languages · smaller download",
            VoiceModel.Kind.WHISPER,
            listOf(
                VoiceFile("$HF/sherpa-onnx-whisper-base/resolve/main/base-encoder.int8.onnx", "encoder.int8.onnx", 29_120_534),
                VoiceFile("$HF/sherpa-onnx-whisper-base/resolve/main/base-decoder.int8.onnx", "decoder.int8.onnx", 130_672_026),
                VoiceFile("$HF/sherpa-onnx-whisper-base/resolve/main/base-tokens.txt", "tokens.txt", 816_730),
            ),
        ),
        VoiceModel(
            "whisper-tiny", "Whisper Tiny",
            "Fastest & smallest · 99 languages · lower accuracy",
            VoiceModel.Kind.WHISPER,
            listOf(
                VoiceFile("$HF/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx", "encoder.int8.onnx", 12_937_772),
                VoiceFile("$HF/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx", "decoder.int8.onnx", 89_855_401),
                VoiceFile("$HF/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt", "tokens.txt", 816_730),
            ),
        ),
    )
    fun byId(id: String) = models.firstOrNull { it.id == id }
}

enum class VoiceState { NO_MODEL, DOWNLOADING, IDLE, LOADING, READY, LISTENING, ERROR }

interface VoiceListener {
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onStopped()
}

/**
 * Fully offline voice typing: microphone -> Silero VAD -> sherpa-onnx offline recognizer.
 * While you speak, the growing utterance is re-decoded continuously for live partial text;
 * when you pause, the VAD closes the segment and the final text is committed.
 */
object VoiceEngine {
    private const val SR = 16000
    private lateinit var ctx: Context
    val state = mutableStateOf(VoiceState.NO_MODEL)
    val progress = mutableFloatStateOf(0f)
    val level = mutableFloatStateOf(0f)
    val message = mutableStateOf("")
    val loadedModel = mutableStateOf("")

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val decoder = Executors.newSingleThreadExecutor { Thread(it, "asr-decode").apply { priority = Thread.MAX_PRIORITY - 1 } }
    private var recognizer: OfflineRecognizer? = null
    private var recognizerId = ""
    private var vad: Vad? = null
    private val running = AtomicBoolean(false)
    private val decodeBusy = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private var audioThread: Thread? = null
    private var listener: VoiceListener? = null
    private var pollJob: Job? = null
    private var unloadJob: Job? = null

    fun init(c: Context) {
        ctx = c.applicationContext
        refreshState()
        val pending = Prefs.raw().getString("voice_dl", null)
        if (pending != null) resumePoll(pending)
    }

    fun dir(m: VoiceModel) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "voice/${m.id}").apply { mkdirs() }
    fun isInstalled(m: VoiceModel) = m.files.all { File(dir(m), it.name).let { f -> f.exists() && f.length() > 0 } }
    fun activeModel(): VoiceModel? =
        VoiceCatalog.byId(Prefs.voiceModel.value)?.takeIf { isInstalled(it) } ?: VoiceCatalog.models.firstOrNull { isInstalled(it) }
    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun refreshState() {
        if (state.value == VoiceState.DOWNLOADING || state.value == VoiceState.LISTENING || state.value == VoiceState.LOADING) return
        state.value = when {
            activeModel() == null -> VoiceState.NO_MODEL
            recognizer != null -> VoiceState.READY
            else -> VoiceState.IDLE
        }
    }

    // ------------------------------------------------------------------ downloads

    fun startDownload(m: VoiceModel) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val ids = m.files.filter { !File(dir(m), it.name).exists() }.map { f ->
            File(dir(m), f.name + ".part").delete()
            dm.enqueue(
                DownloadManager.Request(Uri.parse(f.url))
                    .setTitle("Expressive Keyboard voice model")
                    .setDescription("${m.name} · ${f.name}")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(ctx, "voice/${m.id}", f.name + ".part"),
            )
        }
        val token = m.id + "|" + ids.joinToString(",")
        Prefs.raw().edit().putString("voice_dl", token).apply()
        resumePoll(token)
    }

    fun cancelDownload() {
        val token = Prefs.raw().getString("voice_dl", null) ?: return
        val dm = ctx.getSystemService(DownloadManager::class.java)
        token.substringAfter('|').split(',').mapNotNull { it.toLongOrNull() }.forEach { dm.remove(it) }
        Prefs.raw().edit().remove("voice_dl").apply()
        pollJob?.cancel()
        state.value = VoiceState.NO_MODEL
        refreshState()
    }

    private fun resumePoll(token: String) {
        val m = VoiceCatalog.byId(token.substringBefore('|')) ?: return
        val ids = token.substringAfter('|').split(',').mapNotNull { it.toLongOrNull() }
        val dm = ctx.getSystemService(DownloadManager::class.java)
        state.value = VoiceState.DOWNLOADING
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                var done = 0L; var allOk = true; var failed = false
                for (id in ids) {
                    dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (c.moveToFirst()) {
                            val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            done += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            if (st == DownloadManager.STATUS_FAILED) failed = true
                            if (st != DownloadManager.STATUS_SUCCESSFUL) allOk = false
                        } else { failed = true }
                    }
                }
                val already = m.files.filter { File(dir(m), it.name).exists() }.sumOf { it.size }
                progress.floatValue = ((done + already).toFloat() / m.sizeBytes).coerceIn(0f, 1f)
                if (failed) {
                    Prefs.raw().edit().remove("voice_dl").apply()
                    message.value = "Voice model download failed"
                    state.value = VoiceState.ERROR
                    delay(2000); state.value = VoiceState.NO_MODEL; refreshState()
                    return@launch
                }
                if (allOk) {
                    // DownloadManager may still be finalising the files
                    delay(1500)
                    m.files.forEach { f ->
                        val part = File(dir(m), f.name + ".part")
                        if (part.exists()) { File(dir(m), f.name).delete(); part.renameTo(File(dir(m), f.name)) }
                    }
                    Prefs.raw().edit().remove("voice_dl").apply()
                    if (isInstalled(m)) { Prefs.voiceModel.value = m.id; message.value = "${m.name} ready" }
                    state.value = VoiceState.NO_MODEL
                    refreshState()
                    return@launch
                }
                delay(600)
            }
        }
    }

    fun deleteModel(m: VoiceModel) {
        if (recognizerId == m.id) unload()
        dir(m).deleteRecursively()
        if (Prefs.voiceModel.value == m.id) Prefs.voiceModel.value = ""
        refreshState()
    }

    /** Called when the voice language setting changes so the next session uses it. */
    fun settingsChanged() { unload() }

    fun select(m: VoiceModel) {
        Prefs.voiceModel.value = m.id
        if (recognizerId != m.id) unload()
        refreshState()
    }

    // ------------------------------------------------------------------ recognizer

    private fun threads(): Int {
        val p = Prefs.voiceThreads.value
        if (p > 0) return p
        return Runtime.getRuntime().availableProcessors().coerceIn(2, 6) - 1
    }

    @Synchronized
    private fun ensureRecognizer(): OfflineRecognizer? {
        val m = activeModel() ?: return null
        recognizer?.let { if (recognizerId == m.id) return it }
        recognizer?.release(); recognizer = null
        main.post { state.value = VoiceState.LOADING }
        val d = dir(m).absolutePath
        val modelConfig = when (m.kind) {
            VoiceModel.Kind.PARAKEET -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$d/encoder.int8.onnx", decoder = "$d/decoder.int8.onnx", joiner = "$d/joiner.int8.onnx",
                ),
                tokens = "$d/tokens.txt", numThreads = threads(), modelType = "nemo_transducer",
            )
            VoiceModel.Kind.WHISPER -> {
                val langs = Dictionary.activeCodes()
                val forced = Prefs.voiceLanguage.value.takeIf { it != "auto" }
                OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = "$d/encoder.int8.onnx", decoder = "$d/decoder.int8.onnx",
                        language = forced ?: (if (langs.size == 1) langs[0] else ""), task = "transcribe", tailPaddings = 500,
                    ),
                    tokens = "$d/tokens.txt", numThreads = threads(), modelType = "whisper",
                )
            }
        }
        val r = runCatching { OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = modelConfig)) }
            .onFailure { main.post { message.value = "Couldn't load voice model: ${it.message}"; state.value = VoiceState.ERROR } }
            .getOrNull() ?: return null
        recognizer = r
        recognizerId = m.id
        main.post { loadedModel.value = m.name }
        return r
    }

    @Synchronized
    private fun ensureVad(): Vad {
        vad?.let { return it }
        val cfg = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx", threshold = 0.45f, minSilenceDuration = 0.45f,
                minSpeechDuration = 0.2f, windowSize = 512, maxSpeechDuration = 18f,
            ),
            sampleRate = SR, numThreads = 1,
        )
        return Vad(ctx.assets, cfg).also { vad = it }
    }

    /** Load the model in the background so the first dictation starts instantly. */
    fun preload() {
        if (activeModel() == null) return
        unloadJob?.cancel()
        decoder.execute {
            ensureRecognizer()
            ensureVad()
            main.post { if (state.value == VoiceState.LOADING) state.value = VoiceState.READY }
        }
    }

    fun scheduleUnload(ms: Long = 5 * 60_000L) {
        unloadJob?.cancel()
        unloadJob = scope.launch { delay(ms); if (!running.get()) unload() }
    }

    fun unload() {
        decoder.execute {
            synchronized(this) {
                recognizer?.release(); recognizer = null; recognizerId = ""
                vad?.release(); vad = null
            }
            main.post { loadedModel.value = ""; refreshState() }
        }
    }

    private fun decode(samples: FloatArray): String {
        val r = recognizer ?: return ""
        val st = r.createStream()
        return try {
            st.acceptWaveform(samples, SR)
            r.decode(st)
            r.getResult(st).text
                .replace(Regex("\\[[^]]*]|\\([^)]*\\)"), "") // whisper non-speech tags
                .replace(Regex("\\s+"), " ")
                .trim()
        } finally {
            st.release()
        }
    }

    val isListening get() = running.get()

    // ------------------------------------------------------------------ capture loop

    @SuppressLint("MissingPermission")
    fun start(l: VoiceListener): Boolean {
        if (running.get()) return true
        if (!hasPermission() || activeModel() == null) return false
        listener = l
        running.set(true)
        unloadJob?.cancel()
        state.value = if (recognizer == null) VoiceState.LOADING else VoiceState.LISTENING
        generation.incrementAndGet()
        audioThread = Thread({ captureLoop() }, "asr-audio").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    fun stop() {
        running.set(false)
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        // make sure models are ready (runs on the decoder thread to keep ordering)
        val ready = java.util.concurrent.CountDownLatch(1)
        decoder.execute { ensureRecognizer(); ensureVad(); ready.countDown() }
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SR * 2 * 4))
        } catch (e: Exception) {
            main.post { message.value = "Microphone unavailable"; state.value = VoiceState.ERROR }
            running.set(false); finishSession(); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            main.post { message.value = "Microphone unavailable"; state.value = VoiceState.ERROR }
            rec.release(); running.set(false); finishSession(); return
        }
        rec.startRecording()
        // audio recorded while the model loads is buffered, so nothing you say is lost
        val pending = ArrayList<FloatArray>()
        val chunk = ShortArray(512)
        var loaded = false
        val preroll = ArrayDeque<FloatArray>()
        val speech = ArrayList<FloatArray>()
        var speechSamples = 0
        var inSpeech = false
        var lastPartial = 0L
        try {
            while (running.get()) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val f = FloatArray(n)
                var sum = 0f
                for (i in 0 until n) { val v = chunk[i] / 32768f; f[i] = v; sum += v * v }
                val rms = sqrt(sum / n)
                main.post { level.floatValue = (rms * 12f).coerceIn(0f, 1f) }
                if (!loaded) {
                    pending += f
                    if (ready.count == 0L) {
                        loaded = true
                        if (recognizer == null) { running.set(false); break }
                        main.post { state.value = VoiceState.LISTENING }
                        pending.forEach { feed(it, preroll, speech) }
                        pending.clear()
                    }
                    continue
                }
                val v = vad ?: break
                v.acceptWaveform(f)
                val speaking = v.isSpeechDetected()
                if (speaking && !inSpeech) {
                    inSpeech = true
                    speech.clear(); speechSamples = 0
                    preroll.forEach { speech += it; speechSamples += it.size }
                }
                if (inSpeech) { speech += f; speechSamples += f.size } else {
                    preroll.addLast(f); while (preroll.size > 10) preroll.removeFirst()
                }
                var closed = false
                while (!v.empty()) {
                    val seg = v.front(); v.pop()
                    submitFinal(seg.samples)
                    closed = true
                }
                if (closed) { inSpeech = false; speech.clear(); speechSamples = 0 }
                val now = System.currentTimeMillis()
                if (inSpeech && speechSamples > SR / 3 && now - lastPartial > 250 && !decodeBusy.get()) {
                    lastPartial = now
                    submitPartial(join(speech, speechSamples))
                }
            }
            // flush what's left
            vad?.let { v ->
                v.flush()
                var any = false
                while (!v.empty()) { val seg = v.front(); v.pop(); submitFinal(seg.samples); any = true }
                if (!any && inSpeech && speechSamples > SR / 4) submitFinal(join(speech, speechSamples))
                v.reset()
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            finishSession()
        }
    }

    private fun feed(f: FloatArray, preroll: ArrayDeque<FloatArray>, speech: ArrayList<FloatArray>) {
        // first chunks after loading: just prime the VAD; segments are handled by the main loop
        vad?.acceptWaveform(f)
        preroll.addLast(f); while (preroll.size > 10) preroll.removeFirst()
        @Suppress("UNUSED_EXPRESSION") speech
    }

    private fun join(parts: List<FloatArray>, total: Int): FloatArray {
        val out = FloatArray(total)
        var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, minOf(p.size, total - o)); o += p.size; if (o >= total) break }
        return out
    }

    private fun submitPartial(samples: FloatArray) {
        val gen = generation.get()
        decodeBusy.set(true)
        decoder.execute {
            try {
                val text = decode(samples)
                if (gen == generation.get() && text.isNotEmpty()) main.post { if (gen == generation.get()) listener?.onPartial(text) }
            } finally { decodeBusy.set(false) }
        }
    }

    private fun submitFinal(samples: FloatArray) {
        generation.incrementAndGet() // invalidate partials of this utterance
        decoder.execute {
            val text = decode(samples)
            main.post { listener?.onFinal(text) }
        }
    }

    private fun finishSession() {
        decoder.execute {
            main.post {
                level.floatValue = 0f
                listener?.onStopped()
                listener = null
                state.value = VoiceState.NO_MODEL
                refreshState()
                scheduleUnload()
            }
        }
    }
}
