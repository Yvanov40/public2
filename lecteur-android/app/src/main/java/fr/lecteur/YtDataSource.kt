package fr.lecteur

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource

/**
 * Accès aux flux googlevideo.com à la manière de NewPipe : plage d'octets passée
 * dans l'URL (&range=…) plutôt qu'en en-tête, user-agent cohérent avec le client
 * YouTube qui a produit l'URL, et POST pour les URL du client web.
 */
@OptIn(UnstableApi::class)
class YtDataSource(private val upstream: HttpDataSource) : DataSource by upstream {

    private var rn = 0

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.host?.endsWith("googlevideo.com") != true) return upstream.open(dataSpec)

        val client = clientOf(uri)
        val headers = HashMap(dataSpec.httpRequestHeaders)
        headers.putAll(headersFor(uri))

        val builder = dataSpec.buildUpon().setHttpRequestHeaders(headers)
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val end = dataSpec.position + dataSpec.length - 1
            val ranged = uri.buildUpon()
                .appendQueryParameter("range", "${dataSpec.position}-$end")
                .appendQueryParameter("rn", (++rn).toString())
                .build()
            builder.setUri(ranged)
                .setUriPositionOffset(0)
                .setPosition(0)
                .setLength(C.LENGTH_UNSET.toLong())
            if (client.startsWith("WEB")) {
                builder.setHttpMethod(DataSpec.HTTP_METHOD_POST)
                    .setHttpBody(byteArrayOf(0x78, 0))
            }
        }
        return upstream.open(builder.build())
    }

    companion object {
        fun clientOf(uri: Uri) = uri.getQueryParameter("c").orEmpty().uppercase()

        fun isGoogleVideo(uri: Uri) = uri.host?.endsWith("googlevideo.com") == true

        fun isWebClient(uri: Uri) = clientOf(uri).startsWith("WEB")

        /** En-têtes attendus par googlevideo selon le client YouTube qui a produit l'URL. */
        fun headersFor(uri: Uri): Map<String, String> {
            if (!isGoogleVideo(uri)) return mapOf("User-Agent" to DownloaderImpl.USER_AGENT)
            val c = clientOf(uri)
            val h = mutableMapOf("User-Agent" to userAgentFor(c))
            if (c.startsWith("WEB") || c.startsWith("MWEB") || c.startsWith("TVHTML5")) {
                h["Origin"] = "https://www.youtube.com"
                h["Referer"] = "https://www.youtube.com/"
            }
            return h
        }

        private fun userAgentFor(client: String): String = when {
        client.startsWith("ANDROID_VR") ->
            "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; fr_FR; Oculus Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)"
        client.startsWith("ANDROID") ->
            "com.google.android.youtube/19.28.35 (Linux; U; Android 15; FR) gzip"
        client.startsWith("IOS") ->
            "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)"
        else -> DownloaderImpl.USER_AGENT
        }
    }

    class Factory : DataSource.Factory {
        private val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(mapOf("User-Agent" to DownloaderImpl.USER_AGENT))

        override fun createDataSource(): DataSource = YtDataSource(http.createDataSource())
    }
}
