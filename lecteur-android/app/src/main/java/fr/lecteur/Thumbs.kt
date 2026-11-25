package fr.lecteur

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/** Chargeur de miniatures minimal (pas de bibliothèque d'images). */
object Thumbs {
    private val cache = LruCache<String, Bitmap>(100)
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, url: String?) {
        view.tag = url
        view.setImageDrawable(null)
        if (url == null) return
        cache.get(url)?.let { view.setImageBitmap(it); return }
        pool.execute {
            try {
                val req = okhttp3.Request.Builder().url(url).build()
                val bytes = DownloaderImpl.instance.client.newCall(req).execute().use { it.body?.bytes() }
                    ?: return@execute
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                cache.put(url, bmp)
                main.post { if (view.tag == url) view.setImageBitmap(bmp) }
            } catch (_: Exception) {
            }
        }
    }
}
