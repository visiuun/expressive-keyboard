package com.vis.expressivekeyboard.voice

import com.vis.expressivekeyboard.data.Prefs
import com.vis.expressivekeyboard.engine.Dictionary

/** Post-processing of recognised speech: spoken punctuation, automatic punctuation, spacing & casing. */
object VoiceText {
    // longest phrases first so "punto e virgola" wins over "punto"
    private val COMMANDS: List<Pair<String, String>> = listOf(
        // English
        "new paragraph" to "\n\n", "new line" to "\n", "next line" to "\n",
        "question mark" to "?", "exclamation mark" to "!", "exclamation point" to "!",
        "full stop" to ".", "period" to ".", "comma" to ",", "semicolon" to ";", "colon" to ":",
        "open parenthesis" to "(", "close parenthesis" to ")", "dash" to " –", "hyphen" to "-",
        // Italian
        "punto interrogativo" to "?", "punto di domanda" to "?", "punto esclamativo" to "!",
        "punto e virgola" to ";", "due punti" to ":", "nuovo paragrafo" to "\n\n", "nuova riga" to "\n",
        "a capo" to "\n", "virgola" to ",", "punto" to ".",
        // Spanish
        "signo de interrogación" to "?", "signo de exclamación" to "!", "punto y coma" to ";",
        "nueva línea" to "\n", "coma" to ",", "dos puntos" to ":",
        // French
        "point d'interrogation" to "?", "point d'exclamation" to "!", "point-virgule" to ";",
        "à la ligne" to "\n", "nouvelle ligne" to "\n", "virgule" to ",", "deux-points" to ":", "point" to ".",
        // German
        "fragezeichen" to "?", "ausrufezeichen" to "!", "neue zeile" to "\n", "neuer absatz" to "\n\n",
        "semikolon" to ";", "doppelpunkt" to ":", "komma" to ",", "punkt" to ".",
        // Portuguese
        "ponto de interrogação" to "?", "ponto de exclamação" to "!", "ponto e vírgula" to ";",
        "nova linha" to "\n", "vírgula" to ",", "dois pontos" to ":", "ponto" to ".",
    ).sortedByDescending { it.first.length }

    private val commandRegex: List<Pair<Regex, String>> = COMMANDS.map { (phrase, sym) ->
        // escape each word on its own: Regex.escape wraps text in \Q..\E, which would make \s+ literal
        val p = phrase.split(' ').joinToString("\\s+") { Regex.escape(it) }
        Regex("[\\s,.;:!?]*(?<![\\p{L}])$p(?![\\p{L}])[\\s,.;:!?]*", RegexOption.IGNORE_CASE) to sym
    }

    private val PUNCT = Regex("[.,!?;:¿¡…]")

    /**
     * Transducer models occasionally get stuck in a loop on noisy audio ("oh my god. oh my god. oh my god. ..."),
     * so a phrase of up to 4 words repeated 4+ times back-to-back is trimmed to 3 repeats.
     */
    internal fun collapseLoops(t: String): String {
        val words = t.split(' ').filter { it.isNotEmpty() }
        if (words.size < 4) return t
        val key = words.map { w -> w.lowercase().trim { !it.isLetterOrDigit() } }
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var cut = false
            for (n in 1..4) {
                if (i + n > words.size) break
                var reps = 1
                while (i + (reps + 1) * n <= words.size && (0 until n).all { key[i + reps * n + it] == key[i + it] }) reps++
                if (reps >= 4) {
                    repeat(3 * n) { out += words[i + it] }
                    i += reps * n
                    cut = true
                    break
                }
            }
            if (!cut) { out += words[i]; i++ }
        }
        return out.joinToString(" ")
    }

    /**
     * @param raw recogniser output
     * @param before text before the cursor (to decide spacing/capitalisation)
     */
    fun format(raw: String, before: String): String {
        var t = collapseLoops(raw.trim())
        if (t.isEmpty()) return ""
        val spoken = Prefs.voiceSpokenPunct.value
        if (!Prefs.voiceAutoPunct.value) {
            t = PUNCT.replace(t, "").replace(Regex("\\s+"), " ").trim()
        }
        if (spoken) {
            val tokens = HashMap<String, String>()
            var k = 0
            for ((re, sym) in commandRegex) {
                t = re.replace(t) { _ ->
                    val key = "\u0000${k++}\u0000"
                    tokens[key] = sym
                    key
                }
            }
            for ((key, sym) in tokens) {
                val glue = when {
                    sym.startsWith("\n") -> sym
                    sym == "(" -> " ("
                    sym == "-" -> "-"
                    else -> "$sym "
                }
                t = t.replace(key, glue)
            }
            t = t.replace(Regex("[ \\t]+\n"), "\n").replace(Regex("\n[ \\t]+"), "\n").replace(Regex(" {2,}"), " ").trim(' ')
        }
        if (t.isBlank()) return ""
        // capitalise after sentence ends inside the dictation
        t = Regex("([.!?]\\s+|\\n)(\\p{Ll})").replace(t) { m -> m.groupValues[1] + m.groupValues[2].uppercase() }

        val trimmed = before.trimEnd(' ')
        val sentenceStart = trimmed.isEmpty() || trimmed.last() in ".!?\n" || before.endsWith("\n")
        val first = t.takeWhile { it.isLetter() || it == '\'' }
        t = if (sentenceStart) {
            t.replaceFirstChar { it.uppercaseChar() }
        } else if (first.isNotEmpty() && first[0].isUpperCase() && first.drop(1).all { it.isLowerCase() || it == '\'' } &&
            first != "I" && !first.startsWith("I'") && Dictionary.isValid(first.lowercase())
        ) {
            t.replaceFirstChar { it.lowercaseChar() }
        } else t

        val needsSpace = before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in "([{\"'“‘" &&
            !(t.firstOrNull()?.let { it in ".,!?;:)" } ?: false)
        return (if (needsSpace) " " else "") + t
    }
}
