package com.vis.expressivekeyboard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vis.expressivekeyboard.ai.AiEngine
import com.vis.expressivekeyboard.ai.AiState
import com.vis.expressivekeyboard.ai.AiTask
import com.vis.expressivekeyboard.data.ClipboardStore
import com.vis.expressivekeyboard.data.Prefs
import com.vis.expressivekeyboard.engine.Emoji
import com.vis.expressivekeyboard.engine.EmojiData
import com.vis.expressivekeyboard.keyboard.KeyboardController
import com.vis.expressivekeyboard.keyboard.Panel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val groupIcons: Map<String, Int> = mapOf(
    "Smileys & Emotion" to com.vis.expressivekeyboard.R.drawable.sym_mood,
    "People & Body" to com.vis.expressivekeyboard.R.drawable.sym_emoji_people,
    "Animals & Nature" to com.vis.expressivekeyboard.R.drawable.sym_emoji_nature,
    "Food & Drink" to com.vis.expressivekeyboard.R.drawable.sym_emoji_food_beverage,
    "Travel & Places" to com.vis.expressivekeyboard.R.drawable.sym_directions_car,
    "Activities" to com.vis.expressivekeyboard.R.drawable.sym_emoji_events,
    "Objects" to com.vis.expressivekeyboard.R.drawable.sym_emoji_objects,
    "Symbols" to com.vis.expressivekeyboard.R.drawable.sym_emoji_symbols,
    "Flags" to com.vis.expressivekeyboard.R.drawable.sym_flag,
)

/** A button that fires repeatedly while held (used for backspace in panels). */
@Composable
fun RepeatKey(controller: KeyboardController, modifier: Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    Box(
        modifier.pointerInput(Unit) {
            detectTapGestures(onPress = {
                controller.feedback(view, null)
                controller.onDelete(false)
                val job: Job = scope.launch {
                    delay(400)
                    while (true) { controller.onDelete(true); delay(55) }
                }
                tryAwaitRelease()
                job.cancel()
            })
        },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PanelBottomBar(controller: KeyboardController, middle: @Composable () -> Unit = {}) {
    val kc = LocalKeyColors.current
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.fillMaxHeight().width(72.dp).clip(RoundedCornerShape(20.dp)).background(kc.func)
                .combinedClickable(onClick = { controller.panel = Panel.KEYS }),
            contentAlignment = Alignment.Center,
        ) { Text("ABC", color = kc.funcText, fontWeight = FontWeight.Bold) }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { middle() }
        RepeatKey(controller, Modifier.fillMaxHeight().width(72.dp).clip(RoundedCornerShape(20.dp)).background(kc.func)) {
            Icon(Sym.Backspace, "Delete", tint = kc.funcText)
        }
    }
}

// ============================================================================ emoji

