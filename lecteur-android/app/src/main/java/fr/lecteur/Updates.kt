package fr.lecteur

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Vérifie si une version plus récente a été compilée sur GitHub. */
object Updates {
    private const val API = "https://api.github.com/repos/Yvanov40/public2/releases/tags/lecteur"

    class Update(val version: Int, val url: String)

    fun current(ctx: Context): Int = try {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)).toInt()
    } catch (_: Exception) {
        0
    }

    suspend fun check(ctx: Context): Update? = withContext(Dispatchers.IO) {
        val req = okhttp3.Request.Builder().url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Lecteur")
            .build()
        val body = DownloaderImpl.instance.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            r.body?.string() ?: ""
        }
        val assets = JSONObject(body).optJSONArray("assets") ?: return@withContext null
        var best: Update? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val n = Regex("^lecteur-v(\\d+)\\.apk$").find(a.optString("name"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (best == null || n > best.version) best = Update(n, a.optString("browser_download_url"))
        }
        best?.takeIf { it.version > current(ctx) }
    }

    private const val APK_MIME = "application/vnd.android.package-archive"

    /**
     * Télécharge la mise à jour avec le gestionnaire de téléchargements d'Android
     * (sans passer par le navigateur), puis ouvre directement l'installation.
     */
    fun downloadAndInstall(a: Activity, u: Update) {
        val dm = a.getSystemService(DownloadManager::class.java)
        val name = "lecteur-v${u.version}.apk"
        val req = DownloadManager.Request(Uri.parse(u.url))
            .setTitle("Lecteur v${u.version}")
            .setDescription("Mise à jour de Lecteur")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        val id = try {
            dm.enqueue(req)
        } catch (e: Exception) {
            Toast.makeText(a, "Téléchargement impossible : ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(a, "Téléchargement de la v${u.version}…", Toast.LENGTH_SHORT).show()
        val app = a.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try { app.unregisterReceiver(this) } catch (_: Exception) {}
                val uri = dm.getUriForDownloadedFile(id)
                if (uri == null) {
                    Toast.makeText(app, "Le téléchargement a échoué, réessaie", Toast.LENGTH_LONG).show()
                    return
                }
                install(app, uri)
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun install(ctx: Context, uri: Uri) {
        // Android demande une fois l'autorisation d'installer des applis depuis Lecteur
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(ctx, "Autorise Lecteur à installer des mises à jour, puis reviens : l'installation se lancera.", Toast.LENGTH_LONG).show()
            pending = uri
            ctx.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Mise à jour téléchargée en attente de l'autorisation d'installer. */
    private var pending: Uri? = null

    /** À appeler au retour dans l'appli : reprend l'installation si l'autorisation vient d'être donnée. */
    fun resumePending(ctx: Context) {
        val uri = pending ?: return
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) return
        pending = null
        install(ctx, uri)
    }
}
