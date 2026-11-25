package fr.lecteur

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/** Toutes les 6 h : nouveaux épisodes / vidéos des abonnements, notification, téléchargement auto. */
class NewContentWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!Settings.checkNew) return Result.success()
        val ctx = applicationContext
        val newEpisodes = mutableListOf<Episode>()
        for (p in Store.podcasts()) {
            try {
                val eps = Pod.feed(p.feedUrl).second
                val newest = eps.maxOfOrNull { it.date } ?: continue
                val seen = Store.lastSeen(p.feedUrl)
                if (seen > 0) newEpisodes += eps.filter { it.date > seen }
                if (newest > seen) Store.setLastSeen(p.feedUrl, newest)
            } catch (_: Exception) {
            }
        }
        val newVideos = mutableListOf<Vid>()
        for (c in Store.subs()) {
            try {
                val vids = Yt.latest(c.url).filter { it.date > 0 }
                val newest = vids.maxOfOrNull { it.date } ?: continue
                val seen = Store.lastSeen(c.url)
                if (seen > 0) newVideos += vids.filter { it.date > seen }
                if (newest > seen) Store.setLastSeen(c.url, newest)
            } catch (_: Exception) {
            }
        }
        if (newEpisodes.isNotEmpty() || newVideos.isNotEmpty()) notify(ctx, newEpisodes, newVideos)

        // Téléchargement automatique des nouveaux épisodes, seulement en Wi-Fi
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (Settings.autoDownload && newEpisodes.isNotEmpty() && cm?.isActiveNetworkMetered == false) {
            try {
                setForeground(foreground(ctx, "Téléchargement des nouveaux épisodes"))
            } catch (_: Exception) {
            }
            val work = File(ctx.cacheDir, "auto")
            newEpisodes.sortedByDescending { it.date }.take(5).forEach { e ->
                try {
                    DirectDownload.run(ctx, e.audioUrl, e.title, e.podcastTitle, e.image ?: e.podcastArt, work, { isStopped }) { }
                } catch (_: Exception) {
                }
            }
            work.deleteRecursively()
        }
        return Result.success()
    }

    private fun channel(ctx: Context): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nouveautés", NotificationManager.IMPORTANCE_DEFAULT))
        return CHANNEL
    }

    private fun foreground(ctx: Context, text: String): ForegroundInfo {
        val n = NotificationCompat.Builder(ctx, channel(ctx))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(text)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(43, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(43, n)
    }

    private fun notify(ctx: Context, eps: List<Episode>, vids: List<Vid>) {
        val parts = listOfNotNull(
            eps.size.takeIf { it > 0 }?.let { "$it épisode" + if (it > 1) "s" else "" },
            vids.size.takeIf { it > 0 }?.let { "$it vidéo" + if (it > 1) "s" else "" }
        )
        val lines = (eps.map { "${it.podcastTitle} : ${it.title}" } + vids.map { "${it.uploader ?: ""} : ${it.title}" }).take(6)
        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).putExtra("page", 1),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(it) }
        val n = NotificationCompat.Builder(ctx, channel(ctx))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Nouveautés : " + parts.joinToString(", "))
            .setContentText(lines.firstOrNull())
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(44, n)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CHANNEL = "nouveautes"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<NewContentWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("nouveautes", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
