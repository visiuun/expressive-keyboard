package com.vis.expressivekeyboard.keyboard

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vis.expressivekeyboard.ai.AiEngine
import com.vis.expressivekeyboard.ai.AiTask
import com.vis.expressivekeyboard.data.ClipboardStore
import com.vis.expressivekeyboard.data.Prefs
import com.vis.expressivekeyboard.engine.Ctx
import com.vis.expressivekeyboard.engine.Dictionary
import com.vis.expressivekeyboard.engine.EmojiData
import com.vis.expressivekeyboard.engine.SuggestResult
import com.vis.expressivekeyboard.engine.UserDictionary
import com.vis.expressivekeyboard.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val APOS: Char = 39.toChar() // apostrophe
private val WORD_RE = Regex("[\\p{L}']+")
private val WORD_END_RE = Regex("[\\p{L}']+$")

enum class Shift { OFF, ONCE, LOCK }
enum class Panel { KEYS, EMOJI, CLIPBOARD, AI, VOICE }
enum class StripType { NORMAL, AUTO, LITERAL, PREDICTION, AI, EMOJI }

data class StripItem(val text: String, val type: StripType)

data class AiChip(val kind: Kind, val original: String = "", val result: String = "", val absStart: Int = 0) {
    enum class Kind { WORKING, SUGGEST, UNDO, OK, ERROR }
}

/** Result shown in the AI panel. */
data class AiPanelState(
    val busy: Boolean = false,
    val task: AiTask? = null,
    val original: String = "",
    val result: String? = null,
    val absStart: Int = 0,
    val error: String? = null,
)

