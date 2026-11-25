package fr.lecteur

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/** Téléchargement d'un fichier audio direct (épisode de podcast) vers Music/Lecteur. */
object DirectDownload {
    class Cancelled : Exception()

    fun safeName(name: String) =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifEmpty { "fichier" }

    fun run(
        ctx: Context, url: String, title: String, artist: String?, thumb: String?, work: File,
        cancelled: () -> Boolean, progress: (Int) -> Unit
    ) {
        work.mkdirs()
        val f = File(work, "episode")
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", DownloaderImpl.USER_AGENT).build()
        DownloaderImpl.instance.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("réponse vide")
            val total = body.contentLength()
            body.byteStream().use { input ->
                f.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != last) { last = pct; progress(pct) }
                        }
                    }
                }
            }
        }
        val path = Uri.parse(url).lastPathSegment.orEmpty()
        val ext = path.substringAfterLast('.', "mp3").lowercase().takeIf { it.length in 2..4 } ?: "mp3"
        val mime = when (ext) { "m4a", "mp4", "aac" -> "audio/mp4"; "ogg", "oga", "opus" -> "audio/ogg"; else -> "audio/mpeg" }
        val base = safeName(title)
        val uri = Saver.save(ctx, f, "$base.$ext", mime, false, title)
        f.delete()
        Store.addDownload(Store.Download(uri.toString(), title, artist, thumb, false, url, "Music/Lecteur/$base.$ext"))
    }
}
