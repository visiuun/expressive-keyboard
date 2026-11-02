package com.vis.expressivekeyboard.settings

import com.vis.expressivekeyboard.ui.Sym
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vis.expressivekeyboard.ai.AiEngine
import com.vis.expressivekeyboard.ai.AiState
import com.vis.expressivekeyboard.ai.AiTask
import com.vis.expressivekeyboard.ai.ModelCatalog
import com.vis.expressivekeyboard.data.Pref
import com.vis.expressivekeyboard.data.Prefs
import com.vis.expressivekeyboard.engine.UserDictionary
import com.vis.expressivekeyboard.keyboard.Layouts
import com.vis.expressivekeyboard.ui.KeyboardTheme
import com.vis.expressivekeyboard.ui.Themes
import com.vis.expressivekeyboard.ui.buildScheme
import com.vis.expressivekeyboard.ui.isDarkMode
import com.vis.expressivekeyboard.ui.keyColors
import com.vis.expressivekeyboard.update.UpdateState
import com.vis.expressivekeyboard.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : ComponentActivity() {
    val tab = androidx.compose.runtime.mutableIntStateOf(0)
    val askMic = androidx.compose.runtime.mutableStateOf(false)
    val askPhotos = androidx.compose.runtime.mutableStateOf(false)
    val page = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { KeyboardTheme { SettingsScreen(this) } }
        if (Prefs.autoUpdate.value && Updater.state.value == UpdateState.IDLE) Updater.check()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i ?: return
        if (i.hasExtra("tab")) {
            tab.intValue = i.getIntExtra("tab", 0)
            page.value = if (tab.intValue == 3) "voice" else null
        }
        if (i.getBooleanExtra("request_mic", false)) { askMic.value = true; tab.intValue = 3; page.value = "voice" }
        if (i.getBooleanExtra("request_photos", false)) { askPhotos.value = true; page.value = "clipboard" }
        i.getStringExtra("page")?.let { tab.intValue = 0; page.value = it }
    }

    override fun onResume() {
        super.onResume()
        AiEngine.refreshState()
        com.vis.expressivekeyboard.voice.VoiceEngine.refreshState()
    }
}

private fun imeEnabled(ctx: Context): Boolean =
    ctx.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == ctx.packageName }

private fun imeSelected(ctx: Context): Boolean =
    Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(ctx.packageName + "/") == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(activity: SettingsActivity) {
    val cs = MaterialTheme.colorScheme
    var tab by activity.tab
    var page by activity.page
    var trySheet by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        com.vis.expressivekeyboard.voice.VoiceEngine.refreshState()
    }
    val photosLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(activity.askPhotos.value) {
        if (activity.askPhotos.value) { activity.askPhotos.value = false; photosLauncher.launch(com.vis.expressivekeyboard.data.RecentImages.permission()) }
    }
    LaunchedEffect(activity.askMic.value) {
        if (activity.askMic.value) { activity.askMic.value = false; micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
    }
    val pages = settingsPages(onRequestMic = { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) })
    val current = pages.firstOrNull { it.id == page }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }
    val titles = listOf("Expressive Keyboard", "Style", "Typing", "AI & Voice")
    Box(Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedContent(
            targetState = page to tab,
            transitionSpec = {
                val deeper = targetState.first != null && initialState.first == null
                val back = targetState.first == null && initialState.first != null
                val dir = when {
                    deeper -> 1
                    back -> -1
                    else -> if (targetState.second > initialState.second) 1 else -1
                }
                (androidx.compose.animation.slideInHorizontally { it / 5 * dir } + androidx.compose.animation.fadeIn()) togetherWith
                    (androidx.compose.animation.slideOutHorizontally { -it / 5 * dir } + androidx.compose.animation.fadeOut())
            },
            label = "nav",
        ) { (pg, t) ->
            val def = pages.firstOrNull { it.id == pg }
            val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
            Scaffold(
                modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
                containerColor = cs.surfaceContainer,
                topBar = {
                    LargeFlexibleTopAppBar(
                        title = { Text(def?.title ?: titles[t]) },
                        subtitle = { Text(if (def != null) def.summary() else if (t == 0) "Material 3 Expressive · on-device AI" else "Expressive Keyboard") },
                        navigationIcon = {
                            if (def != null) IconButton(onClick = { page = null }) { Icon(Sym.ArrowBack, "Back") }
                        },
                        scrollBehavior = scroll,
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainer, scrolledContainerColor = cs.surfaceContainerHigh),
                    )
                },
            ) { pad ->
                if (def != null) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 110.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) { def.content() }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 128.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        when (t) {
                            0 -> {
                                if (Updater.state.value in listOf(UpdateState.AVAILABLE, UpdateState.DOWNLOADING, UpdateState.INSTALLING)) {
                                    item { UpdateCard() }
                                    item { Spacer(Modifier.height(12.dp)) }
                                }
                                item { SetupCard() }
                                item { Spacer(Modifier.height(12.dp)) }
                                item { QuickTiles { page = it } }
                                item { Spacer(Modifier.height(12.dp)) }
                                item { MenuGroup(pages.filter { it.id == "about" }) { page = it } }
                            }
                            1 -> item { MenuGroup(pages.filter { it.id in listOf("colors", "keys", "layout") }) { page = it } }
                            2 -> item { MenuGroup(pages.filter { it.id in listOf("languages", "correction", "gestures", "feedback", "clipboard") }) { page = it } }
                            else -> item { MenuGroup(pages.filter { it.id in listOf("voice", "ai") }) { page = it } }
                        }
                    }
                }
            }
        }
        // the "try typing" button stays on every page; the tab pill only on the main tabs
        PillNavBar(
            selected = tab, onSelect = { tab = it }, onFab = { trySheet = true },
            showNav = current == null, modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    if (trySheet) TrySheet { trySheet = false }
}

