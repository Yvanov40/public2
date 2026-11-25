package fr.lecteur

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Range les téléchargements dans Musique/Lecteur et Films/Lecteur (visibles des autres applis). */
object Saver {
    fun save(ctx: Context, src: File, fileName: String, mime: String, video: Boolean, title: String): Uri {
        val folder = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Lecteur")
                put(MediaStore.MediaColumns.TITLE, title)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(collection, values) ?: throw IOException("enregistrement refusé")
            try {
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("écriture impossible")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(folder), "Lecteur")
            dir.mkdirs()
            val dest = File(dir, fileName)
            src.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf(mime), null)
            return Uri.fromFile(dest)
        }
    }

    fun delete(ctx: Context, uri: Uri) {
        try {
            if (uri.scheme == "file") uri.path?.let { File(it).delete() }
            else ctx.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }
}
