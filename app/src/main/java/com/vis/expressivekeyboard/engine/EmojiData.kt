package com.vis.expressivekeyboard.engine

import android.content.Context
import android.graphics.Paint
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlin.concurrent.thread

data class Emoji(val value: String, val name: String, val tones: List<String>, val keywords: List<String> = emptyList()) {
    /** Words of the official name, lower-case ("face with tears of joy"). */
    val nameWords: List<String> = name.lowercase().split(' ', '-', ':', ',').filter { it.isNotEmpty() }
}
data class EmojiGroup(val name: String, val emojis: List<Emoji>)

object EmojiData {
    val groups = mutableStateOf<List<EmojiGroup>>(emptyList())
    val recents = mutableStateListOf<String>()
    private lateinit var ctx: Context

    fun loadAsync(c: Context) {
        ctx = c.applicationContext
        ctx.getSharedPreferences("emoji", Context.MODE_PRIVATE).getString("recents", null)
            ?.split('\u0000')?.filter { it.isNotEmpty() }?.let { recents.addAll(it) }
        thread(name = "emoji-load") {
            runCatching {
                val paint = Paint()
                val out = ArrayList<EmojiGroup>()
                var name = ""
                var list = ArrayList<Emoji>()
                ctx.assets.open("emoji.txt").bufferedReader().forEachLine { line ->
                    if (line.startsWith("#") && !line.contains('	')) {
                        if (list.isNotEmpty()) out += EmojiGroup(name, list)
                        name = line.substring(1); list = ArrayList()
                    } else {
                        val p = line.split('\t')
                        if (p.size >= 2 && paint.hasGlyph(p[0])) {
                            val tones = p.getOrNull(2)?.split(',')?.filter { it.isNotEmpty() && paint.hasGlyph(it) } ?: emptyList()
                            val kw = p.getOrNull(3)?.split('|')?.filter { it.isNotEmpty() } ?: emptyList()
                            list += Emoji(p[0], p[1], tones, kw)
                        }
                    }
                }
                if (list.isNotEmpty()) out += EmojiGroup(name, list)
                groups.value = out
            }
        }
    }

    fun use(e: String) {
        recents.remove(e)
        recents.add(0, e)
        while (recents.size > 32) recents.removeAt(recents.lastIndex)
        ctx.getSharedPreferences("emoji", Context.MODE_PRIVATE).edit()
            .putString("recents", recents.joinToString("\u0000")).apply()
    }

    /** Name search used for emoji suggestions while typing. */
    fun search(q: String, max: Int = 40): List<String> {
        if (q.length < 2) return emptyList()
        val ql = q.lowercase()
        val all = groups.value.asSequence().flatMap { it.emojis.asSequence() }
        val out = LinkedHashSet<String>()
        // 1. Curated favourite for common words ("cry" -> 😭, not the first "crying" match).
        ALIASES[ql]?.let { a -> if (all.any { it.value == a }) out += a }
        // 2. Exact word in the emoji name, then 3. inflected forms ("cry" -> "crying", "laugh" -> "laughing").
        val forms = setOf(ql + "s", ql + "es", ql + "ing", ql + "ed", ql + "y", ql.removeSuffix("e") + "ing",
            ql.removeSuffix("y") + "ies", ql.removeSuffix("y") + "ied")
        for (pass in 0..1) for (e in all) {
            if (out.size >= max) return out.toList()
            val words = e.nameWords
            if (if (pass == 0) ql in words || ql in e.keywords else words.any { it in forms }) out += e.value
        }
        return out.take(max)
    }

    /**
     * Search used by the emoji panel's search box (Gboard style): matches as you type, from the first
     * letter, against the emoji's name and its CLDR keywords ("lol" -> 😂, "cel" -> 🎉). Every word
     * of the query has to match; results are ranked by how well they match.
     */
    fun searchPanel(q: String, max: Int = 120): List<Emoji> {
        val tokens = q.lowercase().trim().split(' ', '\t', '\n').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val all = groups.value.flatMap { it.emojis }
        val alias = if (tokens.size == 1) ALIASES[tokens[0]] else null
        val joined = tokens.joinToString(" ")
        val scored = ArrayList<Pair<Emoji, Int>>()
        all.forEachIndexed { i, e ->
            var score = 0
            for (t in tokens) {
                val s = when {
                    t in e.nameWords -> 60
                    t in e.keywords -> 45
                    e.nameWords.any { it.startsWith(t) } -> 30
                    e.keywords.any { k -> k.startsWith(t) || k.split(' ').any { it.startsWith(t) } } -> 20
                    else -> 0
                }
                if (s == 0) { score = 0; break }
                score += s
            }
            if (score == 0) return@forEachIndexed
            val name = e.name.lowercase()
            if (name == joined) score += 100 else if (name.startsWith(joined)) score += 25
            if (e.value == alias) score += 1000
            // fewer name words = more "canonical" (cat > cat with wry smile); earlier groups first on ties
            scored += e to (score * 10_000 - e.nameWords.size * 100 - i / 100)
        }
        return scored.sortedByDescending { it.second }.map { it.first }.take(max)
    }

    fun isAlias(q: String) = q.lowercase() in ALIASES

    private val ALIASES = mapOf(
        "cry" to "😭", "crying" to "😭", "cried" to "😭", "sob" to "😭", "sad" to "😢", "tears" to "😭",
        "lol" to "😂", "haha" to "😂", "hahaha" to "😂", "laugh" to "😂", "laughing" to "😂", "funny" to "😂",
        "lmao" to "🤣", "rofl" to "🤣", "dead" to "💀", "skull" to "💀",
        "love" to "❤️", "heart" to "❤️", "loved" to "❤️", "luv" to "❤️", "ily" to "❤️",
        "happy" to "😊", "smile" to "😊", "glad" to "😊", "blush" to "😊",
        "kiss" to "😘", "kisses" to "😘", "xoxo" to "😘",
        "angry" to "😠", "mad" to "😡", "rage" to "😡", "furious" to "😡",
        "cool" to "😎", "think" to "🤔", "thinking" to "🤔", "hmm" to "🤔",
        "fire" to "🔥", "lit" to "🔥", "hot" to "🥵", "cold" to "🥶",
        "ok" to "👌", "okay" to "👌", "yes" to "👍", "like" to "👍", "good" to "👍", "no" to "👎",
        "thanks" to "🙏", "thank" to "🙏", "please" to "🙏", "pray" to "🙏",
        "party" to "🥳", "congrats" to "🎉", "celebrate" to "🎉", "birthday" to "🎂",
        "wow" to "😮", "omg" to "😱", "shock" to "😱", "scared" to "😨",
        "sleep" to "😴", "tired" to "😴", "sleepy" to "😴",
        "sick" to "🤒", "ugh" to "😩", "sorry" to "😔", "wink" to "😉", "eyes" to "👀",
        "clap" to "👏", "strong" to "💪", "muscle" to "💪", "hi" to "👋", "hello" to "👋", "bye" to "👋",
        "money" to "💰", "rich" to "🤑", "star" to "⭐", "sun" to "☀️", "rain" to "🌧️",
        "pizza" to "🍕", "coffee" to "☕", "beer" to "🍺", "cake" to "🍰",
        "dog" to "🐶", "cat" to "🐱", "poop" to "💩", "100" to "💯", "perfect" to "💯",
        "nervous" to "😬", "awkward" to "😬", "shrug" to "🤷", "facepalm" to "🤦", "hug" to "🤗",
        "yum" to "😋", "nerd" to "🤓", "crazy" to "🤪", "silly" to "🤪", "sweat" to "😅",
    )
}