@Composable
internal fun SectionTitle(t: String) {
    Text(
        t, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        modifier = Modifier.padding(start = 12.dp, top = 22.dp, bottom = 8.dp),
    )
}

// --------------------------------------------------------------- grouped list containers (Android 16 style)

@Composable
internal fun Group(content: @Composable GroupScope.() -> Unit) {
    val items = mutableListOf<@Composable (Int, Int) -> Unit>()
    GroupScope(items).content()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, c -> c(i, items.size) }
    }
}

class GroupScope(val items: MutableList<@Composable (Int, Int) -> Unit>) {
    fun item(c: @Composable () -> Unit) { items += { i, n -> GroupCell(i, n) { c() } } }
}

@Composable
internal fun GroupCell(i: Int, n: Int, content: @Composable () -> Unit) {
    val big = 24.dp; val small = 6.dp
    val shape = RoundedCornerShape(
        topStart = if (i == 0) big else small, topEnd = if (i == 0) big else small,
        bottomStart = if (i == n - 1) big else small, bottomEnd = if (i == n - 1) big else small,
    )
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLowest.let {
        if (isDarkMode()) MaterialTheme.colorScheme.surfaceContainerHigh else it
    }, modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String? = null, pref: Pref<Boolean>, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { pref.value = !pref.value }.padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f))
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = pref.value, onCheckedChange = { pref.value = it }, enabled = enabled, thumbContent = if (pref.value) {
            { Icon(Sym.Check, null, Modifier.size(16.dp)) }
        } else null)
    }
}

@Composable
internal fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, steps: Int = 0, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row {
            Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
internal fun ChoiceRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(title, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            options.forEachIndexed { i, o ->
                ToggleButton(
                    checked = selected == i,
                    onCheckedChange = { onSelect(i) },
                    modifier = Modifier.weight(1f),
                    shapes = when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                ) { Text(o, fontSize = 13.sp, maxLines = 1) }
            }
        }
    }
}

// --------------------------------------------------------------- setup

