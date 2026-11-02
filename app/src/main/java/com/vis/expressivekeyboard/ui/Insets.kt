package com.vis.expressivekeyboard.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets as ComposeInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Bottom space the keyboard must leave for the navigation bar. On Android 13+ with gesture
 * navigation the IME window also hosts its own "hide keyboard / switch keyboard" button bar,
 * which is taller than the gesture-handle inset, so we reserve the larger of the two.
 */
fun navBarReservePx(ctx: Context): Int {
    val res = ctx.resources
    fun dimen(name: String): Int {
        val id = res.getIdentifier(name, "dimen", "android")
        return if (id != 0) res.getDimensionPixelSize(id) else 0
    }
    var px = 0
    if (Build.VERSION.SDK_INT >= 30) {
        runCatching {
            val wm = ctx.getSystemService(WindowManager::class.java)
            px = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
        }
    } else {
        px = dimen("navigation_bar_height")
    }
    val gesture = Settings.Secure.getInt(ctx.contentResolver, "navigation_mode", 0) == 2
    if (gesture && Build.VERSION.SDK_INT >= 33) px = max(px, dimen("navigation_bar_frame_height"))
    return px
}

/** Pads the keyboard above the nav bar, but only when the IME window actually extends under it. */
fun Modifier.imeBottomPadding(): Modifier = composed {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val composeInset = ComposeInsets.navigationBars.getBottom(density)
    val reserve = remember(ctx.resources.configuration.orientation) { navBarReservePx(ctx) }
    var reachesBottom by remember { mutableStateOf(true) }
    var screenH by remember { mutableIntStateOf(0) }
    val factor = com.vis.expressivekeyboard.data.Prefs.bottomOffset.value
    val pad = if (reachesBottom) max((reserve * factor).toInt(), composeInset) else composeInset
    this
        .onGloballyPositioned { c ->
            if (screenH == 0) {
                screenH = if (Build.VERSION.SDK_INT >= 30) {
                    ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()
                } else ctx.resources.displayMetrics.heightPixels
            }
            val bottom = c.positionOnScreen().y + c.size.height
            reachesBottom = bottom >= screenH - 2
        }
        .padding(bottom = with(density) { pad.toDp() })
}