@Composable
fun EmojiPanel(controller: KeyboardController) {
    val kc = LocalKeyColors.current
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    val groups = EmojiData.groups.value
    val recents = EmojiData.recents
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var toneFor by remember { mutableStateOf<Emoji?>(null) }

    // index of each group header in the flattened grid
    val headerIndex = remember(groups, recents.size) {
        val m = LinkedHashMap<String, Int>()
        var i = 0
        if (recents.isNotEmpty()) { m["Recent"] = 0; i += 1 + recents.size }
        for (g in groups) { m[g.name] = i; i += 1 + g.emojis.size }
        m
    }
    val currentGroup by remember(headerIndex) {
        derivedStateOf {
            val first = gridState.firstVisibleItemIndex
            headerIndex.entries.lastOrNull { it.value <= first }?.key ?: headerIndex.keys.firstOrNull()
        }
    }

    val query = controller.emojiQuery
    val results = remember(groups, query) { if (query.isBlank()) emptyList() else EmojiData.searchPanel(query) }

    Column(Modifier.fillMaxSize()) {
        EmojiSearchPill(controller, query)
        if (query.isNotBlank()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (results.isEmpty()) {
                    Text("No emoji found", color = kc.stripText.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(46.dp), modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                        items(results.size, key = { "s_${results[it].value}" }) { i ->
                            val e = results[i]
                            EmojiCell(e.value, hasTones = e.tones.isNotEmpty(),
                                onClick = { controller.feedback(view, null); controller.commitEmoji(e.value) },
                                onLong = { if (e.tones.isNotEmpty()) toneFor = e })
                        }
                    }
                }
                TonePicker(controller, toneFor) { toneFor = null }
            }
            PanelBottomBar(controller)
            return@Column
        }
        // category tabs
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            headerIndex.keys.forEach { name ->
                val selected = name == currentGroup
                val bg by animateColorAsState(if (selected) cs.secondaryContainer else Color.Transparent, MaterialTheme.motionScheme.fastEffectsSpec(), label = "et")
                val corner by animateDpAsState(if (selected) 12.dp else 20.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "ec")
                Box(
                    Modifier.weight(1f).height(36.dp).padding(horizontal = 1.dp).clip(RoundedCornerShape(corner)).background(bg)
                        .combinedClickable(onClick = { scope.launch { gridState.animateScrollToItem(headerIndex[name] ?: 0) } }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        androidx.compose.ui.graphics.vector.ImageVector.vectorResource(if (name == "Recent") com.vis.expressivekeyboard.R.drawable.sym_schedule else groupIcons[name] ?: com.vis.expressivekeyboard.R.drawable.sym_emoji_symbols),
                        name, tint = if (selected) cs.onSecondaryContainer else kc.stripText.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (groups.isEmpty()) {
                LoadingIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(46.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                ) {
                    fun header(title: String) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "h_$title") {
                            Text(title, color = cs.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp))
                        }
                    }
                    if (recents.isNotEmpty()) {
                        header("Recent")
                        items(recents.size, key = { "r_${recents[it]}_$it" }) { i ->
                            EmojiCell(recents[i], hasTones = false, onClick = { controller.feedback(view, null); controller.commitEmoji(recents[i]) }, onLong = {})
                        }
                    }
                    for (g in groups) {
                        header(g.name)
                        items(g.emojis.size, key = { "e_${g.name}_$it" }) { i ->
                            val e = g.emojis[i]
                            EmojiCell(e.value, hasTones = e.tones.isNotEmpty(),
                                onClick = { controller.feedback(view, null); controller.commitEmoji(e.value) },
                                onLong = { if (e.tones.isNotEmpty()) toneFor = e })
                        }
                    }
                }
            }
            TonePicker(controller, toneFor) { toneFor = null }
        }
        PanelBottomBar(controller)
    }
}