@Composable
internal fun SetupCard() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(imeEnabled(ctx)) }
    var selected by remember { mutableStateOf(imeSelected(ctx)) }
    LaunchedEffect(Unit) {
        while (true) { enabled = imeEnabled(ctx); selected = imeSelected(ctx); delay(700) }
    }
    val cs = MaterialTheme.colorScheme
    val done = enabled && selected
    val rot by animateFloatAsState(if (done) 360f else 0f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "rot")
    Surface(shape = RoundedCornerShape(32.dp), color = if (done) cs.primaryContainer else cs.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).rotate(rot).clip(MaterialShapes.Cookie9Sided.toShape()).background(if (done) cs.primary else cs.tertiary), contentAlignment = Alignment.Center) {
                    Icon(if (done) Sym.Check else Sym.Keyboard, null, tint = if (done) cs.onPrimary else cs.onTertiary, modifier = Modifier.rotate(-rot))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(if (done) "You're all set" else "Set up your keyboard", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (done) "Expressive Keyboard is your active keyboard." else "Two quick steps to start typing.",
                        fontSize = 14.sp, color = (if (done) cs.onPrimaryContainer else cs.onTertiaryContainer).copy(alpha = 0.8f),
                    )
                }
            }
            if (!done) {
                Spacer(Modifier.height(16.dp))
                StepButton(1, "Enable in system settings", enabled) { ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                Spacer(Modifier.height(8.dp))
                StepButton(2, "Switch to Expressive Keyboard", selected, enabledButton = enabled) {
                    ctx.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                }
            }
        }
    }
}

@Composable
private fun StepButton(n: Int, label: String, done: Boolean, enabledButton: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (done) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Sym.CheckCircle, null, tint = cs.primary)
            Spacer(Modifier.width(10.dp))
            Text(label, color = cs.onTertiaryContainer)
        }
    } else {
        Button(onClick = onClick, enabled = enabledButton, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("$n.  $label", fontSize = 15.sp)
        }
    }
}

@Composable
private fun TryItField() {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text, onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        label = { Text("Try it here") },
        shape = RoundedCornerShape(20.dp),
        minLines = 2,
    )
}

// --------------------------------------------------------------- theme

