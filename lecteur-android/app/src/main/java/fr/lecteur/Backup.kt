package fr.lecteur

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/** Sauvegarde / restauration complète, import d'abonnements (OPML, NewPipe, Google Takeout), export OPML. */
object Backup {
    private val files = listOf("store", "settings", "lecteur")

    fun export(ctx: Context, out: Uri) {
        val root = JSONObject().put("app", "lecteur").put("version", 1)
        for (name in files) {
            val o = JSONObject()
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                val e = when (v) {
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> null
                }
                if (e != null) o.put(k, e)
            }
            root.put(name, o)
        }
        ctx.contentResolver.openOutputStream(out)?.use { it.write(root.toString(2).toByteArray()) }
    }

    fun restore(ctx: Context, src: Uri) {
        val text = ctx.contentResolver.openInputStream(src)?.use { it.readBytes().decodeToString() } ?: error("fichier illisible")
        val root = JSONObject(text)
        require(root.optString("app") == "lecteur") { "ce n'est pas une sauvegarde de Lecteur" }
        for (name in files) {
            val o = root.optJSONObject(name) ?: continue
            val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                when (e.optString("t")) {
                    "s" -> ed.putString(k, e.optString("v"))
                    "b" -> ed.putBoolean(k, e.optBoolean("v"))
                    "i" -> ed.putInt(k, e.optInt("v"))
                    "l" -> ed.putLong(k, e.optLong("v"))
                    "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                    "set" -> e.optJSONArray("v")?.let { a -> ed.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet()) }
                }
            }
            ed.commit()
        }
        Store.init(ctx)
        Settings.init(ctx)
    }

    class ImportResult(val podcasts: List<Podcast>, val channels: List<Chan>)

    /** Reconnaît le format : OPML (Podcast Addict…), NewPipe (JSON), Google Takeout (CSV). */
    fun importSubscriptions(ctx: Context, src: Uri): ImportResult {
        val text = ctx.contentResolver.openInputStream(src)?.use { it.readBytes().decodeToString() } ?: error("fichier illisible")
        val t = text.trimStart()
        val pods = mutableListOf<Podcast>()
        val chans = mutableListOf<Chan>()
        when {
            t.startsWith("<") -> {
                val p = Xml.newPullParser()
                p.setInput(text.reader())
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name.equals("outline", true)) {
                        val xml = p.getAttributeValue(null, "xmlUrl") ?: p.getAttributeValue(null, "xmlurl")
                        if (!xml.isNullOrBlank()) {
                            val title = p.getAttributeValue(null, "title") ?: p.getAttributeValue(null, "text") ?: xml
                            // Les flux de chaînes YouTube deviennent des abonnements YouTube
                            val cid = Regex("channel_id=([\\w-]+)").find(xml)?.groupValues?.get(1)
                            if (cid != null) chans += Chan("https://www.youtube.com/channel/$cid", title, null)
                            else pods += Podcast(xml, title, null, null)
                        }
                    }
                }
            }
            t.startsWith("{") -> {
                val arr = JSONObject(text).optJSONArray("subscriptions") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url")
                    if (url.contains("youtube.com")) chans += Chan(url, o.optString("name").ifEmpty { url }, null)
                }
            }
            else -> {
                // Google Takeout : « Channel Id,Channel Url,Channel Title »
                text.lineSequence().drop(1).forEach { line ->
                    val cols = line.split(",")
                    if (cols.size >= 3 && cols[1].contains("youtube.com")) {
                        chans += Chan(cols[1].trim().replace("http://", "https://"), cols.drop(2).joinToString(",").trim(), null)
                    }
                }
            }
        }
        pods.forEach { p -> if (!Store.isPodSubscribed(p.feedUrl)) Store.subscribePodcast(p) }
        chans.forEach { c -> if (!Store.isSubscribed(c.url)) Store.subscribe(c) }
        return ImportResult(pods, chans)
    }

    fun exportOpml(ctx: Context, out: Uri) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n")
        sb.append("  <head><title>Podcasts de Lecteur</title></head>\n  <body>\n")
        Store.podcasts().forEach { p ->
            sb.append("    <outline type=\"rss\" text=\"${esc(p.title)}\" title=\"${esc(p.title)}\" xmlUrl=\"${esc(p.feedUrl)}\" />\n")
        }
        sb.append("  </body>\n</opml>\n")
        ctx.contentResolver.openOutputStream(out)?.use { it.write(sb.toString().toByteArray()) }
    }
}
