package fr.telecommande

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Mises à jour : la dernière version compilée sur GitHub (release « telecommande »). */
object Updates {
    private const val API = "https://api.github.com/repos/Yvanov40/public2/releases/tags/telecommande"
    private const val APK_MIME = "application/vnd.android.package-archive"

    class Update(val version: Int, val url: String)

    private val main = Handler(Looper.getMainLooper())

    fun current(ctx: Context): Int = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
    } catch (_: Exception) { 0 }

    /** Interroge GitHub en arrière-plan ; [done] reçoit la mise à jour (ou null), ou une erreur. */
    fun check(ctx: Context, done: (Update?, String?) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "Telecommande")
                val code = c.responseCode
                if (code != 200) throw java.io.IOException("HTTP $code")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val assets = JSONObject(body).optJSONArray("assets")
                var best: Update? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val n = Regex("^telecommande-v(\\d+)\\.apk$").find(a.optString("name"))
                        ?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if (best == null || n > best.version) best = Update(n, a.optString("browser_download_url"))
                }
                val newer = best?.takeIf { it.version > current(app) }
                main.post { done(newer, null) }
            } catch (e: Exception) {
                main.post { done(null, e.message ?: e.javaClass.simpleName) }
            }
        }.start()
    }

    /** Télécharge avec le gestionnaire de téléchargements d'Android puis lance l'installation. */
    fun downloadAndInstall(a: Activity, u: Update) {
        val dm = a.getSystemService(DownloadManager::class.java) ?: return
        val req = DownloadManager.Request(Uri.parse(u.url))
            .setTitle("Télécommande v${u.version}")
            .setDescription("Mise à jour de Télécommande")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "telecommande-v${u.version}.apk")
        val id = try { dm.enqueue(req) } catch (e: Exception) {
            Toast.makeText(a, "Téléchargement impossible : ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(a, "Téléchargement de la v${u.version}…", Toast.LENGTH_SHORT).show()
        val app = a.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try { app.unregisterReceiver(this) } catch (_: Exception) {}
                val uri = dm.getUriForDownloadedFile(id)
                if (uri == null) {
                    Toast.makeText(app, "Le téléchargement a échoué, réessayez", Toast.LENGTH_LONG).show()
                    return
                }
                install(app, uri)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter)
    }

    private fun install(ctx: Context, uri: Uri) {
        // Android demande une seule fois l'autorisation d'installer des applis depuis Télécommande
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            pending = uri
            Toast.makeText(ctx, "Autorisez Télécommande à installer des mises à jour, puis revenez : l'installation se lancera.", Toast.LENGTH_LONG).show()
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private var pending: Uri? = null

    /** Au retour dans l'appli : reprend l'installation si l'autorisation vient d'être donnée. */
    fun resumePending(ctx: Context) {
        val uri = pending ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) return
        pending = null
        install(ctx, uri)
    }
}
