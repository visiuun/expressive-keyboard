package com.vis.expressivekeyboard.engine

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.vis.expressivekeyboard.data.Prefs
import java.text.Normalizer
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

data class Suggestion(val word: String, val score: Float, val kind: Kind, val dist: Float = 0f) {
    enum class Kind { EXACT, COMPLETION, CORRECTION, PREDICTION, AI, CLIP }
}

data class SuggestResult(
    val typed: String,
    val typedValid: Boolean,
    val candidates: List<Suggestion>,
    /** Word that should replace [typed] when a separator is typed, or null. */
    val autoCorrect: String?,
)

/** Typing context: previous word, sentence start, and recent words (for language detection). */
data class Ctx(val prev: String?, val sentenceStart: Boolean, val recent: List<String> = emptyList())

data class LanguageInfo(val code: String, val name: String, val flag: String)

object Languages {
    val all = listOf(
        LanguageInfo("en", "English", "🇬🇧"),
        LanguageInfo("it", "Italiano", "🇮🇹"),
        LanguageInfo("es", "Español", "🇪🇸"),
        LanguageInfo("fr", "Français", "🇫🇷"),
        LanguageInfo("de", "Deutsch", "🇩🇪"),
        LanguageInfo("pt", "Português", "🇵🇹"),
    )
    const val MAX_ACTIVE = 3
    fun info(code: String) = all.firstOrNull { it.code == code } ?: all[0]
}

/** How often each language is used, so suggestions favour your most-used language. */
object LangStats {
    val counts = mutableStateMapOf<String, Int>()
    private var ctx: Context? = null
    private var dirty = 0

    fun init(c: Context) {
        ctx = c.applicationContext
        val sp = c.getSharedPreferences("lang_stats", Context.MODE_PRIVATE)
        Languages.all.forEach { counts[it.code] = sp.getInt(it.code, 0) }
    }

    fun record(code: String) {
        counts[code] = (counts[code] ?: 0) + 1
        if (++dirty >= 10) flush()
    }

    fun flush() {
        dirty = 0
        val e = ctx?.getSharedPreferences("lang_stats", Context.MODE_PRIVATE)?.edit() ?: return
        counts.forEach { (k, v) -> e.putInt(k, v) }
        e.apply()
    }

    fun share(code: String): Float {
        val active = Dictionary.activeCodes()
        val total = active.sumOf { counts[it] ?: 0 }
        return if (total == 0) 1f / active.size.coerceAtLeast(1) else (counts[code] ?: 0).toFloat() / total
    }
}

/**
 * Multi-language statistical engine. Combines up to three [LangModel]s, weighting each by how
 * much you use it and by the language of the sentence you are typing, plus the learned user
 * vocabulary. A word valid in any active language is never auto-corrected.
 */
object Dictionary {
    const val DIST_W = 3.9f
    @Volatile var ready = false
        private set
    val loadedCodes = mutableStateOf<List<String>>(emptyList())
    @Volatile var models: List<LangModel> = emptyList()
        private set
    private val cache = HashMap<String, LangModel>()
    private lateinit var app: Context
    private val neighbors = Array(26) { BooleanArray(26) }

    fun activeCodes(): List<String> =
        Prefs.languages.value.split(',').map { it.trim() }.filter { c -> Languages.all.any { it.code == c } }
            .ifEmpty { listOf("en") }.take(Languages.MAX_ACTIVE)

    fun loadAsync(ctx: Context) {
        app = ctx.applicationContext
        buildNeighbors()
        LangStats.init(app)
        reload()
    }

    /** (Re)loads the active languages in the background. */
    fun reload() {
        val codes = activeCodes()
        thread(name = "dict-load") {
            runCatching {
                UserDictionary.load(app)
                val list = codes.map { code ->
                    synchronized(cache) { cache[code] } ?: LangModel(code).also { it.load(app); synchronized(cache) { cache[code] = it } }
                }
                synchronized(cache) { cache.keys.retainAll(codes.toSet()) }
                models = list
                loadedCodes.value = codes
                ready = true
            }.onFailure { it.printStackTrace() }
        }
    }