/** Skin-tone picker shown over the emoji grid. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TonePicker(controller: KeyboardController, toneFor: Emoji?, onDone: () -> Unit) {
    val kc = LocalKeyColors.current
    androidx.compose.animation.AnimatedVisibility(toneFor != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
        val e = toneFor
        if (e != null) {
            Row(
                Modifier.padding(8.dp).clip(RoundedCornerShape(24.dp)).background(kc.popup).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                (listOf(e.value) + e.tones).forEach { t ->
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).combinedClickable(onClick = {
                            controller.commitEmoji(t); onDone()
                        }),
                        contentAlignment = Alignment.Center,
                    ) { Text(t, fontSize = 26.sp) }
                }
                IconButton(onClick = onDone) { Icon(Sym.Close, "Close", tint = kc.popupText) }
            }
        }
    }
}

/** "Search emoji" box at the top of the emoji panel; tapping it brings up the letter keys. */
@Composable
private fun EmojiSearchPill(controller: KeyboardController, query: String) {
    val kc = LocalKeyColors.current
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHighest)
            .combinedClickable(onClick = { controller.startEmojiSearch() })
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Sym.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            query.ifEmpty { "Search emoji" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
            color = if (query.isEmpty()) kc.stripText.copy(alpha = 0.6f) else cs.onSurface, modifier = Modifier.weight(1f),
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { controller.clearEmojiQuery() }, modifier = Modifier.size(32.dp)) {
                Icon(Sym.Close, "Clear search", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

val EMOJI_RESULTS_HEIGHT = 52.dp

/** Search box shown in the top strip while the letter keys type into the emoji search. */
@Composable
fun EmojiSearchBar(controller: KeyboardController) {
    val kc = LocalKeyColors.current
    val cs = MaterialTheme.colorScheme
    val query = controller.emojiQuery
    val blink = androidx.compose.animation.core.rememberInfiniteTransition(label = "caret")
    val caret by blink.animateFloat(
        1f, 0f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(530, easing = { if (it < 0.5f) 0f else 1f }), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "caretA",
    )
    Row(Modifier.fillMaxSize().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { controller.endEmojiSearch(keepResults = false) }) {
            Icon(Sym.ArrowBack, "Back to emoji", tint = kc.stripText)
        }
        Row(
            Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(19.dp)).background(cs.surfaceContainerHighest).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Sym.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty()) Text(query, color = cs.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Box(Modifier.width(2.dp).height(18.dp).background(cs.primary.copy(alpha = caret)))
                    if (query.isEmpty()) Text("Search emoji", color = kc.stripText.copy(alpha = 0.6f), fontSize = 15.sp, modifier = Modifier.padding(start = 2.dp))
                }
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { controller.clearEmojiQuery() }, modifier = Modifier.size(32.dp)) {
                    Icon(Sym.Close, "Clear search", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}

/** Live results row above the letter keys while searching (recents while the box is empty). */
@Composable
fun EmojiSearchResults(controller: KeyboardController) {
    val kc = LocalKeyColors.current
    val view = LocalView.current
    val query = controller.emojiQuery
    val groups = EmojiData.groups.value
    val results = remember(groups, query) {
        if (query.isBlank()) EmojiData.recents.toList() else EmojiData.searchPanel(query, 60).map { it.value }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(query) { listState.scrollToItem(0) }
    Box(Modifier.fillMaxWidth().height(EMOJI_RESULTS_HEIGHT), contentAlignment = Alignment.CenterStart) {
        if (results.isEmpty()) {
            Text(
                if (query.isBlank()) "Type to search emoji" else "No emoji found",
                color = kc.stripText.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp),
            )
        } else {
            androidx.compose.foundation.lazy.LazyRow(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                items(results.size, key = { "q_${results[it]}_$it" }) { i ->
                    Box(
                        Modifier.size(EMOJI_RESULTS_HEIGHT).padding(3.dp).clip(RoundedCornerShape(12.dp))
                            .combinedClickable(onClick = { controller.feedback(view, null); controller.commitEmoji(results[i]) }),
                        contentAlignment = Alignment.Center,
                    ) { Text(results[i], fontSize = 27.sp) }
                }
            }
        }
    }
}

@Composable
private fun EmojiCell(e: String, hasTones: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong),
        contentAlignment = Alignment.Center,
    ) {
        Text(e, fontSize = 27.sp)
        if (hasTones) Box(
            Modifier.align(Alignment.BottomEnd).padding(5.dp).size(4.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

// ============================================================================ clipboard

@Composable
fun ClipboardPanel(controller: KeyboardController) {
    val kc = LocalKeyColors.current
    val cs = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val items = ClipboardStore.items
    val tick = controller.visibleTick
    val allowed = com.vis.expressivekeyboard.data.RecentImages.hasPermission(ctx)
    val images by androidx.compose.runtime.produceState(emptyList<com.vis.expressivekeyboard.data.RecentImages.Item>(), tick, allowed) {
        value = if (allowed) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.vis.expressivekeyboard.data.RecentImages.load(ctx, 12) } else emptyList()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Clipboard", color = kc.stripText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            if (items.any { !it.pinned }) TextButton(onClick = { ClipboardStore.clearUnpinned() }) { Text("Clear") }
            Switch(checked = Prefs.clipboardHistory.value, onCheckedChange = { Prefs.clipboardHistory.value = it }, modifier = Modifier.padding(start = 4.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty() && images.isEmpty() && allowed) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).clip(MaterialShapes.Clover4Leaf.toShape()).background(cs.secondaryContainer))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (Prefs.clipboardHistory.value) "Copied text and images will appear here" else "Clipboard history is off",
                        color = kc.stripText.copy(alpha = 0.7f),
                    )
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                    verticalItemSpacing = 6.dp,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items, key = { it.time.toString() + it.text.hashCode() + (it.imagePath ?: "") }) { item ->
                        Column(
                            Modifier.clip(RoundedCornerShape(18.dp))
                                .background(if (item.pinned) cs.tertiaryContainer else cs.surfaceContainerHighest)
                                .combinedClickable(onClick = { controller.pasteClip(item) })
                                .padding(start = if (item.isImage) 6.dp else 12.dp, top = if (item.isImage) 6.dp else 10.dp, end = 4.dp, bottom = 2.dp),
                        ) {
                            if (item.isImage) {
                                ClipThumb(item.imagePath!!, Modifier.fillMaxWidth().padding(end = 2.dp).height(96.dp).clip(RoundedCornerShape(14.dp)))
                            } else {
                                Text(
                                    item.text, maxLines = 4, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                                    color = if (item.pinned) cs.onTertiaryContainer else cs.onSurface,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton(onClick = { ClipboardStore.togglePin(item) }, modifier = Modifier.size(34.dp)) {
                                    Icon(if (item.pinned) Sym.PushPin else Sym.PushPinOutline, "Pin", modifier = Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                                }
                                IconButton(onClick = { ClipboardStore.remove(item) }, modifier = Modifier.size(34.dp)) {
                                    Icon(Sym.Close, "Delete", modifier = Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (!allowed) {
                        item(span = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan.FullLine) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.secondaryContainer)
                                    .combinedClickable(onClick = { controller.requestPhotosPermission() }).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Sym.Mood, null, tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Allow photo access to paste recent screenshots", color = cs.onSecondaryContainer, fontSize = 13.sp)
                            }
                        }
                    } else if (images.isNotEmpty()) {
                        item(span = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan.FullLine) {
                            Text("Recent photos & screenshots", color = cs.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, top = 4.dp))
                        }
                        items(images, key = { "media:" + it.uri }) { im ->
                            MediaThumb(
                                im.uri,
                                Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(18.dp))
                                    .combinedClickable(onClick = { controller.pasteMediaImage(im.uri, im.mime) }),
                            )
                        }
                    }
                }
            }
        }
        PanelBottomBar(controller)
    }
}

