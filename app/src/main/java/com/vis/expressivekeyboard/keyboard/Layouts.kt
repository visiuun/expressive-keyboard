package com.vis.expressivekeyboard.keyboard

enum class KeyType { CHAR, SHIFT, DELETE, ENTER, SPACE, SYMBOLS, SYMBOLS2, ALPHA, EMOJI, NUMPAD_SYMBOLS }

data class Key(
    val type: KeyType,
    val label: String = "",
    val output: String = label,
    val width: Float = 1f,
    val popup: List<String> = emptyList(),
    val hint: String? = null,
    /** Function-styled key (tinted container) even though it outputs text. */
    val functional: Boolean = false,
) {
    val isLetter get() = type == KeyType.CHAR && label.length == 1 && label[0].isLetter()
}

enum class Mode { ALPHA, SYMBOLS, SYMBOLS2, NUMBER, PHONE }
enum class FieldKind { TEXT, EMAIL, URL, PASSWORD, NUMBER, PHONE }

object Layouts {
    val LETTER_LAYOUTS = linkedMapOf(
        "qwerty" to "QWERTY",
        "qwertz" to "QWERTZ",
        "azerty" to "AZERTY",
        "dvorak" to "Dvorak",
        "colemak" to "Colemak",
    )

    private val rowsFor = mapOf(
        "qwerty" to listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
        "qwertz" to listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
        "azerty" to listOf("azertyuiop", "qsdfghjklm", "wxcvbn"),
        "dvorak" to listOf("pyfgcrl", "aoeuidhtns", "qjkxbmwvz"),
        "colemak" to listOf("qwfpgjluy", "arstdhneio", "zxcvbkm"),
    )

    /**
     * Gboard's accent sets, in Gboard's priority order: the popup places the first one to the right
     * of the default, the next to the left, and so on (see showPopup), which reproduces Gboard's grids.
     */
    val ALTERNATES = mapOf(
        'a' to "àáâäæãåā", 'c' to "ç", 'e' to "éèêëē", 'i' to "íîïīì",
        'n' to "ñ", 'o' to "óôöòœøōõ", 's' to "ß", 'u' to "úûüùū",
    )

    private val SYMBOL_POPUPS = mapOf(
        "1" to listOf("¹", "½", "⅓", "¼", "⅛"), "2" to listOf("²", "⅔"), "3" to listOf("³", "¾", "⅜"),
        "4" to listOf("⁴"), "5" to listOf("⅝"), "7" to listOf("⅞"), "0" to listOf("ⁿ", "∅"),
        "$" to listOf("€", "£", "¥", "₹", "¢", "₩", "₽", "₿"), "-" to listOf("–", "—", "_", "·"),
        "+" to listOf("±"), "(" to listOf("<", "[", "{"), ")" to listOf(">", "]", "}"),
        "'" to listOf("‘", "’", "‚", "‹", "›"), "\"" to listOf("“", "”", "„", "«", "»"),
        "!" to listOf("¡"), "?" to listOf("¿"), "%" to listOf("‰"), "*" to listOf("★", "†", "‡"),
        "&" to listOf("§"), "=" to listOf("≠", "≈", "∞"), "#" to listOf("№"), "/" to listOf("\\"),
        "@" to listOf("©", "®"),
    )

    private val DIGITS = "1234567890"

    private fun ch(c: String, w: Float = 1f, popup: List<String>? = null, hint: String? = null) =
        Key(KeyType.CHAR, c, c, w, popup ?: SYMBOL_POPUPS[c] ?: emptyList(), hint)

    private fun letterRow(s: String, withHints: Boolean, firstRow: Boolean): List<Key> = s.mapIndexed { i, c ->
        val hint = if (firstRow && withHints && i < 10) DIGITS[i].toString() else null
        val alts = ALTERNATES[c]?.map { it.toString() } ?: emptyList()
        val popup = (listOfNotNull(hint) + alts)
        Key(KeyType.CHAR, c.toString(), c.toString(), 1f, popup, hint)
    }

