package com.vis.expressivekeyboard.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.vis.expressivekeyboard.BuildConfig
import com.vis.expressivekeyboard.R
import com.vis.expressivekeyboard.data.Prefs
import com.vis.expressivekeyboard.settings.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val page: String)

enum class UpdateState { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, ERROR }

/** Checks GitHub releases for a newer version and installs it with the system package installer. */
object Updater {
    private const val API = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
    private const val CHECK_INTERVAL = 24 * 60 * 60 * 1000L
    private const val CHANNEL = "updates"

    val state = mutableStateOf(UpdateState.IDLE)
    val latest = mutableStateOf<Release?>(null)
    val progress = mutableFloatStateOf(0f)
    val message = mutableStateOf("")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var ctx: Context

    fun init(c: Context) {
        ctx = c.applicationContext
        File(ctx.cacheDir, "update").deleteRecursively()
    }

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Background check, at most once a day. Posts a notification when an update is found. */
    fun checkIfDue() {
        if (!Prefs.autoUpdate.value) return
        val last = Prefs.raw().getLong("update_last_check", 0L)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL) return
        check(notify = true)
    }

    fun check(notify: Boolean = false) {
        if (state.value == UpdateState.CHECKING || state.value == UpdateState.DOWNLOADING || state.value == UpdateState.INSTALLING) return
        state.value = UpdateState.CHECKING
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
            Prefs.raw().edit().putLong("update_last_check", System.currentTimeMillis()).apply()
            r.onSuccess { rel ->
                latest.value = rel
                if (rel != null && isNewer(rel.version, currentVersion)) {
                    state.value = UpdateState.AVAILABLE
                    if (notify) notifyAvailable(rel)
                } else state.value = UpdateState.UP_TO_DATE
            }.onFailure {
                message.value = "Couldn't reach GitHub (${it.message ?: it.javaClass.simpleName})"
                state.value = UpdateState.ERROR
            }
        }
    }

    private fun fetchLatest(): Release? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ExpressiveKeyboard/$currentVersion")
        }
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets")
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".apk") }
            val asset = pickAsset(apks) ?: return null
            return Release(
                version = json.getString("tag_name").removePrefix("v"),
                notes = json.optString("body", ""),
                apkUrl = asset.getString("browser_download_url"),
                apkSize = asset.optLong("size", 0L),
                page = json.optString("html_url", ""),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** The APK built for this device's CPU (ExpressiveKeyboard-arm64-v8a.apk, ...), else the universal one. */
    private fun pickAsset(apks: List<JSONObject>): JSONObject? {
        for (abi in Build.SUPPORTED_ABIS) {
            apks.firstOrNull { it.getString("name").endsWith("-$abi.apk") }?.let { return it }
        }
        return apks.firstOrNull { it.getString("name") == "ExpressiveKeyboard.apk" } ?: apks.firstOrNull()
    }

    internal fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Downloads the release APK and hands it to the system installer. */
    fun install(activity: Context) {
        val rel = latest.value ?: return
        if (state.value == UpdateState.DOWNLOADING || state.value == UpdateState.INSTALLING) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            message.value = "Allow Expressive Keyboard to install apps, then tap Update again."
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        message.value = ""
        state.value = UpdateState.DOWNLOADING
        progress.floatValue = 0f
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val apk = download(rel)
                    commit(apk)
                }
            }
            result.onSuccess { state.value = UpdateState.INSTALLING }
                .onFailure { fail("Update failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    private fun download(rel: Release): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, "update.apk")
        var url = URL(rel.apkUrl)
        var conn: HttpURLConnection
        // GitHub redirects release downloads to its CDN
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("User-Agent", "ExpressiveKeyboard/$currentVersion")
            }
            val code = conn.responseCode
            if (code in 300..399) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code != 200) error("HTTP $code")
            break
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: rel.apkSize
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) { val p = done.toFloat() / total; scope.launch { progress.floatValue = p } }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    private fun commit(apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java).setPackage(ctx.packageName), flags)
            session.commit(pending.intentSender)
        }
    }

    internal fun fail(msg: String) {
        message.value = msg
        state.value = if (latest.value != null) UpdateState.AVAILABLE else UpdateState.ERROR
    }

    private fun notifyAvailable(rel: Release) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, SettingsActivity::class.java).putExtra("page", "about").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.sym_download)
            .setContentTitle("Expressive Keyboard ${rel.version} is available")
            .setContentText("Tap to update")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(1, n)
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.fail("Update cancelled.")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Updater.fail("This install was signed with a different key. Uninstall it and install the APK from GitHub once.")
            else -> Updater.fail("Update failed (${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status})")
        }
    }
}
