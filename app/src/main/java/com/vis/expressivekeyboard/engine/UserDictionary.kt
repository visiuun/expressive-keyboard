package com.vis.expressivekeyboard.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File

/** Learned words, learned word pairs and blocked suggestions. Persisted as JSON. */
object UserDictionary {
    private var file: File? = null
    private val words = HashMap<String, Pair<String, Int>>() // lower -> (display, count)
    private val bigrams = HashMap<String, HashMap<String, Int>>() // prevLower -> (word display -> count)
    private val blocked = HashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val saver = Runnable { save() }

    @Synchronized
    fun load(ctx: Context) {
        val f = File(ctx.filesDir, "user_dict.json")
        file = f
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            o.optJSONObject("w")?.let { w ->
                w.keys().forEach { k -> val e = w.getJSONArray(k); words[k] = e.getString(0) to e.getInt(1) }
            }
            o.optJSONObject("b")?.let { b ->
                b.keys().forEach { k ->
                    val inner = b.getJSONObject(k); val m = HashMap<String, Int>()
                    inner.keys().forEach { m[it] = inner.getInt(it) }
                    bigrams[k] = m
                }
            }
            o.optJSONArray("x")?.let { x -> for (i in 0 until x.length()) blocked += x.getString(i) }
        }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        val o = JSONObject()
        val w = JSONObject(); words.forEach { (k, v) -> w.put(k, org.json.JSONArray().put(v.first).put(v.second)) }
        val b = JSONObject(); bigrams.forEach { (k, m) -> b.put(k, JSONObject(m as Map<*, *>)) }
        o.put("w", w).put("b", b).put("x", org.json.JSONArray(blocked.toList()))
        runCatching { f.writeText(o.toString()) }
    }

    private fun scheduleSave() {
        handler.removeCallbacks(saver)
        handler.postDelayed(saver, 4000)
    }

    @Synchronized fun count(lower: String): Int = words[lower]?.second ?: 0
    @Synchronized fun display(lower: String): String? = words[lower]?.first
    @Synchronized fun isBlocked(lower: String) = lower in blocked
    @Synchronized fun bigram(prevLower: String, wordLower: String): Int =
        bigrams[prevLower]?.entries?.firstOrNull { it.key.lowercase() == wordLower }?.value ?: 0
    @Synchronized fun successors(prevLower: String): List<Pair<String, Int>> =
        bigrams[prevLower]?.map { it.key to it.value } ?: emptyList()
    @Synchronized fun snapshotWords(): List<Pair<String, Pair<String, Int>>> = words.map { it.key to it.value }
    @Synchronized fun learnedCount() = words.size

    /** Learn a word that is not in the main dictionary. */
    @Synchronized
    fun learnWord(word: String, weight: Int = 1) {
        if (word.length < 2 || word.length > 40 || !word.any { it.isLetter() }) return
        val l = word.lowercase()
        blocked.remove(l)
        val cur = words[l]
        words[l] = (cur?.first ?: word) to ((cur?.second ?: 0) + weight)
        scheduleSave()
    }

    @Synchronized
    fun learnBigram(prev: String, word: String) {
        if (word.isEmpty() || word.length > 40) return
        val m = bigrams.getOrPut(prev.lowercase()) { HashMap() }
        val key = m.keys.firstOrNull { it.lowercase() == word.lowercase() } ?: word
        m[key] = (m[key] ?: 0) + 1
        if (m.size > 60) {
            m.entries.minByOrNull { it.value }?.let { m.remove(it.key) }
        }
        if (bigrams.size > 20000) bigrams.keys.firstOrNull()?.let { bigrams.remove(it) }
        scheduleSave()
    }

    @Synchronized
    fun block(word: String) {
        val l = word.lowercase()
        blocked += l
        words.remove(l)
        bigrams.values.forEach { m -> m.keys.filter { it.lowercase() == l }.forEach { m.remove(it) } }
        scheduleSave()
    }

    @Synchronized
    fun clear() {
        words.clear(); bigrams.clear(); blocked.clear(); scheduleSave()
    }
}