    fun bottomRow(mode: Mode, field: FieldKind): List<Key> {
        val modeKey = when (mode) {
            Mode.ALPHA -> Key(KeyType.SYMBOLS, "?123", width = 1.5f)
            else -> Key(KeyType.ALPHA, "ABC", width = 1.5f)
        }
        val left = when (field) {
            FieldKind.EMAIL -> ch("@", popup = listOf("_", "-", "+"))
            FieldKind.URL -> ch("/", popup = listOf(":", "-", "_", "~"))
            else -> Key(KeyType.CHAR, ",", ",", 1f, listOf("{settings}", "{clipboard}", ";", ":", "'", "\""), functional = true)
        }
        val periodPopup = when (field) {
            FieldKind.EMAIL -> listOf(".com", ".net", ".org", ".edu", "!", "?")
            FieldKind.URL -> listOf(".com", ".org", ".net", ".io", "https://", "www.")
            else -> listOf("!", "?", "'", "\"", "-", ":", ";", "/", "@", "#", "&", "(", ")", "…")
        }
        val period = Key(KeyType.CHAR, ".", ".", 1f, periodPopup, functional = true)
        return listOf(
            modeKey,
            left,
            Key(KeyType.EMOJI, "emoji", width = 1f),
            Key(KeyType.SPACE, "space", " ", width = 4.0f),
            period,
            Key(KeyType.ENTER, "enter", "\n", width = 1.5f),
        )
    }

    fun rows(mode: Mode, field: FieldKind, layout: String, numberRow: Boolean, hints: Boolean): List<List<Key>> = when (mode) {
        Mode.ALPHA -> alpha(layout, field, numberRow, hints)
        Mode.SYMBOLS -> listOf(
            DIGITS.map { ch(it.toString()) },
            listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/").map { ch(it) },
            listOf(Key(KeyType.SYMBOLS2, "=\\<", width = 1.5f)) +
                listOf("*", "\"", "'", ":", ";", "!", "?").map { ch(it) } +
                Key(KeyType.DELETE, "del", width = 1.5f),
            bottomRow(mode, field),
        )
        Mode.SYMBOLS2 -> listOf(
            listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆").map { ch(it) },
            listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\").map { ch(it) },
            listOf(Key(KeyType.SYMBOLS, "?123", width = 1.5f)) +
                listOf("%", "©", "®", "™", "✓", "[", "]").map { ch(it) } +
                Key(KeyType.DELETE, "del", width = 1.5f),
            bottomRow(mode, field),
        )
        Mode.NUMBER -> listOf(
            listOf(ch("1"), ch("2"), ch("3"), Key(KeyType.CHAR, "-", "-", 1f, listOf("+", "*", "/"), functional = true)),
            listOf(ch("4"), ch("5"), ch("6"), Key(KeyType.SPACE, "space", " ", 1f)),
            listOf(ch("7"), ch("8"), ch("9"), Key(KeyType.DELETE, "del")),
            listOf(
                Key(KeyType.NUMPAD_SYMBOLS, "!?#", functional = true),
                Key(KeyType.CHAR, ",", ",", 1f, functional = true),
                ch("0"),
                Key(KeyType.CHAR, ".", ".", 1f, functional = true),
                Key(KeyType.ENTER, "enter", "\n"),
            ).let { r -> r.map { if (it.type == KeyType.ENTER) it.copy(width = 1f) else it.copy(width = 0.75f) } },
        )
        Mode.PHONE -> listOf(
            listOf(ch("1"), ch("2"), ch("3"), Key(KeyType.CHAR, "-", "-", 1f, functional = true)),
            listOf(ch("4"), ch("5"), ch("6"), Key(KeyType.SPACE, "space", " ", 1f)),
            listOf(ch("7"), ch("8"), ch("9"), Key(KeyType.DELETE, "del")),
            listOf(
                Key(KeyType.CHAR, "*", "*", 1f, listOf("#", "(", ")", "/", "N", ";", ","), functional = true),
                ch("0", popup = listOf("+")),
                Key(KeyType.CHAR, "+", "+", 1f, listOf("#", "(", ")"), functional = true),
                Key(KeyType.ENTER, "enter", "\n"),
            ),
        )
    }

    private fun alpha(layout: String, field: FieldKind, numberRow: Boolean, hints: Boolean): List<List<Key>> {
        val r = rowsFor[layout] ?: rowsFor.getValue("qwerty")
        val out = ArrayList<List<Key>>()
        if (numberRow) out += DIGITS.map { ch(it.toString()) }
        out += letterRow(r[0], hints && !numberRow, true)
        out += letterRow(r[1], false, false)
        val third = ArrayList<Key>()
        val thirdLetters = letterRow(r[2], false, false)
        val sideW = ((10f - thirdLetters.size) / 2f).coerceIn(1f, 1.5f)
        third += Key(KeyType.SHIFT, "shift", width = sideW)
        third += thirdLetters
        third += Key(KeyType.DELETE, "del", width = sideW)
        out += third
        out += bottomRow(Mode.ALPHA, field)
        return out
    }
}