@Composable
internal fun ThemeGallery() {
    val ctx = LocalContext.current
    val dark = isDarkMode()
    val oled = Prefs.oled.value
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(Themes.all, key = { it.id }) { spec ->
            if (spec.seed == null && android.os.Build.VERSION.SDK_INT < 31) return@items
            val scheme = remember(spec.id, dark, oled, Prefs.customSeed.value, Prefs.customStyle.value) { buildScheme(ctx, spec.id, dark, oled) }
            val kc = remember(scheme, dark, oled) { keyColors(scheme, dark, oled, true) }
            val selected = Prefs.theme.value == spec.id
            val corner by animateDpAsState(if (selected) 34.dp else 18.dp, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "tc")
            val border by animateColorAsState(if (selected) scheme.primary else Color.Transparent, label = "tb")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(width = 112.dp, height = 96.dp).clip(RoundedCornerShape(corner))
                        .border(3.dp, border, RoundedCornerShape(corner))
                        .background(kc.background)
                        .clickable { Prefs.theme.value = spec.id }
                        .padding(9.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(3) { r ->
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                val n = if (r == 2) 4 else 6
                                repeat(n) { c ->
                                    val isFunc = r == 2 && (c == 0)
                                    val isAccent = r == 2 && c == n - 1
                                    Box(
                                        Modifier.size(width = if (r == 2 && (c == 1 || c == 2)) 22.dp else 12.dp, height = 18.dp)
                                            .clip(RoundedCornerShape(if (isAccent) 9.dp else 4.dp))
                                            .background(if (isAccent) kc.accent else if (isFunc) kc.func else kc.key),
                                    )
                                }
                            }
                        }
                    }
                    if (selected) Box(
                        Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(scheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Sym.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp)) }
                }
                Text(spec.name, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

private val SWATCHES = listOf(
    0xFF6750A4, 0xFF3D5AFE, 0xFF0061A4, 0xFF00838F, 0xFF00897B, 0xFF2E7D32, 0xFF7CB342, 0xFFC0CA33,
    0xFFFFB300, 0xFFFF6E40, 0xFFD32F2F, 0xFFC2185B, 0xFFE040FB, 0xFF8D6E63, 0xFF607D8B, 0xFF212121,
).map { it.toInt() }

// --------------------------------------------------------------- sub-pages

@Composable
internal fun ColorsPage() {
    val cs = MaterialTheme.colorScheme
    SectionTitle("Theme")
    ThemeGallery()
    Group {
        item { ChoiceRow("Dark mode", listOf("System", "Light", "Dark"), Prefs.darkMode.value) { Prefs.darkMode.value = it } }
        item { SwitchRow("OLED black", "Pure black background in dark mode", Prefs.oled) }
    }
    SectionTitle("Custom color")
    Group {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SWATCHES.forEach { c ->
                        val sel = Prefs.theme.value == "custom" && Prefs.customSeed.value == c
                        val corner by animateDpAsState(if (sel) 12.dp else 20.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "sw")
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(corner)).background(Color(c))
                                .clickable { Prefs.customSeed.value = c; Prefs.theme.value = "custom" },
                            contentAlignment = Alignment.Center,
                        ) { if (sel) Icon(Sym.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(Prefs.customSeed.value, it) }
                Text("Hue", fontSize = 13.sp, color = cs.onSurfaceVariant)
                Slider(
                    value = hsv[0], valueRange = 0f..360f,
                    onValueChange = { h ->
                        Prefs.customSeed.value = android.graphics.Color.HSVToColor(floatArrayOf(h, hsv[1].coerceAtLeast(0.45f), hsv[2].coerceAtLeast(0.55f)))
                        Prefs.theme.value = "custom"
                    },
                )
                Text("Palette style", fontSize = 13.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.vis.expressivekeyboard.ui.CUSTOM_STYLES.forEach { st ->
                        ToggleButton(checked = Prefs.customStyle.value == st, onCheckedChange = { Prefs.customStyle.value = st; Prefs.theme.value = "custom" }) {
                            Text(st.replace(Regex("([a-z])([A-Z])"), "$1 $2"), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun KeysPage() {
    val cs = MaterialTheme.colorScheme
    SectionTitle("Enter key")
    Group {
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(com.vis.expressivekeyboard.ui.KeyShapes.enter, key = { it.first }) { (id, name) ->
                    val sel = Prefs.enterShape.value == id
                    val bg by animateColorAsState(if (sel) cs.secondaryContainer else Color.Transparent, label = "es")
                    Column(
                        Modifier.clip(RoundedCornerShape(18.dp)).background(bg).clickable { Prefs.enterShape.value = id }.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val shape = com.vis.expressivekeyboard.ui.KeyShapes.enterShape(id)
                        Box(
                            Modifier.size(width = if (shape == null) 64.dp else 48.dp, height = 48.dp).clip(shape ?: RoundedCornerShape(50)).background(cs.primary),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Sym.KeyboardReturn, null, tint = cs.onPrimary, modifier = Modifier.size(22.dp)) }
                        Text(name, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
    SectionTitle("Shapes")
    Group {
        item {
            val opts = listOf("rounded", "square", "pill")
            ChoiceRow("Space bar", listOf("Rounded", "Square", "Pill"), opts.indexOf(Prefs.spaceShape.value).coerceAtLeast(0)) { Prefs.spaceShape.value = opts[it] }
        }
        item {
            val opts = listOf("rounded", "pill", "square")
            ChoiceRow("Shift, delete and ?123", listOf("Rounded", "Pill", "Square"), opts.indexOf(Prefs.funcShape.value).coerceAtLeast(0)) { Prefs.funcShape.value = opts[it] }
        }
        item { SliderRow("Letter key roundness", Prefs.roundness.value, 0.2f..2.0f, "${(Prefs.roundness.value * 100).toInt()}%") { Prefs.roundness.value = it } }
        item { SwitchRow("Key borders", "Show keys as filled shapes", Prefs.keyBorders) }
    }
    SectionTitle("Labels")
    Group {
        item { SliderRow("Label size", Prefs.labelSize.value, 0.8f..1.3f, "${(Prefs.labelSize.value * 100).toInt()}%") { Prefs.labelSize.value = it } }
        item { SwitchRow("Bold labels", pref = Prefs.boldLabels) }
        item { SwitchRow("Number hints on top row", pref = Prefs.showHints) }
        item { SwitchRow("Popup on key press", pref = Prefs.keyPopup) }
    }
}

@Composable
internal fun LayoutPage() {
    SectionTitle("Layout")
    Group {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Layouts.LETTER_LAYOUTS.forEach { (id, name) ->
                        ToggleButton(checked = Prefs.layout.value == id, onCheckedChange = { Prefs.layout.value = id }) { Text(name) }
                    }
                }
            }
        }
        item { SwitchRow("Number row", "Always show 1-0 above the letters", Prefs.numberRow) }
        item { SwitchRow("Emoji key", "Show the emoji key next to the space bar", Prefs.showEmojiKey) }
    }
    SectionTitle("Size and position")
    Group {
        item { SliderRow("Keyboard height", Prefs.keyHeight.value, 0.8f..1.35f, "${(Prefs.keyHeight.value * 100).toInt()}%") { Prefs.keyHeight.value = it } }
        item { SliderRow("Space below keyboard", Prefs.bottomOffset.value, 0.3f..1.6f, "${(Prefs.bottomOffset.value * 100).toInt()}%") { Prefs.bottomOffset.value = it } }
        item { ChoiceRow("One-handed mode", listOf("Off", "Left", "Right"), Prefs.oneHanded.value.coerceIn(0, 2)) { Prefs.oneHanded.value = it } }
    }
}

@Composable
internal fun GesturesPage() {
    Group {
        item { SwitchRow("Glide typing", "Slide across letters to type", Prefs.swipeTyping) }
        item { SwitchRow("Show glide trail", pref = Prefs.swipeTrail, enabled = Prefs.swipeTyping.value) }
        item { SwitchRow("Space bar cursor", "Slide on the space bar to move the cursor", Prefs.spaceCursor) }
        item { SwitchRow("Swipe to delete words", "Slide left from backspace to select words, release to delete", Prefs.deleteSwipe) }
        item { SliderRow("Long-press delay", Prefs.longPressDelay.value.toFloat(), 150f..700f, "${Prefs.longPressDelay.value} ms") { Prefs.longPressDelay.value = it.toInt() } }
    }
}

@Composable
internal fun CorrectionPage() {
    var learned by remember { mutableStateOf(UserDictionary.learnedCount()) }
    SectionTitle("Suggestions")
    Group {
        item { SwitchRow("Suggestion strip", pref = Prefs.suggestions) }
        item { SwitchRow("Auto-correction", "Fix typos on space or punctuation. Backspace right after to undo.", Prefs.autocorrect) }
        item { SwitchRow("Space after picking a suggestion", pref = Prefs.autoSpace) }
        item { SwitchRow("Block offensive words", pref = Prefs.blockOffensive) }
    }
    SectionTitle("Writing")
    Group {
        item { SwitchRow("Auto-capitalization", pref = Prefs.autoCap) }
        item { SwitchRow("Double-space for period", "Tap space twice to type a period", Prefs.doubleSpacePeriod) }
    }
    SectionTitle("Personal dictionary")
    Group {
        item { SwitchRow("Learn new words", "Words you use get added automatically", Prefs.learnWords) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Learned words", fontSize = 16.sp)
                    Text("$learned words", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { UserDictionary.clear(); learned = 0 }) { Text("Clear") }
            }
        }
    }
}

@Composable
internal fun FeedbackPage() {
    Group {
        item { SwitchRow("Vibrate on keypress", pref = Prefs.haptics) }
        item {
            SliderRow("Vibration strength", Prefs.hapticStrength.value, 0f..1f, "${(Prefs.hapticStrength.value * 100).toInt()}%") { Prefs.hapticStrength.value = it }
        }
        item { SwitchRow("Sound on keypress", pref = Prefs.sound) }
        item { SliderRow("Sound volume", Prefs.soundVolume.value, 0f..1f, "${(Prefs.soundVolume.value * 100).toInt()}%") { Prefs.soundVolume.value = it } }
    }
}

@Composable
internal fun ClipboardPage() {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var granted by remember { mutableStateOf(com.vis.expressivekeyboard.data.RecentImages.hasPermission(ctx)) }
    Group {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Recent photos & screenshots", fontSize = 16.sp)
                    Text(if (granted) "Shown in the clipboard panel, tap to insert" else "Allow photo access to paste screenshots from the keyboard", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!granted) FilledTonalButton(onClick = { launcher.launch(com.vis.expressivekeyboard.data.RecentImages.permission()); granted = true }) { Text("Allow") }
            }
        }
        item { SwitchRow("Clipboard history", "Keep recent copies, including images, in the clipboard panel", Prefs.clipboardHistory) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Saved items", fontSize = 16.sp)
                    Text("${com.vis.expressivekeyboard.data.ClipboardStore.items.size} items, pinned items are kept", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { com.vis.expressivekeyboard.data.ClipboardStore.clearUnpinned() }) { Text("Clear") }
            }
        }
    }
}

// --------------------------------------------------------------- AI

internal fun fmtSize(b: Long): String = if (b > 1_000_000_000) "%.1f GB".format(b / 1e9) else "${b / 1_000_000} MB"

@Composable
internal fun AiGroup() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val state = AiEngine.state.value
    var installed by remember { mutableStateOf(AiEngine.installedModels()) }
    LaunchedEffect(state, Prefs.modelPath.value) { installed = AiEngine.installedModels() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) AiEngine.importModel(uri)
    }
    if (!AiEngine.supported) {
        Group {
            item {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("Not available on this device", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "The on-device AI runtime (LiteRT-LM) only supports 64-bit phones. Typing, suggestions, glide typing " +
                            "and voice typing all work normally.",
                        fontSize = 13.sp, color = cs.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }
    Group {
        item {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(MaterialShapes.Sunny.toShape()).background(cs.primary), contentAlignment = Alignment.Center) {
                        Icon(Sym.AutoAwesome, null, tint = cs.onPrimary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(AiEngine.modelDisplayName(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (state) {
                                AiState.NO_MODEL -> "Download a model below to enable AI corrections"
                                AiState.DOWNLOADING -> "Downloading… ${(AiEngine.progress.floatValue * 100).toInt()}%"
                                AiState.IMPORTING -> "Importing… ${(AiEngine.progress.floatValue * 100).toInt()}%"
                                AiState.LOADING -> "Loading into memory…"
                                AiState.READY -> "Loaded · runs fully offline"
                                AiState.IDLE -> "Installed · loads on demand"
                                AiState.ERROR -> AiEngine.message.value
                            },
                            fontSize = 13.sp, color = cs.onSurfaceVariant,
                        )
                    }
                    if (state == AiState.LOADING) LoadingIndicator(Modifier.size(36.dp))
                }
                if (state == AiState.DOWNLOADING || state == AiState.IMPORTING) {
                    Spacer(Modifier.height(14.dp))
                    LinearWavyProgressIndicator(progress = { AiEngine.progress.floatValue }, modifier = Modifier.fillMaxWidth())
                    if (state == AiState.DOWNLOADING) TextButton(onClick = { AiEngine.cancelDownload() }) { Text("Cancel download") }
                }
            }
        }
        ModelCatalog.models.forEach { m ->
            item {
                val file = File(AiEngine.modelsDir(), m.fileName)
                val isInstalled = installed.any { it.name == m.fileName }
                val active = Prefs.modelPath.value == file.absolutePath
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(m.name, fontSize = 16.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                            if (active) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Sym.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                        Text("${m.description} · ${fmtSize(m.sizeBytes)}", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    }
                    when {
                        isInstalled && !active -> FilledTonalButton(onClick = { AiEngine.selectModel(file) }) { Text("Use") }
                        isInstalled -> IconButton(onClick = { AiEngine.deleteModel(file) }) { Icon(Sym.Delete, "Delete") }
                        else -> FilledTonalButton(onClick = { AiEngine.startDownload(m) }, enabled = state != AiState.DOWNLOADING) {
                            Icon(Sym.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Get")
                        }
                    }
                }
            }
        }
        installed.filter { f -> ModelCatalog.models.none { it.fileName == f.name } }.forEach { f ->
            item {
                val active = Prefs.modelPath.value == f.absolutePath
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontSize = 15.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                        Text("Imported · ${fmtSize(f.length())}", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    }
                    if (!active) FilledTonalButton(onClick = { AiEngine.selectModel(f) }) { Text("Use") }
                    IconButton(onClick = { AiEngine.deleteModel(f) }) { Icon(Sym.Delete, "Delete") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Import a model file", fontSize = 16.sp)
                    Text("Any LiteRT-LM .litertlm model (e.g. from huggingface.co/litert-community)", fontSize = 13.sp, color = cs.onSurfaceVariant)
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Sym.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import")
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Group {
        item { SwitchRow("AI features", "Master switch for on-device AI", Prefs.aiEnabled) }
        item {
            SwitchRow(
                "Sentence-level correction",
                "When you finish a sentence (. ! ?), the AI checks the whole sentence for grammar and context, not just single words",
                Prefs.aiSentenceFix, enabled = Prefs.aiEnabled.value,
            )
        }
        item {
            SwitchRow(
                "Apply sentence fixes automatically",
                "Otherwise the fix is offered as a chip you can tap. Either way you can undo.",
                Prefs.aiAutoApply, enabled = Prefs.aiEnabled.value && Prefs.aiSentenceFix.value,
            )
        }
        item {
            SwitchRow(
                "AI next-word prediction",
                "Adds a context-aware ✨ prediction to the suggestion strip after you pause (uses more battery)",
                Prefs.aiPredictions, enabled = Prefs.aiEnabled.value,
            )
        }
        item { ChoiceRow("Run on", listOf("CPU", "GPU"), if (Prefs.aiBackend.value == "gpu") 1 else 0) { Prefs.aiBackend.value = if (it == 1) "gpu" else "cpu"; AiEngine.reset() } }
        item {
            var open by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { open = !open }) {
                        Text("Diagnostics", fontSize = 16.sp)
                        Text(if (open) "Tap to hide" else "Tap to show the AI log, or reset if it hangs", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    }
                    TextButton(onClick = { AiEngine.reset() }) { Text("Reset AI") }
                }
                if (open) {
                    val clip = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
                    val text = AiEngine.diag.joinToString("\n").ifEmpty { "(no AI activity yet)" }
                    Text(text, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = { clip.setPrimaryClip(android.content.ClipData.newPlainText("AI log", text)) }) { Text("Copy log") }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    AiTestCard()
    @Suppress("UNUSED_EXPRESSION") ctx
}

private object AiTestState {
    val output = mutableStateOf<String?>(null)
    val busy = mutableStateOf(false)
    val ms = mutableStateOf(0L)
}

@Composable
private fun AiTestCard() {
    val cs = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("me and him goes to the libary yesterday becuase we was need some book") }
    val samples = listOf(
        "me and him goes to the libary yesterday becuase we was need some book",
        "ieri sono andato al mare con i mio amici e abbiamo mangiato un gelato buonisimo",
        "mañana voy a ir a la playa con mis amigo si no llueve",
    )
    var output by AiTestState.output
    var busy by AiTestState.busy
    var ms by AiTestState.ms
    Surface(shape = RoundedCornerShape(24.dp), color = cs.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Test sentence correction", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = cs.onSecondaryContainer)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("EN", "IT", "ES").forEachIndexed { i, l -> TextButton(onClick = { input = samples[i] }) { Text(l) } }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy && AiEngine.hasModel(), onClick = {
                    busy = true; output = null
                    AiEngine.workScope.launch {
                        val t0 = System.currentTimeMillis()
                        output = AiEngine.process(input, AiTask.FIX)
                            ?: (AiEngine.message.value.ifEmpty { "No result" } + "\nModel said: " + AiEngine.lastRaw.value.ifEmpty { "(nothing)" })
                        ms = System.currentTimeMillis() - t0
                        busy = false
                    }
                }) { Text("Fix it") }
                Spacer(Modifier.width(12.dp))
                if (busy) LoadingIndicator(Modifier.size(36.dp))
                if (!AiEngine.hasModel()) Text("Install a model first", fontSize = 13.sp, color = cs.onSecondaryContainer)
            }
            output?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 15.sp, color = cs.onSecondaryContainer)
                Text("${ms} ms (first run includes loading the model)", fontSize = 12.sp, color = cs.onSecondaryContainer.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
internal fun AboutGroup() {
    UpdateGroup()
    SectionTitle("About")
    Group {
        item {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("Expressive Keyboard ${com.vis.expressivekeyboard.BuildConfig.VERSION_NAME}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("by Visiuun · github.com/visiuun/expressive-keyboard", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Built with Jetpack Compose and Material 3 Expressive. Word prediction uses on-device statistical models " +
                        "(frequency lists from OpenSubtitles via hermitdave/FrequencyWords, CC-BY-SA 4.0; word pairs from Tatoeba, CC-BY 2.0 FR). " +
                        "AI writing runs locally with Google LiteRT-LM (Qwen3.5 Apache-2.0, Gemma 4 Apache-2.0, LFM2.5 LFM Open License). " +
                        "Voice typing runs locally with sherpa-onnx (Parakeet TDT v3 CC-BY-4.0, Whisper MIT). Nothing you type or say leaves your device; " +
                        "the only network requests are model downloads you start and the update check against GitHub.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Licensed CC BY-NC 4.0: free for non-commercial use with credit.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