    private fun buildNeighbors() {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val offs = floatArrayOf(0f, 0.5f, 1.5f)
        val pos = HashMap<Char, Pair<Float, Float>>()
        rows.forEachIndexed { r, s -> s.forEachIndexed { c, ch -> pos[ch] = (c + offs[r]) to r.toFloat() } }
        for ((a, pa) in pos) for ((b, pb) in pos) {
            if (a == b) continue
            val dx = pa.first - pb.first; val dy = pa.second - pb.second
            if (dx * dx + dy * dy <= 1.6f) neighbors[a - 'a'][b - 'a'] = true
        }
    }

    // ------------------------------------------------------------------ language weighting

    private fun weights(ctx: Ctx): FloatArray {
        val ms = models
        val w = FloatArray(ms.size)
        if (ms.size <= 1) return w
        val active = ms.map { it.code }
        val total = active.sumOf { LangStats.counts[it] ?: 0 }
        ms.forEachIndexed { i, m ->
            val c = LangStats.counts[m.code] ?: 0
            w[i] = ln((c + 8f) / (total + 8f * ms.size)) * 0.8f
        }
        // language of the current sentence: words that exist in only one language are strong evidence
        val ctxScore = FloatArray(ms.size)
        for (word in ctx.recent.takeLast(8)) {
            val l = word.lowercase()
            val validIn = ms.indices.filter { ms[it].exact(l) != null }
            if (validIn.size == 1) ctxScore[validIn[0]] += 0.9f
            else if (validIn.size in 2 until ms.size) validIn.forEach { ctxScore[it] += 0.25f }
        }
        for (i in w.indices) w[i] += min(ctxScore[i], 2.7f)
        val max = w.maxOrNull() ?: 0f
        for (i in w.indices) w[i] -= max
        return w
    }

    // ------------------------------------------------------------------ queries

    fun isValid(word: String): Boolean {
        if (!ready) return true
        val l = word.lowercase()
        if (UserDictionary.count(l) >= 2) return true
        return models.any { m -> m.exact(l) != null || elisionSplit(m, l)?.let { m.exact(it.second) != null } == true }
    }

    /**
     * Best guess of the language of [text] among the active languages, or null if unsure.
     * Compares how *common* each word is per language, because subtitle-based word lists
     * contain loan words from other languages ("book" exists in the Italian list too).
     */
    fun detectLanguage(text: String): String? {
        if (!ready) return null
        val ms = models
        if (ms.size < 2) return ms.firstOrNull()?.code
        val ws = Regex("[\\p{L}']+").findAll(text.lowercase()).map { it.value }.filter { it.length >= 3 }.toList()
        if (ws.size < 2) return null
        val score = FloatArray(ms.size)
        var counted = 0
        for (w in ws) {
            val f = FloatArray(ms.size) { i -> ms[i].exact(w)?.let { ms[i].logf[it] } ?: 0f }
            if (f.all { it == 0f }) continue
            counted++
            for (i in f.indices) score[i] += f[i]
        }
        if (counted < 2) return null
        val best = score.indices.maxByOrNull { score[it] } ?: return null
        val second = score.indices.filter { it != best }.maxOfOrNull { score[it] } ?: 0f
        return if (score[best] > second * 1.25f + 1f) ms[best].code else null
    }

    /** Languages in which [word] is a known word (used for usage statistics). */
    fun languagesOf(word: String): List<String> {
        val l = word.lowercase()
        return models.filter { m -> m.exact(l) != null || elisionSplit(m, l)?.let { m.exact(it.second) != null } == true }.map { it.code }
    }

    private fun elisionSplit(m: LangModel, low: String): Pair<String, String>? {
        if (m.elisions.isEmpty()) return null
        val i = low.indexOf('\'')
        if (i <= 0 || i == low.length - 1) return null
        val prefix = low.substring(0, i + 1)
        return if (prefix in m.elisions) prefix to low.substring(i + 1) else null
    }

    fun maxDistance(n: Int) = when {
        n <= 2 -> 1.0f
        n <= 4 -> 1.6f
        n <= 7 -> 2.2f
        else -> 2.8f
    }

