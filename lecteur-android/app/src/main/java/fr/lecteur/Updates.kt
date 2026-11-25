package fr.lecteur

import android.content.Context
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
}
