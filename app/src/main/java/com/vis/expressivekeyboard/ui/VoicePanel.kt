package com.vis.expressivekeyboard.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vis.expressivekeyboard.data.Prefs
import com.vis.expressivekeyboard.engine.Dictionary
import com.vis.expressivekeyboard.keyboard.KeyboardController
import com.vis.expressivekeyboard.keyboard.Panel
import com.vis.expressivekeyboard.voice.VoiceEngine
import com.vis.expressivekeyboard.voice.VoiceState

@Composable
fun VoicePanel(controller: KeyboardController) {
    val cs = MaterialTheme.colorScheme
    val kc = LocalKeyColors.current
    val st = VoiceEngine.state.value
    val listening = st == VoiceState.LISTENING
    val loading = st == VoiceState.LOADING
    val lvl by animateFloatAsState(VoiceEngine.level.floatValue, MaterialTheme.motionScheme.fastSpatialSpec(), label = "lvl")
    val inf = rememberInfiniteTransition(label = "v")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "rot")
    val model = VoiceEngine.activeModel()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            when {
                controller.voiceNeedsPermission && !VoiceEngine.hasPermission() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Sym.Mic, null, tint = cs.primary, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Voice typing needs microphone access", color = kc.stripText)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { controller.requestMicPermission() }) { Text("Allow microphone") }
                }
                model == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Sym.Mic, null, tint = cs.onSecondaryContainer)
                    }
                    Spacer(Modifier.height(10.dp))
                    if (st == VoiceState.DOWNLOADING) {
                        Text("Downloading voice model… ${(VoiceEngine.progress.floatValue * 100).toInt()}%", color = kc.stripText)
                    } else {
                        Text(
                            "Download a voice model to type with your voice. It runs fully offline.",
                            color = kc.stripText, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { controller.openSettingsTab(3) }) { Text("Get a voice model") }
                    }
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val status = when {
                        loading -> "Loading ${model.name}…"
                        listening -> "Listening…"
                        else -> "Tap to speak"
                    }
                    Text(
                        controller.voicePartialText.ifEmpty { status },
                        color = if (controller.voicePartialText.isEmpty()) kc.stripText.copy(alpha = 0.6f) else kc.stripText,
                        fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                    Box(
                        Modifier.size(132.dp).clip(CircleShape).combinedClickable(onClick = { controller.toggleVoice() }),
                        contentAlignment = Alignment.Center,
                    ) {
                        val scale = if (listening) 1f + lvl * 0.38f else 0.9f
                        Box(
                            Modifier.size(100.dp)
                                .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = if (listening) rot else rot / 3 }
                                .clip((if (listening) MaterialShapes.Cookie12Sided else MaterialShapes.Cookie9Sided).toShape())
                                .background(if (listening) cs.primary else cs.secondaryContainer),
                        )
                        if (loading) {
                            LoadingIndicator(Modifier.size(56.dp), color = cs.onSecondaryContainer)
                        } else {
                            Icon(
                                if (listening) Sym.Stop else Sym.Mic, if (listening) "Stop" else "Speak",
                                tint = if (listening) cs.onPrimary else cs.onSecondaryContainer, modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                    Text(
                        model.name + " · " + Dictionary.loadedCodes.value.joinToString(" · ") { it.uppercase() } + " · offline",
                        color = kc.stripText.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.fillMaxHeight().width(72.dp).clip(RoundedCornerShape(20.dp)).background(kc.func)
                    .combinedClickable(onClick = { controller.openPanel(Panel.KEYS) }),
                contentAlignment = Alignment.Center,
            ) { Text("ABC", color = kc.funcText, fontWeight = FontWeight.Bold) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                VoiceChip("Auto punctuation", Prefs.voiceAutoPunct.value) { Prefs.voiceAutoPunct.value = !Prefs.voiceAutoPunct.value }
                Spacer(Modifier.width(6.dp))
                VoiceChip("Spoken , . ?", Prefs.voiceSpokenPunct.value) { Prefs.voiceSpokenPunct.value = !Prefs.voiceSpokenPunct.value }
            }
            RepeatKey(controller, Modifier.fillMaxHeight().width(72.dp).clip(RoundedCornerShape(20.dp)).background(kc.func)) {
                Icon(Sym.Backspace, "Delete", tint = kc.funcText)
            }
        }
    }
}

@Composable
private fun VoiceChip(label: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (on) cs.secondaryContainer else Color.Transparent, label = "vc")
    val corner by animateDpAsState(if (on) 10.dp else 18.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "vcc")
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(corner)).background(bg)
            .border(1.dp, if (on) Color.Transparent else cs.outlineVariant, RoundedCornerShape(corner))
            .combinedClickable(onClick = onClick).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, color = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant, maxLines = 1)
    }
}
