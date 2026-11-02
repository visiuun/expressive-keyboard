package com.vis.expressivekeyboard.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vis.expressivekeyboard.data.Prefs
import com.vis.expressivekeyboard.engine.Dictionary
import com.vis.expressivekeyboard.engine.LangStats
import com.vis.expressivekeyboard.engine.Languages
import com.vis.expressivekeyboard.ui.Sym
import com.vis.expressivekeyboard.voice.VoiceCatalog
import com.vis.expressivekeyboard.voice.VoiceEngine
import com.vis.expressivekeyboard.voice.VoiceListener
import com.vis.expressivekeyboard.voice.VoiceState

// --------------------------------------------------------------- floating pill navigation

private data class NavItem(val icon: @Composable () -> ImageVector, val label: String)

/**
 * Floating pill navigation bar + "try typing" button. Item width adapts to the screen so the
 * button never gets pushed off the edge; on sub-pages only the button is shown.
 */
@Composable
fun PillNavBar(selected: Int, onSelect: (Int) -> Unit, onFab: () -> Unit, showNav: Boolean = true, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val items = listOf(
        NavItem({ Sym.Home }, "Home"),
        NavItem({ Sym.Palette }, "Style"),
        NavItem({ Sym.Keyboard }, "Typing"),
        NavItem({ Sym.AutoAwesome }, "AI & Voice"),
    )
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        val fab = 64.dp
        val gap = 12.dp
        // 4 items + 8dp padding each side inside the pill must fit next to the button
        val itemW = ((maxWidth - fab - gap - 16.dp) / 4).coerceIn(52.dp, 76.dp)
        val itemH = 56.dp
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showNav,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkHorizontally(),
            ) {
                Surface(shape = CircleShape, color = cs.primaryContainer, shadowElevation = 6.dp) {
                    Box(Modifier.padding(8.dp)) {
                        val x by animateDpAsState(itemW * selected, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "pill")
                        Box(Modifier.offset(x = x).size(itemW, itemH).clip(CircleShape).background(cs.onPrimaryContainer))
                        Row {
                            items.forEachIndexed { i, it ->
                                val sel = i == selected
                                val tint by animateColorAsState(if (sel) cs.primaryContainer else cs.onPrimaryContainer, label = "t$i")
                                Box(
                                    Modifier.size(itemW, itemH).clip(CircleShape).clickable { onSelect(i) },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(it.icon(), it.label, tint = tint, modifier = Modifier.size(26.dp)) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            val corner by animateDpAsState(if (pressed) 32.dp else 22.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "fab")
            Box(
                Modifier.size(fab).shadow(6.dp, RoundedCornerShape(corner)).clip(RoundedCornerShape(corner))
                    .background(cs.primary)
                    .clickable(interactionSource = src, indication = androidx.compose.material3.ripple()) { onFab() },
                contentAlignment = Alignment.Center,
            ) { Icon(Sym.Edit, "Try the keyboard", tint = cs.onPrimary, modifier = Modifier.size(26.dp)) }
        }
    }
}

/** Bottom sheet with a focused text field, to try the keyboard from anywhere in settings. */
@Composable
fun TrySheet(onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).imePadding()) {
            Text("Try your keyboard", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Type, glide, use voice or the ✨ AI tools.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                shape = RoundedCornerShape(20.dp), minLines = 3,
                placeholder = { Text("Start typing…") },
            )
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(300); runCatching { focus.requestFocus() } }
}

@Composable
fun QuickTiles(onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    data class Tile(val title: String, val sub: String, val tab: String, val shape: androidx.graphics.shapes.RoundedPolygon, val color: Color, val on: Color)
    val tiles = listOf(
        Tile("Colors", "Themes, dark mode, OLED", "colors", MaterialShapes.Cookie9Sided, cs.primaryContainer, cs.onPrimaryContainer),
        Tile("Languages", Dictionary.activeCodes().joinToString(" · ") { it.uppercase() }, "languages", MaterialShapes.Clover4Leaf, cs.secondaryContainer, cs.onSecondaryContainer),
        Tile("Voice typing", VoiceEngine.activeModel()?.name ?: "Not set up", "voice", MaterialShapes.Sunny, cs.tertiaryContainer, cs.onTertiaryContainer),
        Tile("AI writing", com.vis.expressivekeyboard.ai.AiEngine.modelDisplayName(), "ai", MaterialShapes.SoftBurst, cs.surfaceContainerHighest, cs.onSurface),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    Surface(
                        shape = RoundedCornerShape(28.dp), color = t.color,
                        modifier = Modifier.weight(1f).height(118.dp).clickable { onOpen(t.tab) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Box(Modifier.size(34.dp).clip(t.shape.toShape()).background(t.on.copy(alpha = 0.85f)))
                            Spacer(Modifier.weight(1f))
                            Text(t.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = t.on)
                            Text(t.sub, fontSize = 12.sp, color = t.on.copy(alpha = 0.75f), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------- languages

@Composable
fun LanguagesGroup() {
    val cs = MaterialTheme.colorScheme
    val active = Dictionary.activeCodes()
    // enabled languages first, sorted by how much you use them
    val ordered = Languages.all.sortedWith(
        compareByDescending<com.vis.expressivekeyboard.engine.LanguageInfo> { it.code in active }
            .thenByDescending { LangStats.counts[it.code] ?: 0 },
    )
    Group {
        item {
            Text(
                "Pick up to ${Languages.MAX_ACTIVE}. Suggestions, autocorrect, glide typing and predictions work in all of them at once, " +
                    "favouring the language you use most and the one you're typing right now.",
                fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(18.dp),
            )
        }
        ordered.forEach { lang ->
            item {
                val on = lang.code in active
                val canEnable = on || active.size < Languages.MAX_ACTIVE
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = canEnable) { toggleLanguage(lang.code) }.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(lang.flag, fontSize = 26.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(lang.name, fontSize = 16.sp, color = cs.onSurface.copy(alpha = if (canEnable) 1f else 0.4f))
                        if (on) {
                            val share = LangStats.share(lang.code)
                            Text("${(share * 100).toInt()}% of your typing", fontSize = 12.sp, color = cs.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth(0.7f).height(4.dp).clip(CircleShape).background(cs.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).height(4.dp).clip(CircleShape).background(cs.primary))
                            }
                        }
                    }
                    Checkbox(checked = on, onCheckedChange = { toggleLanguage(lang.code) }, enabled = canEnable)
                }
            }
        }
    }
}

private fun toggleLanguage(code: String) {
    val cur = Dictionary.activeCodes().toMutableList()
    if (code in cur) {
        if (cur.size == 1) return
        cur.remove(code)
    } else {
        if (cur.size >= Languages.MAX_ACTIVE) return
        cur += code
    }
    Prefs.languages.value = cur.joinToString(",")
    Dictionary.reload()
}

// --------------------------------------------------------------- voice

@Composable
fun VoiceGroup(onRequestMic: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val st = VoiceEngine.state.value
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(st) { tick++ }
    val hasMic = remember(st, tick) { VoiceEngine.hasPermission() }
    Group {
        item {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(MaterialShapes.Cookie12Sided.toShape()).background(cs.tertiary), contentAlignment = Alignment.Center) {
                        Icon(Sym.Mic, null, tint = cs.onTertiary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(VoiceEngine.activeModel()?.name ?: "No voice model", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (st) {
                                VoiceState.NO_MODEL -> "Download a model below"
                                VoiceState.DOWNLOADING -> "Downloading… ${(VoiceEngine.progress.floatValue * 100).toInt()}%"
                                VoiceState.LOADING -> "Loading into memory…"
                                VoiceState.READY -> "Loaded · instant start"
                                VoiceState.LISTENING -> "Listening"
                                VoiceState.IDLE -> "Installed · fully offline"
                                VoiceState.ERROR -> VoiceEngine.message.value
                            },
                            fontSize = 13.sp, color = cs.onSurfaceVariant,
                        )
                    }
                    if (st == VoiceState.LOADING) LoadingIndicator(Modifier.size(36.dp))
                }
                if (st == VoiceState.DOWNLOADING) {
                    Spacer(Modifier.height(14.dp))
                    LinearWavyProgressIndicator(progress = { VoiceEngine.progress.floatValue }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { VoiceEngine.cancelDownload() }) { Text("Cancel download") }
                }
                if (!hasMic) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestMic, modifier = Modifier.fillMaxWidth()) { Text("Allow microphone access") }
                }
            }
        }
        VoiceCatalog.models.forEach { m ->
            item {
                val installed = remember(st, tick, Prefs.voiceModel.value) { VoiceEngine.isInstalled(m) }
                val active = VoiceEngine.activeModel()?.id == m.id
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, fontSize = 16.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                            if (active) { Spacer(Modifier.width(6.dp)); Icon(Sym.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(16.dp)) }
                        }
                        Text("${m.description} · ${fmtSize(m.sizeBytes)}", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    }
                    when {
                        installed && !active -> FilledTonalButton(onClick = { VoiceEngine.select(m); tick++ }) { Text("Use") }
                        installed -> IconButton(onClick = { VoiceEngine.deleteModel(m); tick++ }) { Icon(Sym.Delete, "Delete") }
                        else -> FilledTonalButton(onClick = { VoiceEngine.startDownload(m) }, enabled = st != VoiceState.DOWNLOADING) {
                            Icon(Sym.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Get")
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Group {
        item {
            val codes = listOf("auto") + Dictionary.activeCodes()
            val labels = codes.map { if (it == "auto") "Auto" else Languages.info(it).name }
            val parakeet = VoiceEngine.activeModel()?.kind == com.vis.expressivekeyboard.voice.VoiceModel.Kind.PARAKEET
            Column(Modifier.fillMaxWidth()) {
                ChoiceRow("Voice language", labels, codes.indexOf(Prefs.voiceLanguage.value).coerceAtLeast(0)) {
                    Prefs.voiceLanguage.value = codes[it]; VoiceEngine.settingsChanged()
                }
                Text(
                    if (parakeet) "Parakeet always detects the language by itself. To lock a language, use a Whisper model."
                    else "Locking a language stops the model guessing wrong on short phrases.",
                    fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                )
            }
        }
        item { SwitchRow("Automatic punctuation", "The model adds commas, periods and question marks as you speak", Prefs.voiceAutoPunct) }
        item {
            SwitchRow(
                "Spoken punctuation",
                "Say “comma”, “period”, “question mark”, “new line”, or “virgola”, “punto”, “a capo” in Italian (also ES, FR, DE, PT)",
                Prefs.voiceSpokenPunct,
            )
        }
        item { SwitchRow("Keep voice model ready", "Loads the model when the keyboard opens so dictation starts instantly (uses more memory)", Prefs.voicePreload) }
    }
    Spacer(Modifier.height(10.dp))
    VoiceTestCard()
}

@Composable
private fun VoiceTestCard() {
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var firstWordMs by remember { mutableStateOf<Long?>(null) }
    var startedAt by remember { mutableStateOf(0L) }
    Surface(shape = RoundedCornerShape(24.dp), color = cs.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Test voice typing", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = cs.onTertiaryContainer)
            Spacer(Modifier.height(8.dp))
            Text(
                (text + (if (partial.isNotEmpty()) " $partial" else "")).ifEmpty { "Tap the button and speak in any of your languages." },
                color = cs.onTertiaryContainer.copy(alpha = if (text.isEmpty() && partial.isEmpty()) 0.6f else 1f), fontSize = 15.sp,
            )
            firstWordMs?.let { Text("First words after $it ms", fontSize = 12.sp, color = cs.onTertiaryContainer.copy(alpha = 0.7f)) }
            Spacer(Modifier.height(10.dp))
            Button(
                enabled = VoiceEngine.activeModel() != null && VoiceEngine.hasPermission(),
                onClick = {
                    if (listening) { VoiceEngine.stop(); return@Button }
                    text = ""; partial = ""; firstWordMs = null; startedAt = System.currentTimeMillis()
                    listening = VoiceEngine.start(object : VoiceListener {
                        override fun onPartial(t: String) { if (firstWordMs == null) firstWordMs = System.currentTimeMillis() - startedAt; partial = t }
                        override fun onFinal(t: String) {
                            if (firstWordMs == null && t.isNotEmpty()) firstWordMs = System.currentTimeMillis() - startedAt
                            partial = ""; text = (text + " " + t).trim()
                        }
                        override fun onStopped() { listening = false; partial = "" }
                    })
                },
            ) {
                Icon(if (listening) Sym.Stop else Sym.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(if (listening) "Stop" else "Start")
            }
        }
    }
}


// --------------------------------------------------------------- settings menu

class PageDef(
    val id: String,
    val title: String,
    val summary: @Composable () -> String,
    val icon: @Composable () -> ImageVector,
    val shape: androidx.graphics.shapes.RoundedPolygon,
    val content: @Composable () -> Unit,
)

@Composable
fun settingsPages(onRequestMic: () -> Unit): List<PageDef> = listOf(
    PageDef("colors", "Colors & theme", { com.vis.expressivekeyboard.ui.Themes.byId(Prefs.theme.value).name + (if (Prefs.oled.value) " · OLED" else "") },
        { Sym.Palette }, MaterialShapes.Cookie9Sided) { ColorsPage() },
    PageDef("keys", "Keys & shapes", { "Enter, space bar, labels" }, { Sym.Interests }, MaterialShapes.Clover4Leaf) { KeysPage() },
    PageDef("layout", "Layout & size", { (com.vis.expressivekeyboard.keyboard.Layouts.LETTER_LAYOUTS[Prefs.layout.value] ?: "") + " · height ${(Prefs.keyHeight.value * 100).toInt()}%" },
        { Sym.ViewAgenda }, MaterialShapes.Pill) { LayoutPage() },
    PageDef("languages", "Languages", { Dictionary.activeCodes().joinToString(" · ") { Languages.info(it).name } },
        { Sym.Translate }, MaterialShapes.Sunny) { LanguagesGroup() },
    PageDef("correction", "Text correction", { if (Prefs.autocorrect.value) "Autocorrect on" else "Autocorrect off" },
        { Sym.Spellcheck }, MaterialShapes.Cookie6Sided) { CorrectionPage() },
    PageDef("gestures", "Gestures", { if (Prefs.swipeTyping.value) "Glide typing on" else "Glide typing off" },
        { Sym.Swipe }, MaterialShapes.Cookie4Sided) { GesturesPage() },
    PageDef("feedback", "Sound & vibration", { listOfNotNull(if (Prefs.haptics.value) "Vibration" else null, if (Prefs.sound.value) "Sound" else null).joinToString(" · ").ifEmpty { "Off" } },
        { Sym.Vibration }, MaterialShapes.SoftBurst) { FeedbackPage() },
    PageDef("clipboard", "Clipboard", { if (Prefs.clipboardHistory.value) "History on" else "History off" },
        { Sym.ContentPaste }, MaterialShapes.Square) { ClipboardPage() },
    PageDef("voice", "Voice typing", { VoiceEngine.activeModel()?.name ?: "Set up a voice model" },
        { Sym.Mic }, MaterialShapes.Cookie12Sided) { VoiceGroup(onRequestMic) },
    PageDef("ai", "AI writing", { com.vis.expressivekeyboard.ai.AiEngine.modelDisplayName() },
        { Sym.AutoAwesome }, MaterialShapes.VerySunny) { AiGroup() },
    PageDef("about", "Updates & about", {
        if (com.vis.expressivekeyboard.update.Updater.state.value == com.vis.expressivekeyboard.update.UpdateState.AVAILABLE) "Update available"
        else "Version ${com.vis.expressivekeyboard.BuildConfig.VERSION_NAME} · licenses"
    }, { Sym.Info }, MaterialShapes.Circle) { AboutGroup() },
)

/** Grouped menu of settings pages with expressive icon shapes. */
@Composable
fun MenuGroup(items: List<PageDef>, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val palette = listOf(
        cs.primaryContainer to cs.onPrimaryContainer,
        cs.secondaryContainer to cs.onSecondaryContainer,
        cs.tertiaryContainer to cs.onTertiaryContainer,
    )
    Group {
        items.forEachIndexed { i, p ->
            item {
                val (bg, fg) = palette[i % palette.size]
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(46.dp).clip(p.shape.toShape()).background(bg), contentAlignment = Alignment.Center) {
                        Icon(p.icon(), null, tint = fg, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text(p.summary(), fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    Icon(Sym.ChevronRight, null, tint = cs.onSurfaceVariant)
                }
            }
        }
    }
}
