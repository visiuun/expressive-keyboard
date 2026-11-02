package com.vis.expressivekeyboard.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.mutableStateOf

/** Observable preference backed by SharedPreferences. Reads are Compose snapshot state. */
class Pref<T>(val key: String, val default: T) {
    private val state = mutableStateOf(default)
    var value: T
        get() = state.value
        set(v) {
            if (state.value == v) return
            state.value = v
            Prefs.write(key, v)
        }

    @Suppress("UNCHECKED_CAST")
    internal fun load(sp: SharedPreferences) {
        state.value = when (default) {
            is Boolean -> sp.getBoolean(key, default) as T
            is Int -> sp.getInt(key, default) as T
            is Float -> sp.getFloat(key, default) as T
            is String -> (sp.getString(key, default) ?: default) as T
            else -> default
        }
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences
    private val all = mutableListOf<Pref<*>>()

    private fun <T> pref(key: String, default: T) = Pref(key, default).also { all += it }

    // Appearance
    val theme = pref("theme", if (Build.VERSION.SDK_INT >= 31) "dynamic" else "lavender")
    val darkMode = pref("dark_mode", 2) // 0 system, 1 light, 2 dark
    val oled = pref("oled", true)
    val keyBorders = pref("key_borders", true)
    val keyHeight = pref("key_height", 1.0f)
    val numberRow = pref("number_row", false)
    val keyPopup = pref("key_popup", true)
    val layout = pref("layout", "qwerty")
    val oneHanded = pref("one_handed", 0) // 0 off, 1 left, 2 right
    val showHints = pref("show_hints", true)
    val roundness = pref("roundness", 0.65555555f)
    val enterShape = pref("enter_shape", "cookie9")
    val spaceShape = pref("space_shape", "rounded") // rounded | square | pill
    val showEmojiKey = pref("show_emoji_key", true)
    val bottomOffset = pref("bottom_offset", 1.0f) // fraction of the nav-bar area kept free
    val funcShape = pref("func_shape", "pill") // rounded | pill | square
    val customSeed = pref("custom_seed", 0xFFE040FB.toInt())
    val customStyle = pref("custom_style", "Vibrant")
    val labelSize = pref("label_size", 1.0f)
    val boldLabels = pref("bold_labels", true)

    // Typing
    val autoCap = pref("auto_cap", true)
    val doubleSpacePeriod = pref("double_space_period", true)
    val haptics = pref("haptics", true)
    val hapticStrength = pref("haptic_strength", 0.45f)
    val sound = pref("sound", false)
    val soundVolume = pref("sound_volume", 0.4f)
    val swipeTyping = pref("swipe_typing", true)
    val swipeTrail = pref("swipe_trail", true)
    val longPressDelay = pref("long_press_delay", 300)
    val spaceCursor = pref("space_cursor", true)
    val deleteSwipe = pref("delete_swipe", true)

    // Correction
    val suggestions = pref("suggestions", true)
    val autocorrect = pref("autocorrect", true)
    val autoSpace = pref("auto_space", true)
    val blockOffensive = pref("block_offensive", true)
    val learnWords = pref("learn_words", true)
    val clipboardHistory = pref("clipboard_history", true)

    // Languages (comma separated, up to 3)
    val languages = pref("languages", "")

    // Voice typing
    val voiceModel = pref("voice_model", "")
    val voiceAutoPunct = pref("voice_auto_punct", true)
    val voiceLanguage = pref("voice_language", "auto") // auto or a language code
    val voiceSpokenPunct = pref("voice_spoken_punct", true)
    val voiceThreads = pref("voice_threads", 0) // 0 = auto
    val voicePreload = pref("voice_preload", true)

    // AI
    val aiEnabled = pref("ai_enabled", true)
    val aiSentenceFix = pref("ai_sentence_fix", false)
    val aiAutoApply = pref("ai_auto_apply", false)
    val aiPredictions = pref("ai_predictions", false)
    val aiScope = pref("ai_scope", 0) // 0 sentence, 1 whole text
    val aiTemplate = pref("ai_template", "auto")
    val aiBackend = pref("ai_backend", "cpu")
    val modelPath = pref("model_path", "")
    val modelId = pref("model_id", "")

    // Updates
    val autoUpdate = pref("auto_update", true)

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        all.forEach { it.load(sp) }
        if (languages.value.isEmpty()) {
            // default: English + the phone's other system languages we support
            val supported = setOf("en", "it", "es", "fr", "de", "pt")
            val locales = ctx.resources.configuration.locales
            val codes = LinkedHashSet<String>()
            for (i in 0 until locales.size()) locales[i].language.takeIf { it in supported }?.let { codes += it }
            codes += "en"
            languages.value = codes.take(3).joinToString(",")
        }
    }

    internal fun write(key: String, v: Any?) {
        val e = sp.edit()
        when (v) {
            is Boolean -> e.putBoolean(key, v)
            is Int -> e.putInt(key, v)
            is Float -> e.putFloat(key, v)
            is String -> e.putString(key, v)
        }
        e.apply()
    }

    fun raw(): SharedPreferences = sp
}
