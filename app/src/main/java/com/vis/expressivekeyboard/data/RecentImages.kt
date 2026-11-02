package com.vis.expressivekeyboard.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/** Most recent screenshots and photos from the media store, offered in the clipboard panel. */
object RecentImages {
    data class Item(val uri: Uri, val mime: String, val name: String, val time: Long)

    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(ctx: Context) = ContextCompat.checkSelfPermission(ctx, permission()) == PackageManager.PERMISSION_GRANTED

    /** The newest image if it was added in the last few minutes (Gboard offers a fresh screenshot in the strip). */
    fun latest(ctx: Context, maxAgeMs: Long = 3 * 60_000L): Item? =
        load(ctx, 1).firstOrNull { System.currentTimeMillis() - it.time < maxAgeMs }

    fun load(ctx: Context, max: Int = 24): List<Item> {
        if (!hasPermission(ctx)) return emptyList()
        val out = ArrayList<Item>()
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED)
        try {
            val cursor = if (Build.VERSION.SDK_INT >= 30) {
                val args = android.os.Bundle().apply {
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
                    putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, max)
                }
                ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, args, null)
            } else {
                ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")
            }
            cursor?.use { c ->
                while (c.moveToNext() && out.size < max) {
                    val id = c.getLong(0)
                    out += Item(
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        c.getString(1) ?: "image/jpeg", c.getString(2) ?: "", c.getLong(3) * 1000,
                    )
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("RecentImages", "query failed", t)
        }
        return out
    }
}
