package fr.lecteur

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Locale

object Yt {
    class ChannelData(val chan: Chan, val subs: String?, val videos: List<Vid>)

    /** filter : "all" (vidéos + chaînes) ou "videos". */
    suspend fun search(query: String, filter: String = "all"): List<InfoItem> = withContext(Dispatchers.IO) {
        try {
            val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
            ex.fetchPage()
            ex.initialPage.items
        } catch (e: Exception) {
            // YouTube refuse : recherche via un serveur relais
            if (!Settings.fallback) throw e
            try {
                Fallback.search(query)
            } catch (_: Exception) {
                throw e
            }
        }
    }

    suspend fun info(url: String): StreamInfo = withContext(Dispatchers.IO) { StreamInfo.getInfo(url) }

    suspend fun channel(url: String): ChannelData = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(url)
        val chan = Chan(info.url, info.name, Sources.bestThumb(info.avatars, 88))
        ChannelData(chan, subs(info.subscriberCount), channelVideos(info).ifEmpty { feed(url) })
    }

    /** Dernières vidéos d'une chaîne (flux RSS léger, sinon l'onglet Vidéos). */
    suspend fun latest(url: String): List<Vid> = withContext(Dispatchers.IO) {
        feed(url).ifEmpty { channelVideos(ChannelInfo.getInfo(url)) }
    }

    private fun feed(url: String): List<Vid> = try {
        val ex = ServiceList.YouTube.getFeedExtractor(url)
        if (ex == null) emptyList() else {
            ex.fetchPage()
            ex.initialPage.items.map(::vid)
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun channelVideos(info: ChannelInfo): List<Vid> = try {
        val tab = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
        if (tab == null) emptyList()
        else ChannelTabInfo.getInfo(ServiceList.YouTube, tab).relatedItems
            .filterIsInstance<StreamInfoItem>().map(::vid)
    } catch (_: Exception) {
        emptyList()
    }

    fun vid(it: StreamInfoItem): Vid {
        val when_ = runCatching { it.uploadDate?.offsetDateTime() }.getOrNull()
        val date = when_?.let(::ago) ?: it.textualUploadDate?.takeIf { s -> !s.contains('T') }
        val meta = listOfNotNull(it.uploaderName?.ifEmpty { null }, views(it.viewCount), date?.ifEmpty { null })
            .joinToString(" · ")
        return Vid(
            it.url, it.name, it.uploaderName, it.uploaderUrl,
            Sources.bestThumb(it.thumbnails, 300), it.duration, meta,
            when_?.toInstant()?.toEpochMilli() ?: 0
        )
    }

    fun looksLikeUrl(s: String) = s.startsWith("http://") || s.startsWith("https://") ||
        s.contains("youtu.be/") || s.contains("youtube.com/")

    fun formatDuration(sec: Long): String {
        if (sec <= 0) return ""
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun short(d: Double): String {
        val s = if (d >= 100) String.format(Locale.FRANCE, "%.0f", d) else String.format(Locale.FRANCE, "%.1f", d)
        return s.removeSuffix(",0")
    }

    fun count(n: Long): String? = when {
        n < 0 -> null
        n >= 1_000_000_000 -> short(n / 1e9) + " Md"
        n >= 1_000_000 -> short(n / 1e6) + " M"
        n >= 1_000 -> short(n / 1e3) + " k"
        else -> n.toString()
    }

    fun views(n: Long) = count(n)?.let { "$it vues" }

    fun subs(n: Long) = count(n)?.let { "$it abonnés" }

    fun ago(t: OffsetDateTime): String {
        val s = Duration.between(t, OffsetDateTime.now()).seconds.coerceAtLeast(0)
        return when {
            s < 3600 -> "il y a ${(s / 60).coerceAtLeast(1)} min"
            s < 86_400 -> "il y a ${s / 3600} h"
            s < 7 * 86_400 -> "il y a ${s / 86_400} j"
            s < 30 * 86_400 -> "il y a ${s / 604_800} sem."
            s < 365 * 86_400 -> "il y a ${s / 2_592_000} mois"
            else -> (s / 31_536_000).let { "il y a $it an" + if (it > 1) "s" else "" }
        }
    }
}
