package com.vis.expressivekeyboard.engine

import androidx.compose.ui.geometry.Offset
import com.vis.expressivekeyboard.data.Prefs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Shape + location matching gesture decoder. Candidate words are filtered by the keys nearest
 * to the start/end of the trace and by a sequential "every letter was approached" check, then
 * ranked by mean point-wise distance between the user trace and the ideal key-to-key path,
 * combined with word frequency and bigram context.
 */
object GestureDecoder {
    private const val N = 48

    fun decode(
        raw: List<Offset>,
        centers: Map<Char, Offset>,
        keyW: Float,
        ctx: Ctx,
        max: Int = 5,
    ): List<String> {
        if (!Dictionary.ready || raw.size < 3 || centers.isEmpty()) return emptyList()
        val path = dedupe(raw)
        if (path.size < 2) return emptyList()
        val user = resample(path, N)
        val start = path.first(); val end = path.last()
        val radius = keyW * 1.35f
        val startChars = nearest(centers, start, radius, 3)
        val endChars = nearest(centers, end, radius, 4)
        val tol = keyW * 0.95f
        val blockOff = Prefs.blockOffensive.value

        class Cand(val word: String, val score: Float)
        val out = ArrayList<Cand>()

        fun evaluate(word: String, freqScore: Float, ctx: Float) {
            val seq = ArrayList<Offset>(word.length)
            var last = ' '
            for (ch in word) {
                if (ch == last) continue
                val c = centers[ch] ?: return
                seq += c; last = ch
            }
            if (!approaches(path, seq, tol)) return
            val ideal = resample(seq, N)
            var sum = 0f
            for (i in 0 until N) sum += dist(user[i], ideal[i])
            val loc = sum / N / keyW
            val score = -loc * 4.2f + freqScore * 0.4f + ctx
            out += Cand(word, score)
        }

        val models = Dictionary.models
        val weights = Dictionary.modelWeights(ctx)
        val display = HashMap<String, String>()
        models.forEachIndexed { mi, m ->
            val pSlot = m.prevSlot(ctx.prev, ctx.sentenceStart)
            for (s in startChars) for (e in endChars) {
                val bucket = m.byFirstLast[(s - 'a') * 26 + (e - 'a')]
                val limit = minOf(bucket.size, 2500)
                for (k in 0 until limit) {
                    val idx = bucket[k]
                    if (blockOff && m.isOffensive(idx)) continue
                    if (UserDictionary.isBlocked(m.lower[idx])) continue
                    val folded = Dictionary.foldAccents(m.norm[idx])
                    val before = out.size
                    evaluate(folded, m.logf[idx], m.contextBoost(pSlot, idx) + weights[mi])
                    if (out.size > before) {
                        // keep the best-scoring display form per folded spelling
                        val prevBest = display[folded]
                        if (prevBest == null) display[folded] = m.words[idx]
                    }
                }
            }
        }
        // learned words
        for ((l, entry) in UserDictionary.snapshotWords()) {
            val n = Dictionary.foldAccents(l.replace("'", ""))
            if (n.length < 2 || n.any { it !in 'a'..'z' }) continue
            if (n.first() !in startChars || n.last() !in endChars) continue
            evaluate(n, 9f + ln(1f + entry.second), 0.5f)
            display.putIfAbsent(n, entry.first)
        }
        return out.sortedByDescending { it.score }
            .map { c -> display[c.word] ?: c.word }
            .distinct()
            .take(max)
    }

    private fun approaches(path: List<Offset>, seq: List<Offset>, tol: Float): Boolean {
        var p = 0
        val tol2 = tol * tol
        for ((k, c) in seq.withIndex()) {
            // first and last letters are anchored to the trace ends
            if (k == 0) { if (dist2(path[0], c) > tol2 * 2.2f) return false; continue }
            if (k == seq.size - 1) { if (dist2(path.last(), c) > tol2 * 2.2f) return false; continue }
            var found = -1
            var i = p
            while (i < path.size) {
                if (dist2(path[i], c) < tol2) { found = i; break }
                i++
            }
            if (found < 0) return false
            var best = found; var bestD = dist2(path[found], c)
            var j = found + 1
            while (j < path.size) {
                val d = dist2(path[j], c)
                if (d < bestD) { best = j; bestD = d } else if (d > bestD * 1.5f + 1f) break
                j++
            }
            p = best
        }
        return true
    }

    private fun nearest(centers: Map<Char, Offset>, p: Offset, radius: Float, n: Int): List<Char> {
        val sorted = centers.entries.filter { it.key in 'a'..'z' }.sortedBy { dist2(it.value, p) }
        val within = sorted.filter { dist(it.value, p) <= radius }.take(n).map { it.key }
        return within.ifEmpty { sorted.take(1).map { it.key } }
    }

    private fun dedupe(pts: List<Offset>): List<Offset> {
        val out = ArrayList<Offset>(pts.size)
        for (p in pts) if (out.isEmpty() || dist2(out.last(), p) > 4f) out += p
        return out
    }

    fun resample(pts: List<Offset>, n: Int): List<Offset> {
        if (pts.size == 1) return List(n) { pts[0] }
        var total = 0f
        for (i in 1 until pts.size) total += dist(pts[i - 1], pts[i])
        if (total <= 0f) return List(n) { pts[0] }
        val step = total / (n - 1)
        val out = ArrayList<Offset>(n)
        out += pts[0]
        var acc = 0f
        var prev = pts[0]
        var i = 1
        while (i < pts.size && out.size < n) {
            val cur = pts[i]
            val d = dist(prev, cur)
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                val q = Offset(prev.x + t * (cur.x - prev.x), prev.y + t * (cur.y - prev.y))
                out += q
                prev = q
                acc = 0f
            } else {
                acc += d
                prev = cur
                i++
            }
        }
        while (out.size < n) out += pts.last()
        return out
    }

    private fun dist2(a: Offset, b: Offset): Float { val dx = a.x - b.x; val dy = a.y - b.y; return dx * dx + dy * dy }
    private fun dist(a: Offset, b: Offset) = sqrt(dist2(a, b))
}
