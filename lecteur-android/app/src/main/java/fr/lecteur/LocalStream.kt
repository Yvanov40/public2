package fr.lecteur

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Petit serveur sur le Wi-Fi : le téléphone sert de relais entre YouTube (ou un fichier du téléphone)
 * et l'enceinte Chromecast, qui ne sait pas récupérer ces fichiers seule.
 */
object LocalStream {
    private const val TAG = "LocalStream"
    private const val CHUNK = 4L * 1024 * 1024

    class Entry(
        val ytUrl: String? = null,      // flux googlevideo
        val contentUri: Uri? = null,    // fichier du téléphone
        val mime: String,
        val length: Long                // taille en octets (-1 si inconnue)
    )

    private lateinit var app: Context
    private var server: ServerSocket? = null
    private val entries = ConcurrentHashMap<String, Entry>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /** Adresse IPv4 du téléphone sur le réseau local. */
    private fun localIp(): String? {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    @Synchronized
    private fun ensureServer(ip: String): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket()
        s.reuseAddress = true
        // Écoute seulement sur l'adresse Wi-Fi du téléphone (pas sur les autres réseaux)
        s.bind(InetSocketAddress(java.net.InetAddress.getByName(ip), 0))
        server = s
        thread(name = "LocalStream", isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(c) }
            }
        }
        return s.localPort
    }

    /** Enregistre un flux et renvoie l'adresse à donner à l'enceinte. */
    fun publish(e: Entry): String {
        val ip = localIp() ?: throw java.io.IOException("pas de Wi-Fi : l'enceinte doit être sur le même réseau")
        val port = ensureServer(ip)
        if (entries.size > 30) entries.clear()
        val token = UUID.randomUUID().toString().replace("-", "")
        entries[token] = e
        return "http://$ip:$port/s/$token"
    }

    /** Fin de diffusion : le serveur est fermé et les adresses oubliées. */
    @Synchronized
    fun stop() {
        entries.clear()
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(c: Socket) {
        c.use { sock ->
            try {
                sock.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = reader.readLine() ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val token = parts.getOrNull(1)?.substringAfter("/s/", "")?.substringBefore("?").orEmpty()
                val out = sock.getOutputStream()
                if (method == "OPTIONS") {
                    out.write(("HTTP/1.1 204 No Content\r\n" + cors() + "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                    return
                }
                val e = entries[token]
                if (e == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                serve(e, method, headers["range"], out)
            } catch (ex: Exception) {
                Log.d(TAG, "connexion terminée : ${ex.message}")
            }
        }
    }

    private fun cors() = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Range\r\n" +
        "Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n"

    private fun serve(e: Entry, method: String, range: String?, out: OutputStream) {
        val total = e.length
        var start = 0L
        var end = if (total > 0) total - 1 else -1L
        val partial = range != null && range.startsWith("bytes=")
        if (partial) {
            val spec = range!!.removePrefix("bytes=").split(",")[0]
            val a = spec.substringBefore('-').toLongOrNull()
            val b = spec.substringAfter('-').toLongOrNull()
            if (a != null) {
                start = a
                if (b != null) end = b
            } else if (b != null && total > 0) {
                start = (total - b).coerceAtLeast(0)
            }
        }
        if (total > 0) end = end.coerceAtMost(total - 1)
        val head = StringBuilder()
        if (partial && total > 0) {
            head.append("HTTP/1.1 206 Partial Content\r\n")
            head.append("Content-Range: bytes $start-$end/$total\r\n")
            head.append("Content-Length: ${end - start + 1}\r\n")
        } else {
            head.append("HTTP/1.1 200 OK\r\n")
            if (total > 0) head.append("Content-Length: $total\r\n")
        }
        head.append("Content-Type: ${e.mime}\r\nAccept-Ranges: bytes\r\n").append(cors()).append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        out.flush()
        if (method == "HEAD") return

        when {
            e.contentUri != null -> app.contentResolver.openInputStream(e.contentUri)?.use { input ->
                skipFully(input, start)
                copy(input, out, if (end >= 0) end - start + 1 else Long.MAX_VALUE)
            }
            e.ytUrl != null -> {
                if (total <= 0) {
                    // Taille inconnue : un seul flux, sans découpage
                    request(e.ytUrl, null).use { r -> r.body?.byteStream()?.let { copy(it, out, Long.MAX_VALUE) } }
                } else {
                    // Par morceaux, comme le fait NewPipe (YouTube bride les gros téléchargements)
                    var pos = start
                    while (pos <= end) {
                        val to = minOf(pos + CHUNK - 1, end)
                        request(e.ytUrl, pos to to).use { r ->
                            val body = r.body ?: throw java.io.IOException("réponse vide")
                            copy(body.byteStream(), out, to - pos + 1)
                        }
                        pos = to + 1
                    }
                }
            }
        }
        out.flush()
    }

    private var rn = 0

    private fun request(url: String, range: Pair<Long, Long>?): okhttp3.Response {
        val uri = Uri.parse(url)
        val full = if (range != null) uri.buildUpon()
            .appendQueryParameter("range", "${range.first}-${range.second}")
            .appendQueryParameter("rn", (++rn).toString())
            .build().toString() else url
        val b = okhttp3.Request.Builder().url(full)
        YtDataSource.headersFor(uri).forEach { (k, v) -> b.header(k, v) }
        if (range != null && YtDataSource.isWebClient(uri)) b.post(byteArrayOf(0x78, 0).toRequestBody(null))
        val r = DownloaderImpl.instance.client.newCall(b.build()).execute()
        if (!r.isSuccessful) {
            r.close()
            throw java.io.IOException("YouTube : HTTP ${r.code}")
        }
        return r
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) return
                left--
            } else left -= s
        }
    }

    private fun copy(input: InputStream, out: OutputStream, max: Long) {
        val buf = ByteArray(64 * 1024)
        var left = max
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }
}
