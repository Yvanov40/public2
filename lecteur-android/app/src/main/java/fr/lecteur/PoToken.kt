package fr.lecteur

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Jeton anti-robot de YouTube (« PO Token »), généré comme NewPipe :
 * le script BotGuard de Google tourne dans un navigateur caché (WebView).
 */
object PoToken : PoTokenProvider {
    private const val TAG = "PoToken"
    private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
    private const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var ready = false
    private var expiresAt = 0L
    private var visitorData: String? = null
    @Volatile private var failures = 0
    private val callbacks = ConcurrentHashMap<String, (String?, String?) -> Unit>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        try {
            YoutubeStreamExtractor.setPoTokenProvider(this)
        } catch (e: Throwable) {
            Log.w(TAG, "Impossible d'installer le fournisseur de jeton", e)
        }
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? = generate(videoId)

    private fun generate(videoId: String): PoTokenResult? {
        // Désactivé, ou trop d'échecs de suite : on laisse NewPipe se débrouiller sans
        if (!Settings.poToken || failures >= 3) return null
        return try {
            synchronized(this) {
                ensureReady()
                val vd = visitorData ?: throw IOException("visitorData absent")
                val player = mint(videoId)
                val streaming = mint(vd)
                failures = 0
                PoTokenResult(vd, player, streaming)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Jeton impossible", e)
            failures++
            reset()
            null
        }
    }

    /** Diagnostic : génère un jeton de test et renvoie un résumé (lève une exception si échec). */
    fun selfTest(): String {
        val t0 = System.currentTimeMillis()
        return try {
            synchronized(this) {
                ensureReady()
                val tok = mint("jNQXAC9IVRw")
                failures = 0
                "OK (jeton de ${tok.length} caractères, ${System.currentTimeMillis() - t0} ms)"
            }
        } catch (e: Exception) {
            reset()
            throw e
        }
    }

    /** Le jeton a été refusé par YouTube : on n'en génère plus jusqu'au prochain lancement. */
    fun disableForSession() {
        failures = 3
    }

    private fun reset() {
        ready = false
        expiresAt = 0
        visitorData = null
        main.post {
            web?.destroy()
            web = null
        }
    }

    private fun http(url: String, body: String, json: Boolean = false): String {
        val type = if (json) "application/json" else "application/json+protobuf"
        val b = okhttp3.Request.Builder().url(url)
            .header("User-Agent", DownloaderImpl.USER_AGENT)
            .header("Content-Type", type)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
        if (!json) {
            b.header("x-goog-api-key", API_KEY)
            b.header("x-user-agent", "grpc-web-javascript/0.1")
        }
        b.post(body.toRequestBody(type.toMediaType()))
        DownloaderImpl.instance.client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} ($url)")
            return r.body?.string() ?: throw IOException("réponse vide")
        }
    }

    private fun fetchVisitorData(): String {
        val body = JSONObject().put(
            "context", JSONObject().put(
                "client", JSONObject().put("clientName", "WEB").put("clientVersion", "2.20250925.01.00")
                    .put("hl", "fr").put("gl", "FR")
            )
        ).toString()
        val o = JSONObject(http("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", body, json = true))
        return o.optJSONObject("responseContext")?.optString("visitorData")?.ifEmpty { null }
            ?: throw IOException("visitorData introuvable")
    }

    /** Défi BotGuard, converti au format attendu par le script. */
    private fun fetchChallenge(): String {
        val raw = JSONArray(http("https://www.youtube.com/api/jnn/v1/Create", JSONArray().put(REQUEST_KEY).toString()))
        val data = if (raw.length() > 1 && raw.opt(1) is String) {
            val bytes = Base64.decode(raw.getString(1), Base64.DEFAULT)
            JSONArray(String(ByteArray(bytes.size) { (bytes[it] + 97).toByte() }, Charsets.UTF_8))
        } else raw.getJSONArray(0)

        fun firstString(a: JSONArray?): String? {
            if (a == null) return null
            for (i in 0 until a.length()) (a.opt(i) as? String)?.let { return it }
            return null
        }
        var script = firstString(data.optJSONArray(1))
        val scriptUrl = firstString(data.optJSONArray(2))
        if (script == null && scriptUrl != null) {
            val u = if (scriptUrl.startsWith("//")) "https:$scriptUrl" else scriptUrl
            val req = okhttp3.Request.Builder().url(u).header("User-Agent", DownloaderImpl.USER_AGENT).build()
            script = DownloaderImpl.instance.client.newCall(req).execute().use { it.body?.string() }
        }
        return JSONObject()
            .put("messageId", data.optString(0))
            .put(
                "interpreterJavascript", JSONObject()
                    .put("privateDoNotAccessOrElseSafeScriptWrappedValue", script ?: JSONObject.NULL)
                    .put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", scriptUrl ?: JSONObject.NULL)
            )
            .put("interpreterHash", data.optString(3))
            .put("program", data.optString(4))
            .put("globalName", data.optString(5))
            .put("clientExperimentsStateBlob", data.optString(7))
            .toString()
    }

    private fun ensureReady() {
        if (ready && web != null && visitorData != null && System.currentTimeMillis() < expiresAt - 60_000) return
        reset()
        visitorData = fetchVisitorData()
        createWebView()
        val challenge = fetchChallenge()
        val bg = js("runBotGuard($challenge).then(r => { window.__wpso = r.webPoSignalOutput; return r.botguardResponse; })")
        val it = JSONArray(http("https://www.youtube.com/api/jnn/v1/GenerateIT", JSONArray().put(REQUEST_KEY).put(bg).toString()))
        val integrity = it.getString(0)
        val ttl = it.optLong(1, 3600).coerceAtLeast(300)
        js("obtainMinter(window.__wpso, base64ToU8(${JSONObject.quote(integrity)})).then(m => { window.__mint = m; return 'ok'; })")
        ready = true
        expiresAt = System.currentTimeMillis() + ttl * 1000
    }

    private fun mint(identifier: String): String =
        js("window.__mint(new TextEncoder().encode(${JSONObject.quote(identifier)})).then(u8 => u8ToBase64(u8, true))")

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView() {
        val loaded = CountDownLatch(1)
        val html = app.assets.open("po_token.html").use { it.readBytes().decodeToString() }
        main.post {
            try {
                val w = WebView(app)
                w.settings.javaScriptEnabled = true
                w.addJavascriptInterface(Bridge(), "Lecteur")
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loaded.countDown()
                    }
                }
                w.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
                web = w
            } catch (e: Throwable) {
                Log.w(TAG, "WebView indisponible", e)
                loaded.countDown()
            }
        }
        if (!loaded.await(15, TimeUnit.SECONDS) || web == null) throw IOException("navigateur caché indisponible")
    }

    /** Exécute une expression JavaScript asynchrone et attend son résultat (texte). */
    private fun js(expr: String): String {
        val id = UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        var value: String? = null
        var error: String? = null
        callbacks[id] = { v, e ->
            value = v
            error = e
            latch.countDown()
        }
        val code = "(async () => { try { const v = await ($expr); Lecteur.onResult('$id', String(v)); } " +
            "catch (e) { Lecteur.onError('$id', String((e && e.stack) || e)); } })();"
        main.post {
            val w = web
            if (w == null) callbacks.remove(id)?.invoke(null, "navigateur fermé")
            else w.evaluateJavascript(code, null)
        }
        if (!latch.await(25, TimeUnit.SECONDS)) {
            callbacks.remove(id)
            throw IOException("délai dépassé")
        }
        error?.let { throw IOException(it) }
        return value ?: throw IOException("résultat vide")
    }

    private class Bridge {
        @JavascriptInterface
        fun onResult(id: String, v: String) {
            callbacks.remove(id)?.invoke(v, null)
        }

        @JavascriptInterface
        fun onError(id: String, e: String) {
            callbacks.remove(id)?.invoke(null, e)
        }
    }
}
