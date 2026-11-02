package com.vis.expressivekeyboard.engine

import android.content.Context
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/** Static language data for one language: unigram frequencies, bigrams and gesture buckets. */
class LangModel(val code: String) {
    lateinit var words: Array<String>; private set
    lateinit var lower: Array<String>; private set
    lateinit var norm: Array<String>; private set
    lateinit var logf: FloatArray; private set
    private lateinit var offensive: BooleanArray
    private val lowerIndex = HashMap<String, Int>(70000)
    private val normIndex = HashMap<String, Int>(70000)
    private lateinit var sortedByLower: IntArray
    private lateinit var bgOffsets: IntArray
    private lateinit var bgTarget: IntArray
    private lateinit var bgCount: IntArray
    lateinit var byFirstLast: Array<IntArray>; private set
    /** Elided prefixes like "l'", "dell'", "j'" (Italian/French), used to split "l'amico". */
    var elisions: Set<String> = emptySet(); private set

    fun load(ctx: Context) {
        val w = ArrayList<String>(40000)
        val f = ArrayList<Int>(40000)
        val off = ArrayList<Boolean>(40000)
        ctx.assets.open("dict_$code.txt").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val p = line.split('\t')
                if (p.size >= 2) { w += p[0]; f += p[1].toInt(); off += (p.getOrNull(2) == "1") }
            }
        }
        val n = w.size
        words = w.toTypedArray()
        lower = Array(n) { words[it].lowercase() }
        norm = Array(n) { lower[it].replace("'", "") }
        logf = FloatArray(n) { ln(f[it].toFloat().coerceAtLeast(2f)) }
        offensive = BooleanArray(n) { off[it] }
        for (i in 0 until n) {
            lowerIndex.putIfAbsent(lower[i], i)
            normIndex.putIfAbsent(norm[i], i)
        }
        elisions = lower.filter { it.length in 2..6 && it.endsWith("'") }.toSet()
        sortedByLower = (0 until n).sortedBy { lower[it] }.toIntArray()

        val bytes = ctx.assets.open("bigrams_$code.bin").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes)
        val rows = bb.int
        val slots = n + 1
        val counts = IntArray(slots + 1)
        val ta = IntArray(rows); val tb = IntArray(rows); val tc = IntArray(rows)
        for (r in 0 until rows) {
            val a = bb.int; val b = bb.int; val c = bb.short.toInt() and 0xFFFF
            val slot = if (a == START) n else a
            ta[r] = slot; tb[r] = b; tc[r] = c
            counts[slot + 1]++
        }
        for (i in 1..slots) counts[i] += counts[i - 1]
        bgOffsets = counts
        bgTarget = IntArray(rows); bgCount = IntArray(rows)
        val fill = counts.copyOf()
        for (r in 0 until rows) {
            val pos = fill[ta[r]]++
            bgTarget[pos] = tb[r]; bgCount[pos] = tc[r]
        }

        val buckets = Array(26 * 26) { ArrayList<Int>() }
        for (i in 0 until n) {
            val s = Dictionary.foldAccents(norm[i])
            if (s.length < 2 || s.any { it !in 'a'..'z' }) continue
            buckets[(s[0] - 'a') * 26 + (s.last() - 'a')].add(i)
        }
        byFirstLast = Array(buckets.size) { buckets[it].toIntArray() }
    }

    val size get() = words.size
    fun exact(low: String): Int? = lowerIndex[low]
    fun indexOfNorm(n: String): Int = normIndex[n] ?: -1
    fun isOffensive(i: Int) = i >= 0 && offensive[i]

    fun prevSlot(prev: String?, sentenceStart: Boolean): Int {
        if (sentenceStart) return words.size
        if (prev == null) return -1
        return lowerIndex[prev.lowercase()] ?: -1
    }

    fun bigram(prevSlot: Int, target: Int): Int {
        if (prevSlot < 0) return 0
        // rows are sorted by (predecessor, successor), so successors are ascending: binary search
        var lo = bgOffsets[prevSlot]; var hi = bgOffsets[prevSlot + 1] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val t = bgTarget[mid]
            if (t == target) return bgCount[mid]
            if (t < target) lo = mid + 1 else hi = mid - 1
        }
        return 0
    }

    fun contextBoost(prevSlot: Int, idx: Int): Float {
        val c = bigram(prevSlot, idx)
        return if (c > 0) ln(1f + c) * 0.9f else 0f
    }

    /** Successors of [prevSlot]: (word index, count). */
    fun successors(prevSlot: Int): List<Pair<Int, Int>> {
        if (prevSlot < 0) return emptyList()
        return (bgOffsets[prevSlot] until bgOffsets[prevSlot + 1]).map { bgTarget[it] to bgCount[it] }
    }

    /**
     * Collects completion and correction candidates for [low] (lowercase typed word).
     * [offer] receives (display, score, kind, distance).
     */
    fun collect(
        low: String,
        pSlot: Int,
        blockOff: Boolean,
        userBoost: (String) -> Float,
        offer: (String, Float, Suggestion.Kind, Float) -> Unit,
    ) {
        val nrm = low.replace("'", "")
        val n = nrm.length
        val exactIdx = lowerIndex[low]
        if (exactIdx != null) {
            offer(words[exactIdx], logf[exactIdx] + contextBoost(pSlot, exactIdx) + userBoost(low) + 1.5f, Suggestion.Kind.EXACT, 0f)
        }
        // completions
        var lo = 0; var hi = sortedByLower.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lower[sortedByLower[mid]] < low) lo = mid + 1 else hi = mid
        }
        var i = lo; var scanned = 0
        while (i < sortedByLower.size && scanned < 3000) {
            val idx = sortedByLower[i]
            val l = lower[idx]
            if (!l.startsWith(low)) break
            if (l.length > low.length && !(blockOff && offensive[idx])) {
                offer(words[idx], logf[idx] + contextBoost(pSlot, idx) + userBoost(l) - 1.6f - 0.12f * (l.length - low.length), Suggestion.Kind.COMPLETION, 0f)
            }
            i++; scanned++
        }
        if (n < 1) return
        val maxD = Dictionary.maxDistance(n)
        for (idx in norm.indices) {
            if (blockOff && offensive[idx] && exactIdx != idx) continue
            val w = norm[idx]
            val dl = w.length - n
            if (abs(dl) <= 2) {
                val d = Dictionary.distance(nrm, w, maxD)
                if (d <= maxD && !(d == 0f && lower[idx] == low)) {
                    offer(words[idx], logf[idx] + contextBoost(pSlot, idx) + userBoost(lower[idx]) - d * Dictionary.DIST_W, Suggestion.Kind.CORRECTION, d)
                }
            }
            if (n >= 4 && dl >= 2) {
                val d = Dictionary.distance(nrm, w.substring(0, n), min(maxD, 1.2f))
                if (d in 0.01f..1.2f) {
                    offer(words[idx], logf[idx] + contextBoost(pSlot, idx) - 1.6f - d * Dictionary.DIST_W - 0.12f * dl, Suggestion.Kind.COMPLETION, d)
                }
            }
        }
    }

    companion object {
        const val START = 0xFFFFF
    }
}
