package com.vis.expressivekeyboard.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vis.expressivekeyboard.data.Prefs
import com.vis.expressivekeyboard.ui.Sym
import com.vis.expressivekeyboard.update.UpdateState
import com.vis.expressivekeyboard.update.Updater

/** Banner on the home tab when a newer release is on GitHub. */
@Composable
internal fun UpdateCard() {
    val cs = MaterialTheme.colorScheme
    val rel = Updater.latest.value ?: return
    Surface(shape = RoundedCornerShape(32.dp), color = cs.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(cs.secondary), contentAlignment = Alignment.Center) {
                    Icon(Sym.Download, null, tint = cs.onSecondary)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Update available", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Version ${rel.version} · you have ${Updater.currentVersion}",
                        fontSize = 14.sp, color = cs.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            UpdateAction()
        }
    }
}

@Composable
private fun UpdateAction() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val rel = Updater.latest.value
    when (Updater.state.value) {
        UpdateState.DOWNLOADING -> {
            Text("Downloading… ${(Updater.progress.floatValue * 100).toInt()}%", fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            LinearWavyProgressIndicator(progress = { Updater.progress.floatValue }, modifier = Modifier.fillMaxWidth())
        }
        UpdateState.INSTALLING -> Text("Confirm the install in the system dialog.", fontSize = 14.sp)
        else -> if (rel != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Updater.install(ctx) }, modifier = Modifier.weight(1f).height(52.dp)) {
                Text("Update now" + if (rel.apkSize > 0) " (${fmtSize(rel.apkSize)})" else "", fontSize = 15.sp)
            }
            if (rel.page.isNotEmpty()) OutlinedButton(
                onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                modifier = Modifier.height(52.dp),
            ) { Text("What's new") }
        }
    }
    if (Updater.message.value.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(Updater.message.value, fontSize = 13.sp, color = cs.error)
    }
}

@Composable
internal fun UpdateGroup() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val state = Updater.state.value
    Group {
        item {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (state) {
                                UpdateState.AVAILABLE, UpdateState.DOWNLOADING, UpdateState.INSTALLING ->
                                    "Version ${Updater.latest.value?.version} is available"
                                UpdateState.UP_TO_DATE -> "You're up to date"
                                UpdateState.CHECKING -> "Checking for updates…"
                                UpdateState.ERROR -> "Couldn't check for updates"
                                UpdateState.IDLE -> "Updates"
                            },
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text("Installed: ${Updater.currentVersion}", fontSize = 13.sp, color = cs.onSurfaceVariant)
                    }
                    if (state == UpdateState.CHECKING) LoadingIndicator(Modifier.size(36.dp))
                    else if (state != UpdateState.DOWNLOADING && state != UpdateState.INSTALLING)
                        TextButton(onClick = { Updater.check() }) { Text("Check now") }
                }
                if (state == UpdateState.AVAILABLE || state == UpdateState.DOWNLOADING || state == UpdateState.INSTALLING) {
                    Spacer(Modifier.height(14.dp))
                    UpdateAction()
                } else if (state == UpdateState.ERROR && Updater.message.value.isNotEmpty()) {
                    Text(Updater.message.value, fontSize = 13.sp, color = cs.error)
                }
            }
        }
        item {
            SwitchRow(
                "Check for updates automatically",
                "Once a day, from GitHub releases. You get a notification when a new version is out.",
                Prefs.autoUpdate,
            )
        }
        if (Build.VERSION.SDK_INT >= 33 && Prefs.autoUpdate.value &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Allow notifications to hear about updates", fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
            }
        }
    }
}
