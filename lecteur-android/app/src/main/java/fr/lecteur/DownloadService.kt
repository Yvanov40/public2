package fr.lecteur

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Téléchargements l'un après l'autre, avec progression dans une notification. */
class DownloadService : Service() {

    private class Cancelled : Exception()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Downloads.Item>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    @Volatile private var lastStartId = 0
    private lateinit var nm: NotificationManager
    private var current: Notification? = null
    private var lastPct = Int.MIN_VALUE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW)
        )
        scope.launch {
            for (t in queue) {
                process(t)
                if (pending.decrementAndGet() == 0) {
                    withContext(Dispatchers.Main) {
                        if (pending.get() == 0) {
                            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelfResult(lastStartId)
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = current ?: build("Téléchargement", "Préparation…", -1)
        ServiceCompat.startForeground(
            this, NOTIF_ID, notif,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        lastStartId = startId
        val item = intent?.getIntExtra(EXTRA_ID, -1)?.let { Downloads.get(it) }
        if (item != null) {
            pending.incrementAndGet()
            queue.trySend(item)
        } else if (pending.get() == 0) {
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun process(t: Downloads.Item) {
        if (t.cancel) return
        t.state = Downloads.State.RUNNING
        var title = t.title
        val work = File(cacheDir, "telechargement").apply { deleteRecursively(); mkdirs() }
        try {
            update(t, "Analyse…", -1)
            val info = StreamInfo.getInfo(t.url)
            title = info.name
            t.title = info.name
            val base = safeName(info.name)
            val uri: Uri
            val path: String
            if (!t.video) {
                val a = Sources.pickAudio(info.audioStreams) ?: error("aucun flux audio")
                val m4a = a.format == MediaFormat.M4A
                val f = File(work, "audio")
                fetch(t, a, f) { p -> update(t, "Audio", (p * 100).toInt()) }
                val ext = if (m4a) "m4a" else (a.format?.suffix ?: "webm")
                val mime = if (m4a) "audio/mp4" else (a.format?.mimeType ?: "audio/webm")
                if (t.cancel) throw Cancelled()
                uri = Saver.save(this, f, "$base.$ext", mime, false, info.name)
                path = "Music/Lecteur/$base.$ext"
            } else {
                val v = Sources.pickVideoForDownload(info.videoOnlyStreams)
                val a = Sources.pickAudio(info.audioStreams)?.takeIf { it.format == MediaFormat.M4A }
                val out = File(work, "sortie.mp4")
                if (v != null && a != null) {
                    val vf = File(work, "video")
                    val af = File(work, "audio")
                    fetch(t, v, vf) { p -> update(t, "Vidéo", (p * 85).toInt()) }
                    fetch(t, a, af) { p -> update(t, "Audio", 85 + (p * 12).toInt()) }
                    update(t, "Assemblage…", 98)
                    Muxer.mux(vf, af, out)
                } else {
                    val m = info.videoStreams
                        .filter { it.isUrl && it.format == MediaFormat.MPEG_4 }
                        .maxByOrNull { it.height } ?: error("aucun flux vidéo téléchargeable")
                    fetch(t, m, out) { p -> update(t, "Vidéo", (p * 100).toInt()) }
                }
                if (t.cancel) throw Cancelled()
                uri = Saver.save(this, out, "$base.mp4", "video/mp4", true, info.name)
                path = "Movies/Lecteur/$base.mp4"
            }
            Store.addDownload(
                Store.Download(
                    uri.toString(), info.name, info.uploaderName,
                    Sources.bestThumb(info.thumbnails), t.video, info.url, path
                )
            )
            Downloads.remove(t)
            notifyEnd("Téléchargé", title)
        } catch (e: Cancelled) {
            t.state = Downloads.State.CANCELLED
            t.stage = "Annulé"
            Downloads.changed()
        } catch (e: Exception) {
            Log.e(TAG, "Téléchargement", e)
            t.state = Downloads.State.FAILED
            t.stage = "Échec : ${e.message ?: e.javaClass.simpleName}"
            Downloads.changed()
            notifyEnd("Échec du téléchargement", "$title : ${e.message}")
        } finally {
            work.deleteRecursively()
        }
    }

    /** Téléchargement par morceaux (YouTube bride les gros téléchargements d'un seul tenant). */
    private fun fetch(t: Downloads.Item, s: Stream, dest: File, progress: (Float) -> Unit) {
        val url = s.content
        val total = s.itagItem?.contentLength?.takeIf { it > 0 }
            ?: Uri.parse(url).getQueryParameter("clen")?.toLongOrNull() ?: -1L
        dest.outputStream().buffered().use { out ->
            if (total <= 0) {
                retry { call(url, null, 0).use { r -> r.body?.byteStream()?.copyTo(out) } }
                progress(1f)
                return
            }
            var pos = 0L
            var rn = 0
            while (pos < total) {
                if (t.cancel) throw Cancelled()
                val end = minOf(pos + CHUNK, total) - 1
                val bytes = retry { call(url, pos to end, ++rn).use { r -> r.body?.bytes() ?: ByteArray(0) } }
                if (bytes.isEmpty()) throw IOException("réponse vide")
                out.write(bytes)
                pos += bytes.size
                progress(pos.toFloat() / total)
            }
        }
    }

    private fun call(url: String, range: Pair<Long, Long>?, rn: Int): okhttp3.Response {
        val uri = Uri.parse(url)
        val gv = YtDataSource.isGoogleVideo(uri)
        val full = if (range != null && gv) uri.buildUpon()
            .appendQueryParameter("range", "${range.first}-${range.second}")
            .appendQueryParameter("rn", rn.toString())
            .build().toString() else url
        val b = okhttp3.Request.Builder().url(full)
        YtDataSource.headersFor(uri).forEach { (k, v) -> b.header(k, v) }
        if (range != null && !gv) b.header("Range", "bytes=${range.first}-${range.second}")
        if (range != null && gv && YtDataSource.isWebClient(uri)) b.post(byteArrayOf(0x78, 0).toRequestBody(null))
        val r = DownloaderImpl.instance.client.newCall(b.build()).execute()
        if (!r.isSuccessful) {
            r.close()
            throw IOException("HTTP ${r.code}")
        }
        return r
    }

    private inline fun <T> retry(block: () -> T): T {
        var n = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (++n >= 4) throw e
                Thread.sleep(1500L * n)
            }
        }
    }

    // ——— Notifications ———

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun build(title: String, text: String, pct: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (pct >= 0) "$text · $pct %" else text)
            .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun update(t: Downloads.Item, text: String, pct: Int) {
        if (pct == lastPct && pct >= 0) return
        lastPct = pct
        t.stage = text
        t.pct = pct
        Downloads.changed()
        val waiting = Downloads.active() - 1
        val n = build(
            t.title + if (waiting > 0) " (+$waiting en attente)" else "",
            text, pct
        )
        current = n
        try { nm.notify(NOTIF_ID, n) } catch (_: Exception) {}
    }

    private fun notifyEnd(title: String, text: String) {
        lastPct = Int.MIN_VALUE
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        try { nm.notify((System.currentTimeMillis() % 100000).toInt() + 100, n) } catch (_: Exception) {}
    }

    private fun safeName(name: String) =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifEmpty { "video" }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 42
        private const val CHUNK = 5L * 1024 * 1024
        private const val EXTRA_ID = "id"

        fun start(ctx: Context, url: String, video: Boolean, title: String?) {
            val item = Downloads.add(url, video, title)
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, DownloadService::class.java).putExtra(EXTRA_ID, item.id)
            )
        }
    }
}
