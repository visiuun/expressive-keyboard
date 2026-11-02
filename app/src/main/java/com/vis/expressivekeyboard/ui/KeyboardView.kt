package com.vis.expressivekeyboard.ui

import android.content.res.Configuration
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vis.expressivekeyboard.data.ClipboardStore
import com.vis.expressivekeyboard.data.Prefs
import com.vis.expressivekeyboard.engine.GestureDecoder
import com.vis.expressivekeyboard.keyboard.AiChip
import com.vis.expressivekeyboard.keyboard.FieldKind
import com.vis.expressivekeyboard.keyboard.Key
import com.vis.expressivekeyboard.keyboard.KeyType
import com.vis.expressivekeyboard.keyboard.KeyboardController
import com.vis.expressivekeyboard.keyboard.Layouts
import com.vis.expressivekeyboard.keyboard.Mode
import com.vis.expressivekeyboard.keyboard.Panel
import com.vis.expressivekeyboard.keyboard.Shift
import com.vis.expressivekeyboard.keyboard.StripItem
import com.vis.expressivekeyboard.keyboard.StripType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

val STRIP_HEIGHT = 48.dp

@Composable
fun rowHeight(): Dp {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    return 53.dp * Prefs.keyHeight.value * (if (landscape) 0.72f else 1f)
}

@Composable
fun KeyboardRoot(controller: KeyboardController) {
    KeyboardTheme {
        val kc = LocalKeyColors.current
        val rowH = rowHeight()
        val alphaRows = if (Prefs.numberRow.value) 5 else 4
        val areaH = rowH * alphaRows + 5.dp
        Column(
            Modifier
                .fillMaxWidth()
                .background(kc.background)
                .imeBottomPadding(),
        ) {
            TopStrip(controller)
            Box(Modifier.fillMaxWidth().height(areaH)) {
                val eff = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                val spa = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
                AnimatedContent(
                    targetState = controller.panel,
                    transitionSpec = {
                        (fadeIn(eff) + scaleIn(spa, initialScale = 0.96f)) togetherWith fadeOut(eff)
                    },
                    label = "panel",
                ) { p ->
                    when (p) {
                        Panel.KEYS -> OneHandedWrapper(controller) { KeysArea(controller, rowH) }
                        Panel.EMOJI -> EmojiPanel(controller)
                        Panel.CLIPBOARD -> ClipboardPanel(controller)
                        Panel.AI -> AiPanel(controller)
                        Panel.VOICE -> VoicePanel(controller)
                    }
                }
            }
        }
    }
}

@Composable
private fun OneHandedWrapper(controller: KeyboardController, content: @Composable () -> Unit) {
    val side = Prefs.oneHanded.value
    if (side == 0) { content(); return }
    val kc = LocalKeyColors.current
    Row(Modifier.fillMaxSize()) {
        @Composable
        fun SideBar() {
            Column(
                Modifier.width(56.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(onClick = { Prefs.oneHanded.value = 0 }) {
                    Icon(Sym.OpenInFull, "Exit one-handed", tint = kc.funcText)
                }
                IconButton(onClick = { Prefs.oneHanded.value = if (side == 1) 2 else 1 }) {
                    Icon(Sym.SwapHoriz, "Switch side", tint = kc.funcText)
                }
            }
        }
        if (side == 2) SideBar()
        Box(Modifier.weight(1f)) { content() }
        if (side == 1) SideBar()
    }
}

// ============================================================================ top strip

private enum class StripMode { SUGGESTIONS, TOOLBAR, CHIP, CLIP }

@Composable
fun TopStrip(controller: KeyboardController) {
    val composing = controller.composingText
    val chip = controller.aiChip
    val clip = ClipboardStore.freshClip.value
    val clipFresh = (clip != null && System.currentTimeMillis() - clip.time < 120_000) || controller.freshShot != null
    val mode = when {
        controller.panel != Panel.KEYS -> StripMode.TOOLBAR
        composing.isNotEmpty() && controller.strip.isNotEmpty() -> StripMode.SUGGESTIONS
        chip != null -> StripMode.CHIP
        controller.forceToolbar -> StripMode.TOOLBAR
        clipFresh && composing.isEmpty() && controller.field != FieldKind.PASSWORD -> StripMode.CLIP
        controller.strip.isNotEmpty() -> StripMode.SUGGESTIONS
        else -> StripMode.TOOLBAR
    }
    Box(Modifier.fillMaxWidth().height(STRIP_HEIGHT)) {
        val eff = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = mode,
            transitionSpec = { fadeIn(eff) togetherWith fadeOut(eff) },
            label = "strip",
        ) { m ->
            when (m) {
                StripMode.SUGGESTIONS -> SuggestionRow(controller)
                StripMode.TOOLBAR -> Toolbar(controller)
                StripMode.CHIP -> AiChipRow(controller)
                StripMode.CLIP -> ClipRow(controller)
            }
        }
    }
}