class KeyboardController(private val ime: ExpressiveIME) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var mode by mutableStateOf(Mode.ALPHA)
    var shift by mutableStateOf(Shift.OFF)
    var panel by mutableStateOf(Panel.KEYS)
    var field by mutableStateOf(FieldKind.TEXT)
    var enterAction by mutableIntStateOf(EditorInfo.IME_ACTION_UNSPECIFIED)
    var enterIsNewline by mutableStateOf(true)
    var strip by mutableStateOf<List<StripItem>>(emptyList())
    var composingText by mutableStateOf("")
    var forceToolbar by mutableStateOf(false)
    var aiChip by mutableStateOf<AiChip?>(null)
    var aiPanel by mutableStateOf(AiPanelState())
    var visibleTick by mutableIntStateOf(0)

    private var baseMode = Mode.ALPHA
    /** Gboard rule: once a key has been typed on a symbols page, the next space/enter returns to letters. */
    private var symbolTyped = false
    private var suggestionsAllowed = true
    private var autocorrectAllowed = true
    private val composing = StringBuilder()
    private var compStart = 0
    private var cursor = 0
    private var selStart = 0
    private var selEnd = 0
    private var resync = false
    private val recentCursors = ArrayDeque<Int>()
    private var lastAutoSpace = false
    private var lastSpaceTime = 0L
    private var lastShiftTap = 0L
    private var composingFromGesture = false
    private var gestureAlternatives: List<String> = emptyList()
    private var lastResult: SuggestResult? = null
    private var suppressAutocorrectFor: String? = null
    private var lastCorrection: Correction? = null
    private var suggestJob: Job? = null
    private var aiJob: Job? = null
    private var chipJob: Job? = null
    private var deleteRepeats = 0

    private data class Correction(val original: String, val corrected: String, val sep: String)

    private val ic: InputConnection? get() = ime.currentInputConnection
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) ime.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ime.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val audio by lazy { ime.getSystemService(AudioManager::class.java) }

    // ------------------------------------------------------------------ lifecycle

    fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val flags = info.inputType and InputType.TYPE_MASK_FLAGS
        field = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> FieldKind.NUMBER
            InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
                InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldKind.PASSWORD
                else -> FieldKind.TEXT
            }
            else -> FieldKind.TEXT
        }
        baseMode = when (field) {
            FieldKind.NUMBER -> Mode.NUMBER
            FieldKind.PHONE -> Mode.PHONE
            else -> Mode.ALPHA
        }
        if (!restarting || mode == Mode.NUMBER || mode == Mode.PHONE || baseMode != Mode.ALPHA) { mode = baseMode; symbolTyped = false }
        val noSuggest = flags and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        suggestionsAllowed = cls == InputType.TYPE_CLASS_TEXT && field != FieldKind.PASSWORD &&
            variation != InputType.TYPE_TEXT_VARIATION_FILTER
        autocorrectAllowed = suggestionsAllowed && field == FieldKind.TEXT && !noSuggest &&
            variation != InputType.TYPE_TEXT_VARIATION_PERSON_NAME &&
            variation != InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val multiline = flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        enterAction = action
        enterIsNewline = noEnterAction || action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        if (multiline && noEnterAction) enterIsNewline = true

        composing.clear(); composingText = ""
        composingFromGesture = false
        cursor = info.initialSelStart.coerceAtLeast(0)
        selStart = info.initialSelStart; selEnd = info.initialSelEnd
        compStart = cursor
        recentCursors.clear()
        lastCorrection = null
        lastAutoSpace = false
        suppressAutocorrectFor = null
        if (!restarting) {
            panel = Panel.KEYS
            aiChip = null
            aiPanel = AiPanelState()
            forceToolbar = false
        }
        if (shift != Shift.LOCK || !restarting) shift = Shift.OFF
        updateShift()
        refreshSuggestions()
        visibleTick++
        checkRecentShot()
        if (Prefs.aiEnabled.value && (Prefs.aiSentenceFix.value || Prefs.aiPredictions.value)) AiEngine.preload()
        if (Prefs.voicePreload.value && com.vis.expressivekeyboard.voice.VoiceEngine.hasPermission()) com.vis.expressivekeyboard.voice.VoiceEngine.preload()
    }

    fun onFinishInputView() {
        if (com.vis.expressivekeyboard.voice.VoiceEngine.isListening) stopVoice()
        if (panel == Panel.VOICE) panel = Panel.KEYS
        com.vis.expressivekeyboard.voice.VoiceEngine.scheduleUnload()
        if (composing.isNotEmpty()) {
            ic?.finishComposingText()
            composing.clear(); composingText = ""
        }
        suggestJob?.cancel()
    }

    fun onUpdateSelection(newSS: Int, newSE: Int, candS: Int, candE: Int) {
        selStart = newSS; selEnd = newSE
        if (deleteSelAnchor >= 0) return // swipe-to-delete in progress: don't react to our own selection changes
        if (resync) {
            if (!editorIsAt(newSS, newSE)) return // stale; wait for the notification that matches the editor
            resync = false
            cursor = newSS
            if (composing.isEmpty()) {
                afterExternalMove(newSS, newSE)
            }
            return
        }
        if (voiceComposing && composing.isNotEmpty()) {
            // dictation: partials replace the composing text every few hundred ms, so notifications
            // arrive late and some editors never report a candidate range. Anything inside our region is an echo.
            val expectedEnd = compStart + composing.length
            if (newSS == newSE && newSS in compStart..expectedEnd) return
            if (!editorIsAt(newSS, newSE)) return
            // a real move while dictating: keep what was said, carry on at the new spot
            cursor = newSS
            composing.clear(); composingText = ""
            ic?.finishComposingText()
            voiceComposing = false
            lastVoicePartial = ""
            updateShift()
            return
        }
        if (composing.isNotEmpty()) {
            val expectedEnd = compStart + composing.length
            // echo of our own typing: cursor sits at the end of our composing region
            val echo = newSS == newSE && candS == compStart && newSS == candE && newSS in compStart..expectedEnd
            if (echo) return
            // anything else is either a late echo of an older edit or a real move: ask the editor
            if (!editorIsAt(newSS, newSE)) return
            // the user moved the cursor (or the editor dropped the composition)
            cursor = newSS
            composing.clear(); composingText = ""
            composingFromGesture = false
            ic?.finishComposingText()
            lastCorrection = null
            lastAutoSpace = false
            afterExternalMove(newSS, newSE)
            return
        }
        if (newSS == newSE && newSS == cursor) return
        // a stale notification for an older edit of ours is ignored; a real tap is handled
        if (!editorIsAt(newSS, newSE)) return
        cursor = newSS
        lastCorrection = null
        lastAutoSpace = false
        afterExternalMove(newSS, newSE)
    }

    /**
     * True if the editor's selection really is [ss]..[se] right now. Selection notifications can
     * arrive late (for edits we already superseded), so we confirm with the editor instead of
     * guessing. If the editor can't tell us, assume the move is real (the safe choice).
     */
    private fun editorIsAt(ss: Int, se: Int): Boolean {
        val et = runCatching {
            ic?.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply { hintMaxChars = 256 }, 0)
        }.getOrNull() ?: return true
        if (et.selectionStart < 0) return true
        val a = et.startOffset + et.selectionStart
        val b = et.startOffset + et.selectionEnd
        return minOf(a, b) == minOf(ss, se) && maxOf(a, b) == maxOf(ss, se)
    }

    private fun afterExternalMove(newSS: Int, newSE: Int) {
        if (newSS == newSE && suggestionsAllowed && Prefs.suggestions.value) {
            val conn = ic
            val before = conn?.getTextBeforeCursor(48, 0)?.toString() ?: ""
            val after = conn?.getTextAfterCursor(1, 0)?.toString() ?: ""
            if (before.isNotEmpty() && before.last().isLetter() && (after.isEmpty() || !after[0].isLetterOrDigit())) {
                val word = Regex("[\\p{L}']+$").find(before)?.value?.trimStart('\'')
                if (word != null && word.length in 1..40 && conn != null) {
                    compStart = newSS - word.length
                    conn.setComposingRegion(compStart, newSS)
                    composing.append(word); composingText = word
                }
            }
        }
        updateShift()
        refreshSuggestions()
    }

    // ------------------------------------------------------------------ edit primitives (keep the cursor model in sync)

    private fun mark() {
        recentCursors.addLast(cursor)
        while (recentCursors.size > 24) recentCursors.removeFirst()
    }

    private fun setComposing(text: CharSequence) {
        val c = ic ?: return
        if (composing.isEmpty() && text.isNotEmpty()) compStart = cursor
        c.setComposingText(text, 1)
        cursor = compStart + text.length
        mark()
    }

    private fun commit(text: CharSequence) {
        val c = ic ?: return
        val hadComposing = composing.isNotEmpty()
        c.commitText(text, 1)
        cursor = if (hadComposing) compStart + text.length else cursor + text.length
        composing.clear(); composingText = ""
        mark()
    }

    private fun deleteBefore(n: Int) {
        val c = ic ?: return
        c.deleteSurroundingText(n, 0)
        cursor = (cursor - n).coerceAtLeast(0)
        mark()
    }

    private fun textBefore(n: Int): String = ic?.getTextBeforeCursor(n, 0)?.toString() ?: ""

    // ------------------------------------------------------------------ feedback

    fun feedback(view: View, key: Key?) {
        if (Prefs.haptics.value) {
            val s = Prefs.hapticStrength.value
            val v = vibrator
            if (v != null && v.hasVibrator() && v.hasAmplitudeControl()) {
                val amp = (40 + s * 215).toInt().coerceIn(1, 255)
                val dur = (6 + s * 14).toLong()
                runCatching { v.vibrate(VibrationEffect.createOneShot(dur, amp)) }
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
        if (Prefs.sound.value) {
            val fx = when (key?.type) {
                KeyType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio?.playSoundEffect(fx, Prefs.soundVolume.value)
        }
    }

    // ------------------------------------------------------------------ key handling

    fun labelFor(key: Key): String {
        if (!key.isLetter) return key.label
        return when (shift) {
            Shift.OFF -> key.label
            else -> key.label.uppercase()
        }
    }

    private fun inSymbols() = mode == Mode.SYMBOLS || mode == Mode.SYMBOLS2

    private fun leaveSymbolsIfTyped() {
        if (inSymbols() && symbolTyped) {
            symbolTyped = false
            mode = baseMode
            updateShift()
        }
    }

    fun onKey(key: Key) {
        when (key.type) {
            KeyType.CHAR -> { onText(labelFor(key).let { if (key.isLetter) it else key.output }); if (inSymbols()) symbolTyped = true }
            KeyType.SPACE -> { onSpace(); leaveSymbolsIfTyped() }
            KeyType.ENTER -> { onEnter(); leaveSymbolsIfTyped() }
            KeyType.DELETE -> {}
            KeyType.SHIFT -> {}
            KeyType.SYMBOLS, KeyType.NUMPAD_SYMBOLS -> { if (!inSymbols()) symbolTyped = false; mode = Mode.SYMBOLS }
            KeyType.SYMBOLS2 -> { if (!inSymbols()) symbolTyped = false; mode = Mode.SYMBOLS2 }
            KeyType.ALPHA -> { symbolTyped = false; mode = if (baseMode == Mode.ALPHA) Mode.ALPHA else baseMode; updateShift() }
            KeyType.EMOJI -> openPanel(Panel.EMOJI)
        }
    }

    fun onPopupChoice(choice: String) {
        when (choice) {
            "{settings}" -> openSettings()
            "{clipboard}" -> openPanel(Panel.CLIPBOARD)
            "{emoji}" -> openPanel(Panel.EMOJI)
            else -> {
                val text = if (choice.length == 1 && choice[0].isLetter() && shift != Shift.OFF) choice.uppercase() else choice
                onText(text)
                if (inSymbols()) symbolTyped = true
            }
        }
    }

    fun openPanel(p: Panel) {
        if (p == Panel.VOICE) { if (panel == Panel.VOICE) { stopVoice(); panel = Panel.KEYS } else startVoice(); return }
        if (panel == Panel.VOICE) stopVoice()
        if (p != Panel.KEYS) finishComposingQuietly()
        panel = if (panel == p) Panel.KEYS else p
    }

    // ------------------------------------------------------------------ voice typing

    var voiceNeedsPermission by mutableStateOf(false)
    var voicePartialText by mutableStateOf("")
    private var voiceComposing = false
    private var lastVoicePartial = ""

    /**
     * If an editor dropped our composition (some chat apps do), the previous partial is still sitting
     * before the cursor as plain text. Re-attach to it so the next partial/final replaces it instead
     * of being appended after it.
     */
    private fun reclaimVoiceComposing() {
        if (!voiceComposing || composing.isNotEmpty() || lastVoicePartial.isEmpty()) return
        val conn = ic ?: return
        val b = textBefore(lastVoicePartial.length + 4)
        if (b.endsWith(lastVoicePartial)) {
            compStart = cursor - lastVoicePartial.length
            conn.setComposingRegion(compStart, cursor)
            composing.append(lastVoicePartial); composingText = lastVoicePartial
        } else {
            voiceComposing = false
            lastVoicePartial = ""
        }
    }

    private val voiceListener = object : com.vis.expressivekeyboard.voice.VoiceListener {
        override fun onPartial(text: String) = showVoicePartial(text)
        override fun onFinal(text: String) = commitVoice(text)
        override fun onStopped() {
            if (voiceComposing && composing.isNotEmpty()) { ic?.finishComposingText(); composing.clear(); composingText = "" }
            voiceComposing = false
            lastVoicePartial = ""
            voicePartialText = ""
        }
    }

    fun startVoice() {
        panel = Panel.VOICE
        voiceNeedsPermission = !com.vis.expressivekeyboard.voice.VoiceEngine.hasPermission()
        if (voiceNeedsPermission || com.vis.expressivekeyboard.voice.VoiceEngine.activeModel() == null) return
        finishComposingQuietly()
        com.vis.expressivekeyboard.voice.VoiceEngine.start(voiceListener)
    }

    fun stopVoice() = com.vis.expressivekeyboard.voice.VoiceEngine.stop()

    fun toggleVoice() {
        if (com.vis.expressivekeyboard.voice.VoiceEngine.isListening) stopVoice() else startVoice()
    }

    private fun textBeforeVoice(): String {
        val b = textBefore(200)
        return if (voiceComposing && composing.isNotEmpty() && b.endsWith(composing)) b.dropLast(composing.length) else b
    }

    private fun showVoicePartial(text: String) {
        reclaimVoiceComposing()
        val formatted = com.vis.expressivekeyboard.voice.VoiceText.format(text, textBeforeVoice())
        voicePartialText = text
        if (formatted.isEmpty()) return
        if (!voiceComposing) {
            if (composing.isNotEmpty()) finishComposingQuietly()
            compStart = cursor // the composing region for this utterance starts here
        }
        composing.setLength(0); composing.append(formatted)
        setComposing(composing)
        voiceComposing = true
        lastVoicePartial = formatted
    }

    private fun commitVoice(text: String) {
        val conn = ic ?: return
        reclaimVoiceComposing()
        val formatted = com.vis.expressivekeyboard.voice.VoiceText.format(text, textBeforeVoice())
        conn.beginBatchEdit()
        if (formatted.isEmpty()) {
            if (voiceComposing) commit("")
        } else {
            if (!voiceComposing && composing.isNotEmpty()) finishComposingQuietly()
            commit(formatted)
        }
        conn.endBatchEdit()
        voiceComposing = false
        lastVoicePartial = ""
        voicePartialText = ""
        lastAutoSpace = false
        lastCorrection = null
        updateShift()
    }

    fun requestMicPermission() {
        val i = Intent(ime, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("request_mic", true).putExtra("tab", 3)
        ime.startActivity(i)
    }

    fun openSettingsTab(tab: Int) {
        finishComposingQuietly()
        ime.startActivity(Intent(ime, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("tab", tab))
    }

    fun openSettings() {
        finishComposingQuietly()
        val i = Intent(ime, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ime.startActivity(i)
    }

    fun hideKeyboard() {
        finishComposingQuietly()
        ime.requestHideSelf(0)
    }

    fun switchKeyboard() {
        ime.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showInputMethodPicker()
    }

    private fun finishComposingQuietly() {
        if (composing.isNotEmpty()) {
            ic?.finishComposingText()
            composing.clear(); composingText = ""
            composingFromGesture = false
            refreshSuggestions()
        }
    }

    fun onShiftDown() {
        val now = System.currentTimeMillis()
        shift = when (shift) {
            Shift.OFF -> Shift.ONCE
            Shift.ONCE -> if (now - lastShiftTap < 350) Shift.LOCK else Shift.OFF
            Shift.LOCK -> Shift.OFF
        }
        lastShiftTap = now
    }

    fun onShiftLongPress() { shift = Shift.LOCK }

    private fun isWordChar(ch: Char): Boolean =
        ch.isLetter() || (composing.isNotEmpty() && (ch == '\'' || ch == '’' || ch.isDigit() || ch == '-' && false))

    fun onText(text: String) {
        val conn = ic ?: return
        lastCorrection = null
        if (text.isEmpty()) return
        if (!suggestionsAllowed) {
            conn.commitText(text, 1); cursor += text.length; mark()
            if (shift == Shift.ONCE) shift = Shift.OFF
            updateShift()
            return
        }
        if (text.length == 1 && isWordChar(text[0])) {
            if (composingFromGesture) {
                conn.beginBatchEdit()
                commitWord(autocorrect = false)
                commit(" ")
                conn.endBatchEdit()
            }
            composing.append(if (text == "’") "'" else text)
            composingText = composing.toString()
            setComposing(composing)
            lastAutoSpace = false
            if (shift == Shift.ONCE) shift = Shift.OFF
            refreshSuggestions()
            return
        }
        onSeparator(text)
    }

    private fun onSeparator(sep: String) {
        val conn = ic ?: return
        val isPunct = sep.length == 1 && sep[0] in ".,!?;:)]}\"'…"
        val spaceAfter = sep.length == 1 && sep[0] in ".,!?;:…"
        conn.beginBatchEdit()
        var correction: Correction? = null
        var swapped = false
        if (composing.isNotEmpty()) {
            val typed = composing.toString()
            val final = commitWord(autocorrect = isPunct && sep != "'" && sep != "\"")
            if (final != typed) correction = Correction(typed, final, sep)
        } else if (lastAutoSpace && isPunct && sep != "\"" && sep != "'") {
            if (textBefore(1) == " ") { deleteBefore(1); swapped = true }
        }
        commit(sep)
        lastAutoSpace = false
        if (swapped && spaceAfter) { commit(" "); lastAutoSpace = true }
        conn.endBatchEdit()
        lastCorrection = correction
        if (shift == Shift.ONCE && !sep[0].isLetter()) shift = Shift.OFF
        updateShift()
        refreshSuggestions()
        if (swapped && sep in listOf(".", "!", "?")) maybeAutoFixSentence()
    }

    /** Commits the composing word, applying autocorrect if requested. Returns the committed text. */
    private fun commitWord(autocorrect: Boolean): String {
        val typed = composing.toString()
        if (typed.isEmpty()) return ""
        var final = typed
        val cx = context(excludeComposing = true)
        val (prev, start) = cx
        if (autocorrect && autocorrectAllowed && Prefs.autocorrect.value && !composingFromGesture && suppressAutocorrectFor != typed) {
            val res = lastResult?.takeIf { it.typed == typed } ?: Dictionary.suggest(typed, cx)
            res.autoCorrect?.let { final = it }
        }
        if (suppressAutocorrectFor == typed) {
            if (Prefs.learnWords.value) UserDictionary.learnWord(typed, 2)
            suppressAutocorrectFor = null
        }
        commit(final)
        composingFromGesture = false
        learn(prev, start, final, typed)
        return final
    }

    private fun learn(prev: String?, sentenceStart: Boolean, final: String, typed: String) {
        if (!Prefs.learnWords.value || field != FieldKind.TEXT) return
        if (final == typed && !Dictionary.isValid(final) && Dictionary.looksLikeWord(final)) UserDictionary.learnWord(final, 1)
        Dictionary.languagesOf(final).let { if (it.size == 1) com.vis.expressivekeyboard.engine.LangStats.record(it[0]) }
        UserDictionary.learnBigram(if (sentenceStart) "<s>" else prev ?: return, final)
    }

    fun onSpace() {
        val conn = ic ?: return
        val now = System.currentTimeMillis()
        conn.beginBatchEdit()
        var correction: Correction? = null
        if (composing.isNotEmpty()) {
            val typed = composing.toString()
            val final = commitWord(autocorrect = true)
            commit(" ")
            if (final != typed) correction = Correction(typed, final, " ")
        } else {
            val before = textBefore(3)
            val canPeriod = Prefs.doubleSpacePeriod.value && suggestionsAllowed && now - lastSpaceTime < 1500 &&
                before.length >= 2 && before.last() == ' ' &&
                (before[before.length - 2].isLetterOrDigit() || before[before.length - 2] in ")\"'”’")
            if (canPeriod) {
                deleteBefore(1)
                commit(". ")
            } else {
                commit(" ")
            }
        }
        conn.endBatchEdit()
        lastCorrection = correction
        lastAutoSpace = false
        lastSpaceTime = now
        updateShift()
        refreshSuggestions()
        maybeAutoFixSentence()
    }

    fun onEnter() {
        val conn = ic ?: return
        if (composing.isNotEmpty()) {
            conn.beginBatchEdit(); commitWord(autocorrect = true); conn.endBatchEdit()
        }
        lastCorrection = null
        lastAutoSpace = false
        if (!enterIsNewline) {
            conn.performEditorAction(enterAction)
        } else {
            ime.sendKeyChar('\n')
            cursor += 1; mark()
        }
        updateShift()
        refreshSuggestions()
    }

    /** Backspace. [repeat] is true for auto-repeat while held. */
    fun onDelete(repeat: Boolean = false) {
        val conn = ic ?: return
        if (repeat) deleteRepeats++ else deleteRepeats = 0
        val lc = lastCorrection
        if (!repeat && lc != null) {
            lastCorrection = null
            val expect = lc.corrected + lc.sep
            if (textBefore(expect.length) == expect) {
                conn.beginBatchEdit()
                deleteBefore(expect.length)
                composing.append(lc.original); composingText = lc.original
                setComposing(composing)
                conn.endBatchEdit()
                suppressAutocorrectFor = lc.original
                refreshSuggestions()
                return
            }
        }
        lastCorrection = null
        lastAutoSpace = false
        if (composing.isNotEmpty() && selStart == selEnd) {
            if (composingFromGesture) {
                composing.clear(); composingText = ""
                composingFromGesture = false
                commit("")
            } else {
                val cp = Character.codePointBefore(composing, composing.length)
                composing.setLength(composing.length - Character.charCount(cp))
                composingText = composing.toString()
                if (composing.isEmpty()) commit("") else setComposing(composing)
            }
            updateShift()
            refreshSuggestions()
            return
        }
        if (repeat && deleteRepeats > 18 && selStart == selEnd) {
            val before = textBefore(64)
            val m = Regex("([\\p{L}\\p{N}']+\\s*|[^\\p{L}\\p{N}]\\s*)$").find(before)
            val n = m?.value?.length ?: 1
            if (n > 0 && before.isNotEmpty()) { deleteBefore(n); resync = true; updateShift(); refreshSuggestions(); return }
        }
        resync = true
        ime.sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    /** Swipe-left-on-backspace: select [words] words before the cursor (0 restores). */
    private var deleteSelAnchor = -1
    private var deleteSelText = ""
    private var deleteSelWords = 0

    fun deleteSwipeSelect(words: Int) {
        val conn = ic ?: return
        if (deleteSelAnchor < 0) {
            finishComposingQuietly()
            conn.finishComposingText() // make sure no editor-side composing region survives
            // read the real cursor position and the text before it ONCE, before anything is selected:
            // once a selection exists, getTextBeforeCursor() returns text before the selection start.
            val et = conn.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            deleteSelAnchor = if (et != null && et.selectionEnd >= 0) et.startOffset + et.selectionEnd else cursor
            deleteSelText = conn.getTextBeforeCursor(4000, 0)?.toString() ?: ""
            deleteSelWords = 0
        }
        val w = words.coerceAtLeast(0)
        if (w == deleteSelWords) return
        deleteSelWords = w
        if (w == 0) { conn.setSelection(deleteSelAnchor, deleteSelAnchor); return }
        val back = charsForWords(deleteSelText, w)
        conn.setSelection((deleteSelAnchor - back).coerceAtLeast(0), deleteSelAnchor)
    }

    /** Number of characters covering the last [words] words of [text] (punctuation runs count as words). */
    private fun charsForWords(text: String, words: Int): Int {
        fun wordChar(c: Char) = c.isLetterOrDigit() || c == APOS
        var idx = text.length
        var count = 0
        while (idx > 0 && count < words) {
            while (idx > 0 && text[idx - 1].isWhitespace()) idx--
            if (idx > 0 && !wordChar(text[idx - 1])) {
                while (idx > 0 && !text[idx - 1].isWhitespace() && !wordChar(text[idx - 1])) idx--
            } else {
                while (idx > 0 && wordChar(text[idx - 1])) idx--
            }
            count++
        }
        return text.length - idx
    }

    fun deleteSwipeEnd(commitDelete: Boolean) {
        val conn = ic ?: return
        if (deleteSelAnchor < 0) return
        conn.finishComposingText()
        if (commitDelete && deleteSelWords > 0) {
            // delete exactly the selected range (commitText would target a composing region if one existed)
            val n = charsForWords(deleteSelText, deleteSelWords)
            conn.beginBatchEdit()
            conn.setSelection(deleteSelAnchor, deleteSelAnchor)
            conn.deleteSurroundingText(n, 0)
            conn.endBatchEdit()
        } else conn.setSelection(deleteSelAnchor, deleteSelAnchor)
        deleteSelAnchor = -1
        deleteSelWords = 0
        deleteSelText = ""
        resync = true
    }

    fun moveCursor(steps: Int) {
        if (steps == 0) return
        finishComposingQuietly()
        resync = true
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(steps)) { ime.sendDownUpKeyEvents(code) }
    }

    // ------------------------------------------------------------------ suggestions

    fun onGesture(words: List<String>) {
        val conn = ic ?: return
        if (words.isEmpty()) return
        lastCorrection = null
        conn.beginBatchEdit()
        if (composing.isNotEmpty()) {
            commitWord(autocorrect = false)
            commit(" ")
        } else {
            val before = textBefore(1)
            if (before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in "([{\"'/@#“‘") commit(" ")
        }
        val (_, sentenceStart) = context(excludeComposing = true)
        fun cased(w: String) = when {
            shift == Shift.LOCK -> w.uppercase()
            shift == Shift.ONCE || (sentenceStart && Prefs.autoCap.value && capsActive()) -> w.replaceFirstChar { it.uppercaseChar() }
            else -> w
        }
        val list = words.map { cased(it) }
        composing.append(list[0]); composingText = list[0]
        setComposing(composing)
        conn.endBatchEdit()
        composingFromGesture = true
        gestureAlternatives = list
        lastAutoSpace = false
        if (shift == Shift.ONCE) shift = Shift.OFF
        refreshSuggestions()
    }

    fun pickSuggestion(item: StripItem) {
        val conn = ic ?: return
        val typed = composing.toString()
        val (prev, start) = context(excludeComposing = true)
        conn.beginBatchEdit()
        when (item.type) {
            StripType.EMOJI -> {
                // Replaces the composing word ("cry" -> "😭"), like Gboard.
                commit(item.text)
                EmojiData.use(item.text)
            }
            else -> {
                if (composing.isNotEmpty()) {
                    commit(item.text)
                } else {
                    val before = textBefore(1)
                    if (before.isNotEmpty() && !before.last().isWhitespace() && before.last() !in "([{\"'“‘") commit(" ")
                    commit(item.text)
                }
            }
        }
        val addSpace = Prefs.autoSpace.value && field == FieldKind.TEXT && !item.text.endsWith(APOS)
        if (addSpace) { commit(" "); lastAutoSpace = true }
        conn.endBatchEdit()
        composingFromGesture = false
        lastCorrection = null
        if (Prefs.learnWords.value && field == FieldKind.TEXT && item.type != StripType.EMOJI) {
            if (item.type == StripType.LITERAL || !Dictionary.isValid(item.text)) UserDictionary.learnWord(item.text, 2)
            UserDictionary.learnBigram(if (start) "<s>" else prev ?: "", item.text)
        }
        updateShift()
        refreshSuggestions()
    }

    fun blockSuggestion(item: StripItem) {
        UserDictionary.block(item.text)
        refreshSuggestions()
    }

    fun gestureContext(): Ctx {
        if (composing.isNotEmpty()) {
            val c = context(excludeComposing = true)
            return Ctx(composing.toString(), false, c.recent + composing.toString())
        }
        return context(excludeComposing = true)
    }

    /** Previous word, sentence start, and recent words (for language detection). */
    private fun context(excludeComposing: Boolean = true): Ctx {
        val before = textBefore(160)
        val b = if (excludeComposing && composing.isNotEmpty() && before.endsWith(composing)) before.dropLast(composing.length) else before
        val recent = WORD_RE.findAll(b).map { it.value }.toList().takeLast(8)
        val t = b.trimEnd()
        if (t.isEmpty()) return Ctx(null, true, recent)
        if (b.substring(t.length).contains("\n")) return Ctx(null, true, recent)
        val last = t.last()
        if (last in ".!?…") return Ctx(null, true, recent)
        if (!last.isLetter() && last != APOS) return Ctx(null, false, recent)
        return Ctx(WORD_END_RE.find(t)?.value, false, recent)
    }

    private fun capsActive(): Boolean {
        val ei = ime.currentInputEditorInfo ?: return false
        val capFlags = ei.inputType and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        if (capFlags == 0) return false
        return (ic?.getCursorCapsMode(ei.inputType) ?: 0) != 0
    }

    fun updateShift() {
        if (shift == Shift.LOCK) return
        if (composing.isNotEmpty()) { if (shift == Shift.ONCE && composing.length > 0) shift = Shift.OFF; return }
        val auto = Prefs.autoCap.value && mode == Mode.ALPHA && field == FieldKind.TEXT && capsActive()
        shift = if (auto) Shift.ONCE else Shift.OFF
    }

    fun refreshSuggestions() {
        suggestJob?.cancel()
        if (!Prefs.suggestions.value || !suggestionsAllowed) {
            strip = emptyList(); return
        }
        val typed = composing.toString()
        if (composingFromGesture && typed.isNotEmpty()) {
            val alts = gestureAlternatives
            val center = alts.firstOrNull() ?: typed
            strip = listOfNotNull(
                alts.getOrNull(1)?.let { StripItem(it, StripType.NORMAL) },
                StripItem(center, StripType.NORMAL),
                alts.getOrNull(2)?.let { StripItem(it, StripType.NORMAL) },
            )
            return
        }
        val cx = context(excludeComposing = true)
        val start = cx.sentenceStart
        suggestJob = scope.launch {
            if (typed.isEmpty()) {
                lastResult = null
                val preds = withContext(Dispatchers.Default) { Dictionary.predict(cx, 3) }
                strip = arrangePredictions(preds.map { StripItem(it, StripType.PREDICTION) })
                if (Prefs.aiEnabled.value && Prefs.aiPredictions.value && field == FieldKind.TEXT && AiEngine.hasModel()) {
                    delay(350)
                    val ctxText = textBefore(300)
                    if (ctxText.isBlank()) return@launch
                    val ai = AiEngine.predictNext(ctxText) ?: return@launch
                    if (composing.isNotEmpty()) return@launch
                    val word = if (start) ai.replaceFirstChar { it.uppercaseChar() } else ai
                    val others = preds.filter { !it.equals(word, ignoreCase = true) }.map { StripItem(it, StripType.PREDICTION) }
                    strip = arrangePredictions(listOf(StripItem(word, StripType.AI)) + others)
                }
            } else {
                val res = withContext(Dispatchers.Default) { Dictionary.suggest(typed, cx) }
                if (composing.toString() != typed) return@launch
                lastResult = res
                strip = arrange(res)
            }
        }
    }

    private fun arrangePredictions(items: List<StripItem>): List<StripItem> {
        val list = items.distinctBy { it.text.lowercase() }.take(3)
        return when (list.size) {
            0 -> emptyList()
            1 -> list
            2 -> listOf(list[1], list[0])
            else -> listOf(list[1], list[0], list[2])
        }
    }

    private fun arrange(res: SuggestResult): List<StripItem> {
        val typed = res.typed
        val auto = if (suppressAutocorrectFor == typed || !autocorrectAllowed) null else res.autoCorrect
        val others = res.candidates.map { it.word }.filter { it != typed && it != auto }.distinct()
        val emoji = if (typed.length >= 2) EmojiData.search(typed, 1).firstOrNull() else null
        val center: StripItem
        val left: StripItem?
        var right: StripItem?
        if (emoji != null && auto == null && (res.typedValid || EmojiData.isAlias(typed))) {
            // Like Gboard: typed word on the left, emoji in the middle, next best on the right.
            // Space still commits the literal word since nothing is auto-corrected here.
            return listOfNotNull(
                StripItem(typed, StripType.NORMAL),
                StripItem(emoji, StripType.EMOJI),
                others.getOrNull(0)?.let { StripItem(it, StripType.NORMAL) },
            )
        } else if (auto != null) {
            center = StripItem(auto, StripType.AUTO)
            left = StripItem(typed, StripType.LITERAL)
            right = others.getOrNull(0)?.let { StripItem(it, StripType.NORMAL) }
        } else if (res.typedValid) {
            center = StripItem(typed, StripType.NORMAL)
            left = others.getOrNull(0)?.let { StripItem(it, StripType.NORMAL) }
            right = others.getOrNull(1)?.let { StripItem(it, StripType.NORMAL) }
        } else {
            val best = others.getOrNull(0)
            center = if (best != null) StripItem(best, StripType.NORMAL) else StripItem(typed, StripType.LITERAL)
            left = if (best != null) StripItem(typed, StripType.LITERAL) else null
            right = others.getOrNull(1)?.let { StripItem(it, StripType.NORMAL) }
        }
        if (emoji != null && (res.typedValid || EmojiData.isAlias(typed))) right = StripItem(emoji, StripType.EMOJI)
        return listOfNotNull(left, center, right)
    }

    // ------------------------------------------------------------------ clipboard & emoji

    fun paste(text: String) {
        val conn = ic ?: return
        conn.beginBatchEdit()
        if (composing.isNotEmpty()) commitWord(autocorrect = false)
        commit(text)
        conn.endBatchEdit()
        ClipboardStore.freshClip.value = null
        resync = true
        updateShift(); refreshSuggestions()
    }

    /** Inserts a clipboard item: text is typed, images go through the rich-content API like Gboard. */
    /** Inserts a gallery/screenshot image: copied into app storage first, then inserted like a clip. */
    /** A screenshot/photo taken moments ago, offered as a "Paste screenshot" chip like Gboard. */
    var freshShot by mutableStateOf<com.vis.expressivekeyboard.data.RecentImages.Item?>(null)
    private var dismissedShot: String? = null

    private fun checkRecentShot() {
        if (!com.vis.expressivekeyboard.data.RecentImages.hasPermission(ime)) return
        Thread {
            val latest = com.vis.expressivekeyboard.data.RecentImages.latest(ime)
            android.os.Handler(ime.mainLooper).post {
                freshShot = latest?.takeIf { it.uri.toString() != dismissedShot }
            }
        }.start()
    }

    fun dismissShot() {
        dismissedShot = freshShot?.uri?.toString()
        freshShot = null
    }

    fun pasteMediaImage(uri: android.net.Uri, mime: String) {
        if (freshShot?.uri == uri) dismissShot()
        Thread {
            val imported = ClipboardStore.importImage(uri, mime)
            android.os.Handler(ime.mainLooper).post {
                if (imported == null) { android.widget.Toast.makeText(ime, "Could not read that image", android.widget.Toast.LENGTH_SHORT).show(); return@post }
                ClipboardStore.addImageItem(imported)
                pasteClip(imported)
            }
        }.start()
    }

    fun requestPhotosPermission() {
        finishComposingQuietly()
        ime.startActivity(
            Intent(ime, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("request_photos", true).putExtra("tab", 2),
        )
    }

    fun pasteClip(item: com.vis.expressivekeyboard.data.ClipItem) {
        if (!item.isImage) { paste(item.text); return }
        val conn = ic ?: return
        val ei = ime.currentInputEditorInfo ?: return
        val file = java.io.File(item.imagePath!!)
        if (!file.exists()) { ClipboardStore.remove(item); return }
        val mime = item.mime ?: "image/png"
        val accepted = androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(ei)
        val ok = accepted.any { android.content.ClipDescription.compareMimeTypes(mime, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(ime, ime.packageName + ".clips", file)
        if (!ok) {
            // the field doesn't take rich content: put the image on the system clipboard so a long-press "Paste" works
            copyImageToSystemClipboard(uri, mime)
            android.widget.Toast.makeText(ime, "Image copied. Long-press the text field and choose Paste", android.widget.Toast.LENGTH_LONG).show()
            ClipboardStore.freshClip.value = null
            return
        }
        finishComposingQuietly()
        val info = androidx.core.view.inputmethod.InputContentInfoCompat(
            uri, android.content.ClipDescription("Clipboard image", arrayOf(mime)), null,
        )
        val flags = if (Build.VERSION.SDK_INT >= 25) androidx.core.view.inputmethod.InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION else 0
        val sent = androidx.core.view.inputmethod.InputConnectionCompat.commitContent(conn, ei, info, flags, null)
        if (!sent) android.widget.Toast.makeText(ime, "Couldn't insert the image", android.widget.Toast.LENGTH_SHORT).show()
        ClipboardStore.freshClip.value = null
    }

    private fun copyImageToSystemClipboard(uri: android.net.Uri, mime: String) {
        val cm = ime.getSystemService(android.content.ClipboardManager::class.java) ?: return
        ime.grantUriPermission("android", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = android.content.ClipData(android.content.ClipDescription("image", arrayOf(mime)), android.content.ClipData.Item(uri))
        ClipboardStore.suppressNext = true
        runCatching { cm.setPrimaryClip(clip) }.onFailure { ClipboardStore.suppressNext = false }
    }

    fun commitEmoji(e: String) {
        val conn = ic ?: return
        conn.beginBatchEdit()
        if (composing.isNotEmpty()) commitWord(autocorrect = false)
        commit(e)
        conn.endBatchEdit()
        EmojiData.use(e)
        lastAutoSpace = false
        lastCorrection = null
        resync = true
    }

    // ------------------------------------------------------------------ AI

    private fun lastSentenceRange(before: String): Pair<Int, Int>? {
        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end--
        if (end == 0) return null
        var i = end - 1
        while (i >= 0 && before[i] in ".!?…") i--
        var j = i
        while (j >= 0 && before[j] !in ".!?\n…") j--
        var start = j + 1
        while (start < end && before[start].isWhitespace()) start++
        if (end - start < 3) return null
        return start to end
    }

    private fun maybeAutoFixSentence() {
        if (!Prefs.aiEnabled.value || !Prefs.aiSentenceFix.value || field != FieldKind.TEXT || !autocorrectAllowed) return
        if (!AiEngine.hasModel()) return
        val before = textBefore(1200)
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty() || trimmed.last() !in ".!?…") return
        val (s, e) = lastSentenceRange(before) ?: return
        val sentence = before.substring(s, e)
        if (sentence.count { it.isLetter() } < 6) return
        val absStart = cursor - before.length + s
        aiJob?.cancel()
        aiChip = AiChip(AiChip.Kind.WORKING, sentence, "", absStart)
        aiJob = scope.launch {
            val result = AiEngine.process(sentence, AiTask.FIX)
            if (result == null) {
                aiChip = null; return@launch
            }
            if (result.trim() == sentence.trim()) {
                showChipBriefly(AiChip(AiChip.Kind.OK, sentence, result, absStart), 1400)
                return@launch
            }
            if (Prefs.aiAutoApply.value && composing.isEmpty() && replaceRange(absStart, sentence, result)) {
                showChipBriefly(AiChip(AiChip.Kind.UNDO, sentence, result, absStart), 6000)
            } else {
                aiChip = AiChip(AiChip.Kind.SUGGEST, sentence, result, absStart)
            }
        }
    }

    private fun showChipBriefly(chip: AiChip, ms: Long) {
        aiChip = chip
        chipJob?.cancel()
        chipJob = scope.launch { delay(ms); if (aiChip == chip) aiChip = null }
    }

    fun onChipTap() {
        val chip = aiChip ?: return
        when (chip.kind) {
            AiChip.Kind.SUGGEST -> {
                finishComposingQuietly()
                if (replaceRange(chip.absStart, chip.original, chip.result)) {
                    showChipBriefly(AiChip(AiChip.Kind.UNDO, chip.original, chip.result, chip.absStart), 5000)
                } else aiChip = null
            }
            AiChip.Kind.UNDO -> {
                finishComposingQuietly()
                replaceRange(chip.absStart, chip.result, chip.original)
                aiChip = null
            }
            else -> aiChip = null
        }
    }

    fun dismissChip() { aiJob?.cancel(); aiChip = null }

    /** Replaces text at absolute [absStart] if it still equals [original]. */
    fun replaceRange(absStart: Int, original: String, replacement: String): Boolean {
        val conn = ic ?: return false
        val end = absStart + original.length
        val current: String = if (end <= cursor) {
            val before = textBefore(cursor - absStart)
            if (before.length != cursor - absStart) return false
            before.substring(0, original.length)
        } else {
            val before = textBefore(cursor - absStart)
            val after = conn.getTextAfterCursor(end - cursor, 0)?.toString() ?: ""
            before + after
        }
        if (current != original) return false
        val oldCursor = cursor
        conn.beginBatchEdit()
        conn.finishComposingText()
        conn.setSelection(absStart, end)
        conn.commitText(replacement, 1)
        val newCursor = if (oldCursor >= end) oldCursor + (replacement.length - original.length) else absStart + replacement.length
        conn.setSelection(newCursor, newCursor)
        conn.endBatchEdit()
        cursor = newCursor
        composing.clear(); composingText = ""
        resync = true
        updateShift()
        refreshSuggestions()
        return true
    }

    /** Collects the text the AI panel should work on: selection, current sentence or whole field. */
    private fun aiTarget(): Pair<String, Int>? {
        val conn = ic ?: return null
        finishComposingQuietly()
        val selected = conn.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty() && selStart >= 0) return selected to minOf(selStart, selEnd)
        val before = conn.getTextBeforeCursor(4000, 0)?.toString() ?: ""
        if (Prefs.aiScope.value == 1) {
            val after = conn.getTextAfterCursor(4000, 0)?.toString() ?: ""
            val all = before + after
            if (all.isBlank()) return null
            val lead = all.length - all.trimStart().length
            return all.trim() to (cursor - before.length + lead)
        }
        val (s, e) = lastSentenceRange(before) ?: return null
        return before.substring(s, e) to (cursor - before.length + s)
    }

    fun runAiTask(task: AiTask) {
        if (!AiEngine.hasModel()) {
            aiPanel = AiPanelState(error = if (AiEngine.supported) "No AI model installed. Download one in Settings → AI."
                else "AI writing needs a 64-bit device. Everything else works as usual.")
            return
        }
        val target = aiTarget()
        if (target == null) {
            aiPanel = AiPanelState(error = "Type something first: the AI works on the current sentence, the selection or the whole text.")
            return
        }
        val (text, abs) = target
        aiPanel = AiPanelState(busy = true, task = task, original = text, absStart = abs)
        aiJob?.cancel()
        aiJob = scope.launch {
            val r = AiEngine.process(text, task)
            aiPanel = if (r == null) aiPanel.copy(busy = false, error = AiEngine.message.value.ifEmpty { "The model returned nothing useful. Try again." })
            else aiPanel.copy(busy = false, result = r)
        }
    }

    fun applyAiPanel() {
        val st = aiPanel
        val r = st.result ?: return
        if (replaceRange(st.absStart, st.original, r)) {
            aiPanel = AiPanelState()
            panel = Panel.KEYS
        } else {
            aiPanel = st.copy(error = "The text changed - couldn't replace it. Use Insert instead.")
        }
    }

    fun insertAiPanel() {
        val r = aiPanel.result ?: return
        paste(r)
        aiPanel = AiPanelState()
        panel = Panel.KEYS
    }
}
