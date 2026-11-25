package fr.lecteur

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** Fichiers présents dans Music/Lecteur et Movies/Lecteur (y compris ceux d'avant une réinstallation). */
object LocalMedia {
    class Item(val id: Long, val uri: Uri, val name: String, val video: Boolean, val path: String, val added: Long)

    fun scan(ctx: Context): List<Item> =
        (query(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false, "Music/Lecteur") +
            query(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, "Movies/Lecteur"))
            .sortedByDescending { it.added }

    @Suppress("DEPRECATION")
    private fun query(ctx: Context, coll: Uri, video: Boolean, rel: String): List<Item> {
        val q = Build.VERSION.SDK_INT >= 29
        val pathCol = if (q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED, pathCol
        )
        val sel = "$pathCol LIKE ?"
        val args = arrayOf(if (q) "$rel/%" else "%/$rel/%")
        val out = mutableListOf<Item>()
        try {
            ctx.contentResolver.query(coll, proj, sel, args, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val name = c.getString(iName) ?: continue
                    out += Item(id, ContentUris.withAppendedId(coll, id), name, video, "$rel/$name", c.getLong(iAdded))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }
}
