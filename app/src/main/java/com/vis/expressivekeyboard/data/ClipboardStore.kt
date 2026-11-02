package com.vis.expressivekeyboard.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A clipboard entry: either text, or an image copied into app storage ([imagePath] + [mime]). */
data class ClipItem(
    val text: String,
    val time: Long,
    val pinned: Boolean = false,
    val imagePath: String? = null,
    val mime: String? = null,
) {
    val isImage get() = imagePath != null
}

object ClipboardStore {
    private const val MAX = 40
    private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    private lateinit var ctx: Context
    val items = mutableStateListOf<ClipItem>()
    /** Most recent clip that hasn't been pasted yet (for the suggestion strip chip). */
    val freshClip = mutableStateOf<ClipItem?>(null)
    /** Set when we put something on the system clipboard ourselves, so the listener doesn't re-add it. */
    @Volatile var suppressNext = false

    fun clipsDir(): File = File(ctx.filesDir, "clips").apply { mkdirs() }

    fun init(c: Context) {
        ctx = c.applicationContext
        val raw = ctx.getSharedPreferences("clipboard", Context.MODE_PRIVATE).getString("items", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val path = o.optString("img").ifEmpty { null }
                if (path != null && !File(path).exists()) continue
                items += ClipItem(o.optString("t"), o.getLong("ts"), o.optBoolean("p"), path, o.optString("m").ifEmpty { null })
            }
        }
    }

    fun add(text: String) {
        if (text.isBlank() || !Prefs.clipboardHistory.value) return
        val existing = items.indexOfFirst { !it.isImage && it.text == text }
        val pinned = existing >= 0 && items[existing].pinned
        if (existing >= 0) items.removeAt(existing)
        insert(ClipItem(text, System.currentTimeMillis(), pinned))
    }

    /** Copies an image from [uri] into private storage. Must be called off the main thread. */
    fun importImage(uri: Uri, mime: String): ClipItem? {
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("gif") -> "gif"
            mime.contains("webp") -> "webp"
            else -> "jpg"
        }
        val dest = File(clipsDir(), "clip_${System.currentTimeMillis()}.$ext")
        return runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val r = input.read(buf); if (r < 0) break
                        total += r
                        if (total > MAX_IMAGE_BYTES) throw IllegalStateException("too big")
                        out.write(buf, 0, r)
                    }
                }
            }
            // skip exact duplicates of the newest image
            val prev = items.firstOrNull { it.isImage }
            if (prev != null && File(prev.imagePath!!).length() == dest.length() &&
                File(prev.imagePath).readBytes().contentEquals(dest.readBytes())
            ) { dest.delete(); return prev }
            ClipItem("", System.currentTimeMillis(), false, dest.absolutePath, mime)
        }.onFailure { dest.delete() }.getOrNull()
    }

    fun addImageItem(item: ClipItem) {
        if (!Prefs.clipboardHistory.value) { File(item.imagePath ?: return).delete(); return }
        if (items.firstOrNull() == item) { freshClip.value = item; return }
        insert(item)
    }

    private fun insert(item: ClipItem) {
        items.add(0, item)
        freshClip.value = item
        while (items.size > MAX) {
            val idx = items.indexOfLast { !it.pinned }
            if (idx < 0) break
            deleteFile(items.removeAt(idx))
        }
        save()
    }

    private fun deleteFile(item: ClipItem) { item.imagePath?.let { File(it).delete() } }

    fun togglePin(item: ClipItem) {
        val i = items.indexOf(item)
        if (i >= 0) items[i] = item.copy(pinned = !item.pinned)
        save()
    }

    fun remove(item: ClipItem) {
        items.remove(item); deleteFile(item)
        if (freshClip.value == item) freshClip.value = null
        save()
    }

    fun clearUnpinned() {
        items.filter { !it.pinned }.forEach { deleteFile(it) }
        items.removeAll { !it.pinned }
        freshClip.value = null
        save()
    }

    private fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject().put("t", it.text).put("ts", it.time).put("p", it.pinned)
                    .put("img", it.imagePath ?: "").put("m", it.mime ?: ""),
            )
        }
        ctx.getSharedPreferences("clipboard", Context.MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
    }
}