@Composable
fun MediaThumb(uri: android.net.Uri, modifier: Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, android.util.Size(288, 216), null).asImageBitmap()
                else android.provider.MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver, android.content.ContentUris.parseId(uri), android.provider.MediaStore.Images.Thumbnails.MINI_KIND, null)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val b = bmp
    if (b != null) androidx.compose.foundation.Image(b, "Photo", modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

/** Downsampled thumbnail of a clipboard image, decoded off the main thread. */
@Composable
fun ClipThumb(path: String, modifier: Modifier) {
    val bmp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, o)
                var sample = 1
                while (o.outWidth / (sample * 2) >= 360 && o.outHeight / (sample * 2) >= 240) sample *= 2
                android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    val b = bmp
    if (b != null) {
        androidx.compose.foundation.Image(b, "Clipboard image", modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

// ============================================================================ AI

@Composable
fun AiPanel(controller: KeyboardController) {
    val cs = MaterialTheme.colorScheme
    val kc = LocalKeyColors.current
    val st = controller.aiPanel
    val state = AiEngine.state.value
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(MaterialShapes.Sunny.toShape()).background(cs.primary), contentAlignment = Alignment.Center) {
                Icon(Sym.AutoAwesome, null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("AI writing tools", color = kc.stripText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    when (state) {
                        AiState.NO_MODEL -> if (AiEngine.supported) "No model installed" else "Needs a 64-bit device"
                        AiState.DOWNLOADING -> "Downloading model… ${(AiEngine.progress.floatValue * 100).toInt()}%"
                        AiState.IMPORTING -> "Importing model…"
                        AiState.LOADING -> "Loading ${AiEngine.modelDisplayName()}…"
                        AiState.ERROR -> AiEngine.message.value
                        else -> "${AiEngine.modelDisplayName()} · on-device"
                    },
                    color = kc.stripText.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { controller.openSettings() }) { Icon(Sym.Settings, "AI settings", tint = cs.onSurfaceVariant) }
        }
        Spacer(Modifier.height(6.dp))
        // scope selector (expressive connected toggle buttons)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            listOf("Current sentence", "Whole text").forEachIndexed { i, label ->
                ToggleButton(
                    checked = Prefs.aiScope.value == i,
                    onCheckedChange = { Prefs.aiScope.value = i },
                    modifier = Modifier.weight(1f),
                    shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                ) { Text(label, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AiTask.entries.forEach { t ->
                if (t == AiTask.FIX) {
                    Button(onClick = { controller.runAiTask(t) }, enabled = !st.busy) { Text(t.label) }
                } else {
                    FilledTonalButton(onClick = { controller.runAiTask(t) }, enabled = !st.busy) { Text(t.label) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHighest.copy(alpha = if (kc.borders) 1f else 0.6f))
                .padding(12.dp),
        ) {
            when {
                st.busy -> Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(40.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Thinking on-device…", color = cs.onSurfaceVariant)
                }
                st.result != null -> Column(Modifier.fillMaxSize()) {
                    Text(st.result, color = cs.onSurface, fontSize = 15.sp, modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { controller.applyAiPanel() }) { Text("Replace") }
                        OutlinedButton(onClick = { controller.insertAiPanel() }) { Text("Insert") }
                    }
                }
                st.error != null -> Text(st.error, color = cs.error, modifier = Modifier.align(Alignment.Center))
                else -> Text(
                    "Pick an action. It works on the selected text, otherwise on the " +
                        (if (Prefs.aiScope.value == 0) "sentence before the cursor." else "whole text field.") +
                        " Everything runs locally - nothing leaves your phone.",
                    color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        PanelBottomBar(controller)
    }
}

@Suppress("unused")
@OptIn(ExperimentalFoundationApi::class)
private fun unusedMarker() = Unit