    fun suggest(typed: String, ctx: Ctx, max: Int = 6): SuggestResult {
        if (!ready || typed.isEmpty()) return SuggestResult(typed, true, emptyList(), null)
        val ms = models
        val low = typed.lowercase()
        val blockOff = Prefs.blockOffensive.value
        val prevLower = if (ctx.sentenceStart) "<s>" else ctx.prev?.lowercase()
        val w = weights(ctx)
        val cands = HashMap<String, Suggestion>()
        fun offer(display: String, score: Float, kind: Suggestion.Kind, d: Float) {
            val key = display.lowercase()
            if (UserDictionary.isBlocked(key)) return
            val old = cands[key]
            if (old == null || old.score < score) cands[key] = Suggestion(display, score, kind, d)
        }
        val userBoost: (String) -> Float = { wl ->
            if (prevLower == null) 0f else UserDictionary.bigram(prevLower, wl).let { if (it > 0) ln(1f + it) * 1.6f else 0f }
        }
        var typedValid = UserDictionary.count(low) >= 2
        var exactDisplay: String? = null
        var exactWeight = -1e9f
        ms.forEachIndexed { mi, m ->
            val split = elisionSplit(m, low)
            val (prefix, rest) = split ?: ("" to low)
            val typedPrefix = typed.substring(0, prefix.length)
            m.exact(rest)?.let { idx ->
                typedValid = true
                val disp = typedPrefix + m.words[idx]
                // casing comes from the language that best fits the sentence ("bad and i" -> "I",
                // "la città è bella" keeps "bella"); on a tie prefer the lowercase spelling
                val wt = w[mi] + (if (disp == low) 0.05f else 0f)
                if (wt > exactWeight) { exactWeight = wt; exactDisplay = disp }
            }
            val pSlot = m.prevSlot(ctx.prev, ctx.sentenceStart)
            m.collect(rest, pSlot, blockOff, userBoost) { disp, s, kind, d ->
                offer(typedPrefix + disp, s + w[mi], kind, d)
            }
        }
        // learned words
        val nrm = low.replace("'", "")
        val n = nrm.length
        val maxD = maxDistance(n)
        val userCount = UserDictionary.count(low)
        for ((ul, entry) in UserDictionary.snapshotWords()) {
            val (display, count) = entry
            if (ul == low) continue
            val un = ul.replace("'", "")
            val base = 9f + ln(1f + count) * 1.5f
            if (un.startsWith(nrm)) offer(display, base - 1.2f - 0.1f * (un.length - n), Suggestion.Kind.COMPLETION, 0f)
            else if (abs(un.length - n) <= 2) {
                val d = distance(nrm, un, maxD)
                if (d <= maxD) offer(display, base - d * DIST_W, Suggestion.Kind.CORRECTION, d)
            }
        }
        if (userCount > 0 && exactDisplay == null) {
            offer(UserDictionary.display(low) ?: typed, 9f + ln(1f + userCount) * 1.5f + 1.5f, Suggestion.Kind.EXACT, 0f)
        }

        val sorted = cands.values.sortedByDescending { it.score }.take(max)
            .map { it.copy(word = applyCase(typed, it.word)) }

        var auto: String? = null
        if (Prefs.autocorrect.value && looksLikeWord(typed)) {
            val best = sorted.firstOrNull()
            if (typedValid) {
                exactDisplay?.let { ed ->
                    val disp = applyCase(typed, ed)
                    if (disp != typed && disp.lowercase() == low) auto = disp
                }
            } else if (best != null && best.kind != Suggestion.Kind.COMPLETION) {
                val second = sorted.getOrNull(1)
                val margin = best.score - (second?.score ?: -100f)
                val confident = best.dist <= 0.2f || margin > 0.6f || (best.dist <= 1.0f && n >= 3)
                if (confident && (n >= 2 || best.dist <= 0.2f)) auto = best.word
            }
        }
        return SuggestResult(typed, typedValid, sorted, auto)
    }

