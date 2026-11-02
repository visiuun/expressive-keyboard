package com.vis.expressivekeyboard.engine

import android.content.Context
import android.graphics.Paint
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlin.concurrent.thread

data class Emoji(val value: String, val name: String, val tones: List<String>)
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
                            list += Emoji(p[0], p[1], tones)
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
            val words = e.name.split(' ', '-', ':')
            if (if (pass == 0) ql in words else words.any { it in forms }) out += e.value
        }
        return out.take(max)
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
