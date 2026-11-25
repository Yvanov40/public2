package fr.lecteur

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

object Yt {
    suspend fun search(query: String): List<StreamInfoItem> = withContext(Dispatchers.IO) {
        val ex = ServiceList.YouTube.getSearchExtractor(query, listOf("videos"), "")
        ex.fetchPage()
        ex.initialPage.items.filterIsInstance<StreamInfoItem>()
    }

    suspend fun info(url: String): StreamInfo = withContext(Dispatchers.IO) {
        StreamInfo.getInfo(url)
    }

    fun looksLikeUrl(s: String) = s.startsWith("http://") || s.startsWith("https://") ||
        s.contains("youtu.be/") || s.contains("youtube.com/")

    fun formatDuration(sec: Long): String {
        if (sec <= 0) return ""
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
