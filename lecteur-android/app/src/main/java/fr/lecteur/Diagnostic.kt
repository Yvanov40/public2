package fr.lecteur

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException

/** Teste chaque maillon de la lecture YouTube et dit ce qui marche ou coince. */
object Diagnostic {
    private const val VIDEO = "jNQXAC9IVRw" // « Me at the zoo », la toute première vidéo de YouTube

    private suspend fun step(out: (String) -> Unit, name: String, block: suspend () -> String) {
        val t0 = System.currentTimeMillis()
        val line = try {
            val r = withContext(Dispatchers.IO) { block() }
            "✅ $name : $r"
        } catch (e: Exception) {
            "❌ $name : ${e.message ?: e.javaClass.simpleName}"
        }
        out("$line  [${System.currentTimeMillis() - t0} ms]")
    }

    suspend fun run(ctx: Context, out: (String) -> Unit) {
        out("Lecteur v${Updates.current(ctx)} · NewPipeExtractor ${BuildConfig.NPE_VERSION} · Android ${Build.VERSION.RELEASE}")
        out("Relais : ${if (Settings.fallback) "activés" else "désactivés"} · SponsorBlock : ${if (Settings.sponsorBlock) "activé" else "désactivé"}")
        out("")

        step(out, "Accès à YouTube") {
            val req = okhttp3.Request.Builder().url("https://www.youtube.com/generate_204")
                .header("User-Agent", DownloaderImpl.USER_AGENT).build()
            DownloaderImpl.instance.client.newCall(req).execute().use { "HTTP ${it.code}" }
        }

        step(out, "Recherche (NewPipe)") {
            val ex = ServiceList.YouTube.getSearchExtractor("musique", listOf("videos"), "")
            ex.fetchPage()
            "${ex.initialPage.items.size} résultats"
        }

        step(out, "YouTube Music (NewPipe)") {
            val ex = ServiceList.YouTube.getSearchExtractor("daft punk", listOf("music_songs"), "")
            ex.fetchPage()
            "${ex.initialPage.items.size} titres"
        }


        var audioUrl: String? = null
        step(out, "Infos d'une vidéo (NewPipe)") {
            val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$VIDEO")
            val a = Sources.pickAudio(info.audioStreams)
            audioUrl = a?.content
            val client = a?.content?.let { Uri.parse(it).getQueryParameter("c") } ?: "?"
            val pot = if (a?.content?.contains("pot=") == true) ", avec jeton" else ""
            "${info.audioStreams.size} flux audio, ${info.videoOnlyStreams.size} flux vidéo (client $client$pot)"
        }

        step(out, "Téléchargement direct depuis YouTube") {
            val url = audioUrl ?: throw IOException("pas d'adresse (étape précédente en échec)")
            val uri = Uri.parse(url)
            val ranged = uri.buildUpon().appendQueryParameter("range", "0-65535").appendQueryParameter("rn", "1").build().toString()
            val b = okhttp3.Request.Builder().url(ranged)
            YtDataSource.headersFor(uri).forEach { (k, v) -> b.header(k, v) }
            if (YtDataSource.isWebClient(uri)) b.post(byteArrayOf(0x78, 0).toRequestBody(null))
            DownloaderImpl.instance.client.newCall(b.build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code} (YouTube refuse le fichier)")
                "OK (${(r.body?.bytes()?.size ?: 0) / 1024} ko reçus)"
            }
        }

        // Le titre qui a échoué récemment, testé en détail
        Playback.lastFailed?.let { (url, title) ->
            val id = SponsorBlock.videoId(url)
            out("")
            out("Titre en échec : $title")
            step(out, "  Infos (NewPipe)") {
                try {
                    val info = StreamInfo.getInfo(url)
                    val a = Sources.pickAudio(info.audioStreams)
                    "${info.audioStreams.size} flux audio (client ${a?.content?.let { Uri.parse(it).getQueryParameter("c") } ?: "?"})"
                } catch (e: Exception) {
                    // La raison donnée par YouTube (pays, âge, Premium, supprimé…)
                    throw IOException("${e.javaClass.simpleName} : ${e.message}")
                }
            }
            step(out, "  Fichier direct") {
                val info = StreamInfo.getInfo(url)
                val a = Sources.pickAudio(info.audioStreams)?.content ?: throw IOException("aucun flux audio")
                val uri = Uri.parse(a)
                val ranged = uri.buildUpon().appendQueryParameter("range", "0-65535").appendQueryParameter("rn", "1").build().toString()
                val b = okhttp3.Request.Builder().url(ranged)
                YtDataSource.headersFor(uri).forEach { (k, v) -> b.header(k, v) }
                if (YtDataSource.isWebClient(uri)) b.post(byteArrayOf(0x78, 0).toRequestBody(null))
                DownloaderImpl.instance.client.newCall(b.build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    "OK"
                }
            }
            if (id != null) {
                val rel = withContext(Dispatchers.IO) { runCatching { Fallback.relays(6, includeBenched = true) }.getOrDefault(emptyList()) }
                for (r in rel) step(out, "  ${r.label}") { Fallback.test(r, id) }
            }
        }

        out("")
        out("Serveurs relais :")
        val relays = withContext(Dispatchers.IO) { runCatching { Fallback.relays(12, includeBenched = true) }.getOrDefault(emptyList()) }
        if (relays.isEmpty()) out("❌ liste des relais introuvable")
        for (r in relays) step(out, r.label) { Fallback.test(r, VIDEO) }
        out("")
        out("Terminé.")
    }
}