@Composable
private fun StripToggle(controller: KeyboardController, open: Boolean) {
    val kc = LocalKeyColors.current
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(kc.func.takeIf { kc.borders } ?: Color.Transparent)
            .combinedClickable(onClick = { controller.forceToolbar = !open }),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (open) Sym.ChevronLeft else Sym.ChevronRight, "Toolbar", tint = kc.funcText, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SuggestionRow(controller: KeyboardController) {
    val kc = LocalKeyColors.current
    val view = LocalView.current
    val items = controller.strip
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        StripToggle(controller, open = false)
        items.forEachIndexed { i, item ->
            if (i > 0) Box(Modifier.width(1.dp).height(20.dp).background(kc.stripText.copy(alpha = 0.15f)))
            SuggestionCell(item, Modifier.weight(1f).fillMaxHeight(), onClick = {
                controller.feedback(view, null); controller.pickSuggestion(item)
            }, onLong = { if (item.type != StripType.LITERAL) controller.blockSuggestion(item) })
        }
        if (items.size < 3) repeat(3 - items.size) { Spacer(Modifier.weight(1f)) }
        Box(
            Modifier.padding(end = 6.dp).size(36.dp).clip(CircleShape)
                .combinedClickable(onClick = { controller.openPanel(Panel.VOICE) }),
            contentAlignment = Alignment.Center,
        ) { Icon(Sym.Mic, "Voice typing", tint = kc.stripText.copy(alpha = 0.8f), modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun SuggestionCell(item: StripItem, modifier: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    val kc = LocalKeyColors.current
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong),
        contentAlignment = Alignment.Center,
    ) {
        when (item.type) {
            StripType.AUTO -> Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(item.text, color = cs.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StripType.AI -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Sym.AutoAwesome, null, tint = cs.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(item.text, color = cs.primary, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StripType.LITERAL -> Text("“${item.text}”", color = kc.stripText.copy(alpha = 0.8f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            StripType.EMOJI -> Text(item.text, fontSize = 22.sp)
            else -> Text(item.text, color = kc.stripText, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, desc: String, active: Boolean = false, onClick: () -> Unit) {
    val kc = LocalKeyColors.current
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (active) cs.primaryContainer else Color.Transparent, MaterialTheme.motionScheme.fastEffectsSpec(), label = "tb")
    val corner by animateDpAsState(if (active) 12.dp else 20.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "tbc")
    Box(
        Modifier
            .padding(horizontal = 2.dp)
            .size(width = 44.dp, height = 40.dp)
            .clip(RoundedCornerShape(corner))
            .background(bg)
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (active) cs.onPrimaryContainer else kc.stripText.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AiButton(controller: KeyboardController) {
    val cs = MaterialTheme.colorScheme
    val active = controller.panel == Panel.AI
    val rot by animateFloatAsState(if (active) 90f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "ai")
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .size(40.dp)
            .combinedClickable(onClick = { controller.openPanel(Panel.AI) }),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .rotate(rot)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(if (active) cs.primary else cs.tertiaryContainer),
        )
        Icon(Sym.AutoAwesome, "AI writing tools", tint = if (active) cs.onPrimary else cs.onTertiaryContainer, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Toolbar(controller: KeyboardController) {
    val kc = LocalKeyColors.current
    val hasSuggestions = controller.strip.isNotEmpty() && controller.panel == Panel.KEYS
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (controller.forceToolbar && hasSuggestions) StripToggle(controller, open = true) else Spacer(Modifier.width(4.dp))
        AiButton(controller)
        ToolButton(Sym.Mic, "Voice typing", controller.panel == Panel.VOICE) { controller.openPanel(Panel.VOICE) }
        ToolButton(Sym.Mood, "Emoji", controller.panel == Panel.EMOJI) { controller.openPanel(Panel.EMOJI) }
        ToolButton(Sym.ContentPaste, "Clipboard", controller.panel == Panel.CLIPBOARD) { controller.openPanel(Panel.CLIPBOARD) }
        ToolButton(Sym.BackHand, "One-handed", Prefs.oneHanded.value != 0) {
            Prefs.oneHanded.value = if (Prefs.oneHanded.value == 0) 2 else 0
            if (controller.panel != Panel.KEYS) controller.panel = Panel.KEYS
        }
        ToolButton(Sym.Settings, "Settings") { controller.openSettings() }
        Spacer(Modifier.weight(1f))
        ToolButton(Sym.KeyboardHide, "Hide keyboard") { controller.hideKeyboard() }
        Spacer(Modifier.width(4.dp))
        @Suppress("UNUSED_EXPRESSION") kc
    }
}

@Composable
private fun AiChipRow(controller: KeyboardController) {
    val chip = controller.aiChip ?: return
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(19.dp))
                .background(if (chip.kind == AiChip.Kind.SUGGEST) cs.primaryContainer else cs.secondaryContainer)
                .combinedClickable(onClick = { controller.onChipTap() })
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = if (chip.kind == AiChip.Kind.SUGGEST) cs.onPrimaryContainer else cs.onSecondaryContainer
            when (chip.kind) {
                AiChip.Kind.WORKING -> LoadingIndicator(Modifier.size(26.dp), color = cs.primary)
                AiChip.Kind.OK -> Icon(Sym.Check, null, tint = fg, modifier = Modifier.size(18.dp))
                else -> Icon(Sym.AutoAwesome, null, tint = fg, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            val text = when (chip.kind) {
                AiChip.Kind.WORKING -> "Checking sentence…"
                AiChip.Kind.SUGGEST -> chip.result
                AiChip.Kind.UNDO -> "Sentence fixed · tap to undo"
                AiChip.Kind.OK -> "Sentence looks good"
                AiChip.Kind.ERROR -> "AI unavailable"
            }
            Text(text, color = fg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        IconButton(onClick = { controller.dismissChip() }) {
            Icon(Sym.Close, "Dismiss", tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun ClipRow(controller: KeyboardController) {
    val cs = MaterialTheme.colorScheme
    val clip = ClipboardStore.freshClip.value
    if (clip == null) {
        val shot = controller.freshShot ?: return
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StripToggle(controller, open = false)
            Spacer(Modifier.width(6.dp))
            Row(
                Modifier.weight(1f, fill = false).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(cs.secondaryContainer)
                    .combinedClickable(onClick = { controller.pasteMediaImage(shot.uri, shot.mime) })
                    .padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaThumb(shot.uri, Modifier.size(width = 48.dp, height = 28.dp).clip(RoundedCornerShape(14.dp)))
                Spacer(Modifier.width(8.dp))
                Text(if (shot.name.contains("screenshot", true)) "Paste screenshot" else "Paste photo", color = cs.onSecondaryContainer, fontSize = 15.sp)
            }
            Spacer(Modifier.weight(0.01f))
            IconButton(onClick = { controller.dismissShot() }) { Icon(Sym.Close, "Dismiss", tint = cs.onSurfaceVariant) }
        }
        return
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StripToggle(controller, open = false)
        Spacer(Modifier.width(6.dp))
        Row(
            Modifier
                .weight(1f, fill = false)
                .fillMaxHeight()
                .clip(RoundedCornerShape(18.dp))
                .background(cs.secondaryContainer)
                .combinedClickable(onClick = { controller.pasteClip(clip) })
                .padding(start = if (clip.isImage) 4.dp else 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (clip.isImage) {
                ClipThumb(clip.imagePath!!, Modifier.size(width = 48.dp, height = 28.dp).clip(RoundedCornerShape(14.dp)))
                Spacer(Modifier.width(8.dp))
                Text("Paste image", color = cs.onSecondaryContainer, fontSize = 15.sp)
            } else {
                Icon(Sym.ContentPaste, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(clip.text.replace('\n', ' '), color = cs.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.weight(0.01f))
        IconButton(onClick = { ClipboardStore.freshClip.value = null }) { Icon(Sym.Close, "Dismiss", tint = cs.onSurfaceVariant) }
    }
}

// ============================================================================ keys

data class KeyRect(val index: Int, val row: Int, val key: Key, val left: Float, val top: Float, val width: Float, val height: Float) {
    val center get() = Offset(left + width / 2f, top + height / 2f)
    val right get() = left + width
}

class PopupState(
    val anchor: KeyRect,
    val options: List<String>,
    val left: Float,
    val top: Float,
    val itemW: Float,
    val itemH: Float,
    val cols: Int,
    val pointer: Long,
    selected: Int,
) {
    val selected = mutableStateOf(selected)
    val rows get() = (options.size + cols - 1) / cols
}

private fun computeRects(rows: List<List<Key>>, width: Float, rowH: Float, hPad: Float, vPad: Float): Pair<List<KeyRect>, Float> {
    val maxUnits = rows.maxOf { r -> r.sumOf { it.width.toDouble() }.toFloat() }.coerceAtLeast(1f)
    val unit = (width - hPad * 2) / maxUnits
    val out = ArrayList<KeyRect>()
    var idx = 0
    rows.forEachIndexed { r, row ->
        val units = row.sumOf { it.width.toDouble() }.toFloat()
        var x = hPad + (maxUnits - units) * unit / 2f
        val y = vPad + r * rowH
        for (k in row) {
            val w = k.width * unit
            out += KeyRect(idx++, r, k, x, y, w, rowH)
            x += w
        }
    }
    return out to unit
}

private class Tracker(val id: Long, val start: Offset, var key: KeyRect?) {
    var longJob: Job? = null
    var repeatJob: Job? = null
    var consumed = false
    var swiping = false
    val path = ArrayList<Offset>()
    var spaceDrag = false
    var spaceAnchorX = start.x
    var deleteDrag = false
    var deleteWords = 0
    var repeated = false
}

class TouchHandler(private val controller: KeyboardController, private val scope: CoroutineScope) {
    var rects: List<KeyRect> = emptyList()
    var pressed: List<MutableState<Boolean>> = emptyList()
    var rowH = 0f
    var vPad = 0f
    var unitW = 0f
    var totalW = 0f
    var view: View? = null
    var density = 1f
    val preview = mutableStateOf<KeyRect?>(null)
    val popup = mutableStateOf<PopupState?>(null)
    val trail = mutableStateListOf<Offset>()
    private val trackers = HashMap<Long, Tracker>()

    private fun hit(p: Offset): KeyRect? {
        if (rects.isEmpty()) return null
        val maxRow = rects.last().row
        val row = ((p.y - vPad) / rowH).toInt().coerceIn(0, maxRow)
        var best: KeyRect? = null
        var bestD = Float.MAX_VALUE
        for (r in rects) {
            if (r.row != row) continue
            if (p.x >= r.left && p.x < r.right) return r
            val d = minOf(abs(p.x - r.left), abs(p.x - r.right))
            if (d < bestD) { bestD = d; best = r }
        }
        return best
    }

    private fun press(r: KeyRect?, on: Boolean) {
        if (r == null) return
        pressed.getOrNull(r.index)?.value = on
    }

    private fun popupOptions(k: Key): List<String> = when {
        k.type == KeyType.CHAR -> k.popup
        else -> emptyList()
    }

    private fun scheduleLong(t: Tracker) {
        t.longJob?.cancel()
        val k = t.key ?: return
        t.longJob = scope.launch {
            delay(Prefs.longPressDelay.value.toLong())
            when (k.key.type) {
                KeyType.SHIFT -> { controller.onShiftLongPress(); t.consumed = true }
                KeyType.SPACE -> { if (!t.spaceDrag) { t.consumed = true; press(k, false); controller.switchKeyboard() } }
                KeyType.CHAR -> {
                    val opts = popupOptions(k.key).map { if (it.length == 1 && it[0].isLetter() && controller.shift != Shift.OFF) it.uppercase() else it }
                    if (opts.isNotEmpty()) {
                        showPopup(t, k, opts)
                        view?.let { controller.feedback(it, null) }
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Gboard-style accent grid: compact (max 5 per row), centred on the key, and the default choice
     * (the key's hint) sits directly above the finger in the bottom row. Empty grid cells are "".
     */
    private fun showPopup(t: Tracker, k: KeyRect, opts: List<String>) {
        val n = opts.size
        val cols = if (n <= 5) n else (n + 1) / 2
        val rows = (n + cols - 1) / cols
        val longest = opts.maxOf { it.length }
        val itemW = (unitW * (if (longest > 2) 1.5f else 1.0f)).coerceAtLeast(44f * density)
        val itemH = rowH * 1.0f
        val w = cols * itemW
        val margin = 4 * density
        // the default cell is centred exactly over the key; near a screen edge the grid grows the other way
        var defaultCol = cols / 2
        var left = k.center.x - (defaultCol + 0.5f) * itemW
        while (left < margin && defaultCol > 0) { defaultCol--; left = k.center.x - (defaultCol + 0.5f) * itemW }
        while (left + w > totalW - margin && defaultCol < cols - 1) { defaultCol++; left = k.center.x - (defaultCol + 0.5f) * itemW }
        left = left.coerceIn(margin, (totalW - margin - w).coerceAtLeast(margin))
        val top = k.top - rows * itemH - 6 * density
        val sel = (rows - 1) * cols + defaultCol
        // Gboard placement: the default sits above the finger; the others fan out from that column
        // alternating right, left, right, ... first along the bottom row, then the row above.
        val grid = Array(rows * cols) { "" }
        grid[sel] = opts[0]
        val rest = opts.drop(1).iterator()
        val order = ArrayList<Int>()
        order += defaultCol
        for (d in 1 until cols) { if (defaultCol + d < cols) order += defaultCol + d; if (defaultCol - d >= 0) order += defaultCol - d }
        for (r in rows - 1 downTo 0) {
            for (c in order) {
                val i = r * cols + c
                if (i == sel) continue
                if (!rest.hasNext()) break
                grid[i] = rest.next()
            }
        }
        popup.value = PopupState(k, grid.toList(), left, top, itemW, itemH, cols, t.id, sel)
        preview.value = null
    }

    fun down(id: Long, p: Offset) {
        // roll-over typing: commit any pending key from another finger first
        for (o in trackers.values) {
            if (!o.consumed && !o.swiping && !o.spaceDrag && !o.deleteDrag && popup.value?.pointer != o.id) {
                val k = o.key
                if (k != null && k.key.type != KeyType.DELETE && k.key.type != KeyType.SHIFT) {
                    o.longJob?.cancel()
                    controller.onKey(k.key)
                    o.consumed = true
                    press(k, false)
                }
            }
        }
        val k = hit(p)
        val t = Tracker(id, p, k)
        trackers[id] = t
        t.path += p
        press(k, true)
        view?.let { controller.feedback(it, k?.key) }
        val key = k?.key ?: return
        when (key.type) {
            KeyType.DELETE -> {
                // tap deletes on release; holding starts auto-repeat; sliding left selects words instead
                t.repeatJob = scope.launch {
                    delay(380)
                    t.repeated = true
                    controller.onDelete(false)
                    delay(120)
                    var n = 0
                    while (true) {
                        controller.onDelete(true)
                        n++
                        delay(if (n > 12) 35 else 60)
                    }
                }
            }
            KeyType.SHIFT -> { controller.onShiftDown(); scheduleLong(t) }
            KeyType.CHAR -> {
                if (Prefs.keyPopup.value) preview.value = k
                scheduleLong(t)
            }
            else -> scheduleLong(t)
        }
    }

    fun move(id: Long, p: Offset) {
        val t = trackers[id] ?: return
        if (t.consumed && !t.swiping) return
        val pop = popup.value
        if (pop != null && pop.pointer == id) {
            val col = ((p.x - pop.left) / pop.itemW).toInt().coerceIn(0, pop.cols - 1)
            val row = ((p.y - pop.top) / pop.itemH).toInt().coerceIn(0, pop.rows - 1)
            val i = (row * pop.cols + col).coerceIn(0, pop.options.size - 1)
            if (pop.options[i].isNotEmpty()) pop.selected.value = i
            return
        }
        val k = t.key
        val dx = p.x - t.start.x
        val dy = p.y - t.start.y
        if (k != null && k.key.type == KeyType.SPACE && Prefs.spaceCursor.value) {
            if (!t.spaceDrag && abs(dx) > 16 * density) {
                t.spaceDrag = true; t.longJob?.cancel(); t.spaceAnchorX = p.x
            }
            if (t.spaceDrag) {
                val step = unitW * 0.42f
                val steps = ((p.x - t.spaceAnchorX) / step).toInt()
                if (steps != 0) {
                    controller.moveCursor(steps)
                    t.spaceAnchorX += steps * step
                    view?.let { if (Prefs.haptics.value) it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                }
            }
            return
        }
        if (k != null && k.key.type == KeyType.DELETE && Prefs.deleteSwipe.value) {
            val step = unitW * 1.1f
            if (!t.repeated && abs(dx) > 10 * density) t.repeatJob?.cancel()
            if (!t.deleteDrag && !t.repeated && -dx > step) { t.deleteDrag = true; t.repeatJob?.cancel() }
            if (t.deleteDrag) {
                // hysteresis: grow when crossing the next step, shrink only after moving clearly back
                val raw = (-dx) / step
                var words = t.deleteWords
                while (raw >= words + 1) words++
                while (words > 0 && raw < words - 1.0f) words-- // shrink only after a full step back
                if (words != t.deleteWords) {
                    t.deleteWords = words
                    controller.deleteSwipeSelect(words)
                    view?.let { if (Prefs.haptics.value) it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                }
            }
            return
        }
        if (t.swiping) {
            t.path += p
            if (Prefs.swipeTrail.value) trail += p
            return
        }
        val canSwipe = k != null && k.key.isLetter && controller.mode == Mode.ALPHA && Prefs.swipeTyping.value &&
            controller.field == FieldKind.TEXT && trackers.size == 1
        if (canSwipe && (dx * dx + dy * dy) > (unitW * 0.6f) * (unitW * 0.6f)) {
            t.swiping = true
            t.longJob?.cancel()
            press(k, false)
            preview.value = null
            t.path += p
            if (Prefs.swipeTrail.value) { trail.clear(); trail.addAll(t.path) }
            return
        }
        t.path += p
        val nk = hit(p)
        if (nk != null && nk != k && k?.key?.type != KeyType.SHIFT) {
            press(k, false); press(nk, true)
            t.key = nk
            if (nk.key.type == KeyType.CHAR && Prefs.keyPopup.value) preview.value = nk else preview.value = null
            scheduleLong(t)
        }
    }

    fun up(id: Long, p: Offset) {
        val t = trackers.remove(id) ?: return
        t.longJob?.cancel(); t.repeatJob?.cancel()
        val k = t.key
        press(k, false)
        if (preview.value == k) preview.value = null
        val pop = popup.value
        if (pop != null && pop.pointer == id) {
            popup.value = null
            pop.options[pop.selected.value].takeIf { it.isNotEmpty() }?.let { controller.onPopupChoice(it) }
            return
        }
        if (t.swiping) {
            t.path += p
            val centers = rects.filter { it.key.isLetter }.associate { it.key.label[0] to it.center }
            val path = ArrayList(t.path)
            scope.launch {
                val gctx = controller.gestureContext()
                val words = withContext(Dispatchers.Default) { GestureDecoder.decode(path, centers, unitW, gctx) }
                controller.onGesture(words)
                delay(90)
                trail.clear()
            }
            return
        }
        if (t.consumed) return
        if (t.spaceDrag) return
        if (t.deleteDrag) { controller.deleteSwipeEnd(t.deleteWords > 0); return }
        val key = k?.key ?: return
        if (key.type == KeyType.DELETE) {
            if (!t.repeated && t.start.x - p.x < unitW * 0.9f) controller.onDelete(false)
            return
        }
        if (key.type == KeyType.SHIFT) return
        controller.onKey(key)
    }

    fun cancelAll() {
        trackers.values.forEach { it.longJob?.cancel(); it.repeatJob?.cancel(); press(it.key, false) }
        trackers.clear()
        popup.value = null
        preview.value = null
        trail.clear()
    }
}

@Composable
fun KeysArea(controller: KeyboardController, rowH: Dp) {
    val rows = remember(controller.mode, controller.field, Prefs.layout.value, Prefs.numberRow.value, Prefs.showHints.value, Prefs.showEmojiKey.value) {
        Layouts.rows(controller.mode, controller.field, Prefs.layout.value, Prefs.numberRow.value, Prefs.showHints.value).also { Prefs.showEmojiKey.value }
    }
    val scope = rememberCoroutineScope()
    val handler = remember(controller) { TouchHandler(controller, scope) }
    val view = LocalView.current
    val density = LocalDensity.current
    val kc = LocalKeyColors.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val rowPx = with(density) { rowH.toPx() }
        val hPad = with(density) { 1.dp.toPx() }
        val vPad = with(density) { 3.dp.toPx() }
        val (rects, unit) = remember(rows, wPx, rowPx) { computeRects(rows, wPx, rowPx, hPad, vPad) }
        val pressed = remember(rects) { List(rects.size) { mutableStateOf(false) } }
        handler.rects = rects
        handler.pressed = pressed
        handler.rowH = rowPx
        handler.vPad = vPad
        handler.unitW = unit
        handler.totalW = wPx
        handler.view = view
        handler.density = density.density

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(handler) {
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                for (ch in event.changes) {
                                    val id = ch.id.value
                                    when {
                                        ch.changedToDownIgnoreConsumed() -> handler.down(id, ch.position)
                                        ch.changedToUpIgnoreConsumed() -> handler.up(id, ch.position)
                                        ch.pressed && ch.positionChange() != Offset.Zero -> {
                                            for (h in ch.historical) handler.move(id, h.position)
                                            handler.move(id, ch.position)
                                        }
                                    }
                                    ch.consume()
                                }
                            }
                        }
                    } finally {
                        handler.cancelAll()
                    }
                },
        ) {
            val gap = 1.75.dp
            for (r in rects) {
                // a fresh composable per layout: no colour/shape "flash" from the key that used to sit here
                androidx.compose.runtime.key(rows, r.index) {
                    KeyCap(controller, r, pressed[r.index], gap)
                }
            }
            // gesture trail
            if (handler.trail.isNotEmpty()) {
                Canvas(Modifier.fillMaxSize()) {
                    val pts = handler.trail
                    val n = pts.size
                    if (n > 1) {
                        val w = 7.dp.toPx()
                        for (i in 1 until n) {
                            val f = i.toFloat() / n
                            drawLine(kc.trail.copy(alpha = 0.15f + 0.75f * f), pts[i - 1], pts[i], strokeWidth = w * (0.35f + 0.65f * f), cap = StrokeCap.Round)
                        }
                    }
                }
            }
            KeyPreview(handler, controller)
            PopupOverlay(handler)
        }
    }
}

@Composable
private fun KeyCap(controller: KeyboardController, r: KeyRect, pressedState: MutableState<Boolean>, gap: Dp) {
    val kc = LocalKeyColors.current
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val pressed = pressedState.value
    val key = r.key
    val type = key.type
    val isAccent = type == KeyType.ENTER
    val isFunc = type == KeyType.SHIFT || type == KeyType.DELETE || type == KeyType.SYMBOLS || type == KeyType.SYMBOLS2 ||
        type == KeyType.ALPHA || type == KeyType.EMOJI || type == KeyType.NUMPAD_SYMBOLS || key.functional
    val shiftState = controller.shift
    val shiftActive = type == KeyType.SHIFT && shiftState != Shift.OFF
    val base = when {
        isAccent -> kc.accent
        shiftActive && shiftState == Shift.LOCK -> cs.primary
        shiftActive -> cs.primaryContainer
        isFunc -> kc.func
        else -> kc.key
    }
    val target = if (pressed) {
        if (isAccent) androidx.compose.ui.graphics.lerp(kc.accent, cs.onPrimary, 0.18f) else kc.pressed
    } else base
    val color by animateColorAsState(target, MaterialTheme.motionScheme.fastEffectsSpec(), label = "kc")
    val hDp = with(density) { r.height.toDp() } - VGAP * 2
    val wDp = with(density) { r.width.toDp() } - gap * 2
    val funcStyle = Prefs.funcShape.value
    val baseRadius = when {
        type == KeyType.ENTER -> hDp / 2
        type == KeyType.SPACE -> when (Prefs.spaceShape.value) { "pill" -> hDp / 2; "square" -> 4.dp; else -> 9.dp }
        isFunc && funcStyle == "pill" -> hDp / 2
        isFunc && funcStyle == "square" -> 5.dp
        else -> 12.dp * Prefs.roundness.value
    }
    val pressedRadius = when (type) {
        KeyType.ENTER -> 14.dp
        KeyType.SPACE -> when (Prefs.spaceShape.value) { "pill" -> 14.dp; "square" -> 8.dp; else -> 14.dp }
        else -> hDp / 2
    }
    val corner by animateDpAsState(if (pressed) pressedRadius else baseRadius, MaterialTheme.motionScheme.fastSpatialSpec(), label = "kr")
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "ks")
    val fg = when {
        isAccent -> kc.accentText
        shiftActive && shiftState == Shift.LOCK -> cs.onPrimary
        shiftActive -> cs.onPrimaryContainer
        isFunc -> kc.funcText
        else -> kc.keyText
    }
    val enterShape = if (type == KeyType.ENTER) KeyShapes.enterShape(Prefs.enterShape.value) else null
    val slot = Modifier
        .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
        .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
        .padding(horizontal = gap, vertical = VGAP)
    if (enterShape != null) {
        // expressive shaped enter key: square shape centred in the slot, spins on press
        val rot by animateFloatAsState(if (pressed) 45f else 0f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "er")
        val side = minOf(hDp, wDp) * 1.04f
        Box(slot, contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(side)
                    .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = rot }
                    .clip(enterShape)
                    .background(color),
            )
            KeyLabel(controller, key, fg, shiftState)
        }
        return
    }
    Box(
        slot
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(corner))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        KeyLabel(controller, key, fg, shiftState)
        if (key.hint != null && Prefs.showHints.value && type == KeyType.CHAR) {
            Text(
                key.hint, color = kc.hint, fontSize = 9.sp, lineHeight = 9.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 4.dp),
            )
        }
    }
}

private val VGAP = 4.dp

@Composable
private fun KeyLabel(controller: KeyboardController, key: Key, fg: Color, shift: Shift) {
    when (key.type) {
        KeyType.CHAR -> {
            val label = controller.labelFor(key)
            val f = Prefs.labelSize.value
            Text(
                label, color = fg,
                fontSize = (if (label.length > 1) 16f else if (key.isLetter) 22f else 20f).times(f).sp,
                fontWeight = if (key.functional || Prefs.boldLabels.value) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
        KeyType.SHIFT -> Icon(
            when (shift) { Shift.OFF -> Sym.Shift; Shift.ONCE -> Sym.ShiftFilled; Shift.LOCK -> Sym.ShiftLock },
            "Shift", tint = fg, modifier = Modifier.size(24.dp),
        )
        KeyType.DELETE -> Icon(Sym.Backspace, "Delete", tint = fg, modifier = Modifier.size(22.dp))
        KeyType.ENTER -> {
            val icon = if (controller.enterIsNewline) Sym.KeyboardReturn else when (controller.enterAction) {
                EditorInfo.IME_ACTION_SEARCH -> Sym.Search
                EditorInfo.IME_ACTION_SEND -> Sym.Send
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> Sym.ArrowForward
                EditorInfo.IME_ACTION_PREVIOUS -> Sym.ArrowBack
                EditorInfo.IME_ACTION_DONE -> Sym.Check
                else -> Sym.KeyboardReturn
            }
            Icon(icon, "Enter", tint = fg, modifier = Modifier.size(24.dp))
        }
        KeyType.SPACE -> {
            if (controller.mode == Mode.ALPHA) {
                Text(
                    com.vis.expressivekeyboard.engine.Dictionary.loadedCodes.value.joinToString(" · ") { it.uppercase() }
                        .ifEmpty { Layouts.LETTER_LAYOUTS[Prefs.layout.value] ?: "" },
                    color = fg.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
        KeyType.EMOJI -> Icon(Sym.Mood, "Emoji", tint = fg, modifier = Modifier.size(22.dp))
        else -> Text(key.label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ShiftIcon(color: Color, shift: Shift) {
    Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension / 24f
        val path = Path().apply {
            moveTo(12f * s, 4f * s)
            lineTo(20.5f * s, 12.5f * s)
            lineTo(15.5f * s, 12.5f * s)
            lineTo(15.5f * s, if (shift == Shift.LOCK) 17.5f * s else 20f * s)
            lineTo(8.5f * s, if (shift == Shift.LOCK) 17.5f * s else 20f * s)
            lineTo(8.5f * s, 12.5f * s)
            lineTo(3.5f * s, 12.5f * s)
            close()
        }
        if (shift == Shift.OFF) {
            drawPath(path, color, style = Stroke(width = 1.8f * s, join = StrokeJoin.Round))
        } else {
            drawPath(path, color, style = Fill)
            drawPath(path, color, style = Stroke(width = 1.8f * s, join = StrokeJoin.Round))
        }
        if (shift == Shift.LOCK) {
            drawLine(color, Offset(8.5f * s, 21f * s), Offset(15.5f * s, 21f * s), strokeWidth = 2f * s, cap = StrokeCap.Round)
        }
    }
}

/** A window above the keyboard (Gboard style), so bubbles for the top row are not cut off by the keyboard's edge. */
@Composable
private fun OverlayWindow(left: Float, top: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val margin = with(density) { 16.dp.roundToPx() } // room for the shadow
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(left.roundToInt() - margin, top.roundToInt() - margin),
        properties = androidx.compose.ui.window.PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun KeyPreview(handler: TouchHandler, controller: KeyboardController) {
    val k = handler.preview.value ?: return
    val kc = LocalKeyColors.current
    val density = LocalDensity.current
    val w = maxOf(k.width * 1.1f, handler.unitW * 1.1f)
    val h = k.height * 1.05f
    val left = (k.center.x - w / 2f).coerceIn(0f, handler.totalW - w)
    val top = k.top - h - 2 * density.density
    val appear = remember(k) { androidx.compose.animation.core.Animatable(0.6f) }
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    androidx.compose.runtime.LaunchedEffect(k) { appear.animateTo(1f, spec) }
    OverlayWindow(left, top) {
    Box(
        Modifier
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .graphicsLayer {
                scaleX = appear.value; scaleY = appear.value
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                shadowElevation = 6.dp.toPx()
                shape = RoundedCornerShape(18.dp)
                clip = true
            }
            .background(kc.popup),
        contentAlignment = Alignment.Center,
    ) {
        Text(controller.labelFor(k.key), color = kc.popupText, fontSize = 30.sp)
    }
    }
}

@Composable
private fun PopupOverlay(handler: TouchHandler) {
    val pop = handler.popup.value ?: return
    val kc = LocalKeyColors.current
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val appear = remember(pop) { androidx.compose.animation.core.Animatable(0.7f) }
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    androidx.compose.runtime.LaunchedEffect(pop) { appear.animateTo(1f, spec) }
    OverlayWindow(pop.left, pop.top) {
    Box(
        Modifier
            .size(with(density) { (pop.itemW * pop.cols).toDp() }, with(density) { (pop.itemH * pop.rows).toDp() })
            .graphicsLayer {
                scaleX = appear.value; scaleY = appear.value
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                shadowElevation = 8.dp.toPx()
                shape = RoundedCornerShape(24.dp)
                clip = true
            }
            .background(kc.popup),
    ) {
        pop.options.forEachIndexed { i, opt ->
            val col = i % pop.cols
            val row = i / pop.cols
            val selected = pop.selected.value == i
            val bg by animateColorAsState(if (selected) cs.primary else Color.Transparent, MaterialTheme.motionScheme.fastEffectsSpec(), label = "ps")
            Box(
                Modifier
                    .offset { IntOffset((col * pop.itemW).roundToInt(), (row * pop.itemH).roundToInt()) }
                    .size(with(density) { pop.itemW.toDp() }, with(density) { pop.itemH.toDp() })
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                if (opt.isEmpty()) return@Box
                when (opt) {
                    "{settings}" -> Icon(Sym.Settings, "Settings", tint = if (selected) cs.onPrimary else kc.popupText)
                    "{clipboard}" -> Icon(Sym.ContentPaste, "Clipboard", tint = if (selected) cs.onPrimary else kc.popupText)
                    "{emoji}" -> Icon(Sym.Mood, "Emoji", tint = if (selected) cs.onPrimary else kc.popupText)
                    else -> Text(opt, color = if (selected) cs.onPrimary else kc.popupText, fontSize = if (opt.length > 2) 15.sp else 22.sp)
                }
            }
        }
    }
    }
}