    /** Next-word predictions from bigram statistics of every active language and your own history. */
    fun predict(ctx: Ctx, max: Int = 3): List<String> {
        if (!ready) return emptyList()
        val ms = models
        val w = weights(ctx)
        val prevLower = if (ctx.sentenceStart) "<s>" else ctx.prev?.lowercase()
        val scores = HashMap<String, Float>()
        val display = HashMap<String, String>()
        val blockOff = Prefs.blockOffensive.value
        ms.forEachIndexed { mi, m ->
            val pSlot = m.prevSlot(ctx.prev, ctx.sentenceStart)
            for ((t, c) in m.successors(pSlot)) {
                if (blockOff && m.isOffensive(t)) continue
                val k = m.lower[t]
                val s = ln(1f + c) + m.logf[t] * 0.15f + w[mi] * 1.5f
                if ((scores[k] ?: -1e9f) < s) { scores[k] = s; display[k] = m.words[t] }
            }
        }
        if (prevLower != null) {
            for ((word, c) in UserDictionary.successors(prevLower)) {
                val k = word.lowercase()
                scores[k] = (scores[k] ?: 0f) + ln(1f + c) * 2.2f + 1f
                display.putIfAbsent(k, word)
            }
        }
        return scores.entries.asSequence()
            .filter { !UserDictionary.isBlocked(it.key) }
            .sortedByDescending { it.value }
            .take(max)
            .map { e ->
                val d = display[e.key]!!
                if (ctx.sentenceStart) d.replaceFirstChar { it.uppercaseChar() } else d
            }.toList()
    }

    /** Per-language weight used by the gesture decoder. */
    fun modelWeights(ctx: Ctx): FloatArray = weights(ctx)

    fun looksLikeWord(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.any { it.isDigit() || it == '@' || it == '/' || it == '_' }) return false
        if (s.length > 1 && s.drop(1).any { it.isUpperCase() } && !s.all { it.isUpperCase() || it == '\'' }) return false
        return true
    }

    fun applyCase(typed: String, word: String): String {
        if (typed.isEmpty()) return word
        val letters = typed.filter { it.isLetter() }
        return when {
            letters.length > 1 && letters.all { it.isUpperCase() } -> word.uppercase()
            typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }

    // ------------------------------------------------------------------ distance

    private val foldCache = HashMap<Char, Char>()

    fun baseChar(c: Char): Char {
        if (c in 'a'..'z') return c
        return synchronized(foldCache) {
            foldCache.getOrPut(c) {
                when (c) {
                    'ß' -> 's'; 'æ' -> 'a'; 'œ' -> 'o'; 'ø' -> 'o'
                    else -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
                }
            }
        }
    }

    fun foldAccents(s: String): String {
        if (s.all { it in 'a'..'z' }) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(baseChar(c))
        return sb.toString()
    }

    private val dpPrev = ThreadLocal.withInitial { FloatArray(64) }
    private val dpCur = ThreadLocal.withInitial { FloatArray(64) }
    private val dpPrev2 = ThreadLocal.withInitial { FloatArray(64) }

    private fun subCost(a: Char, b: Char, first: Boolean): Float {
        if (a == b) return 0f
        val ba = baseChar(a); val bb = baseChar(b)
        if (ba == bb) return 0.15f // missing/extra accent: perche -> perché
        val ai = ba - 'a'; val bi = bb - 'a'
        val near = ai in 0..25 && bi in 0..25 && neighbors[ai][bi]
        return (if (near) 0.6f else 1.0f) + (if (first) 0.3f else 0f)
    }

    /** Weighted optimal-string-alignment distance with keyboard-proximity and accent costs. */
    fun distance(a: String, b: String, maxD: Float): Float {
        val la = a.length; val lb = b.length
        if (la >= 63 || lb >= 63) return maxD + 1
        var prev2 = dpPrev2.get()!!; var prev = dpPrev.get()!!; var cur = dpCur.get()!!
        // leading edits are rare in real typing: penalise them
        prev[0] = 0f
        for (j in 1..lb) prev[j] = j * 0.8f + 0.4f
        for (i in 1..la) {
            cur[0] = i + 0.4f
            var rowMin = cur[0]
            val ca = a[i - 1]
            for (j in 1..lb) {
                val cb = b[j - 1]
                val delCost = if (i >= 2 && a[i - 2] == ca) 0.3f else 1f
                // a letter missing from what you typed is the most common slip ("wether" -> "weather")
                val insCost = if (j >= 2 && b[j - 2] == cb) 0.3f else 0.8f
                var v = min(prev[j] + delCost, cur[j - 1] + insCost)
                v = min(v, prev[j - 1] + subCost(ca, cb, i == 1 && j == 1))
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = min(v, prev2[j - 2] + 0.7f)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > maxD) return maxD + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[lb]
    }
}
