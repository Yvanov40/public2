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

    fun load(view: ImageView, url: String?, placeholder: Int = 0) {
        view.tag = url
        view.scaleType = ImageView.ScaleType.CENTER_INSIDE
        if (placeholder != 0) view.setImageResource(placeholder) else view.setImageDrawable(null)
        if (url == null) return
        cache.get(url)?.let { show(view, it); return }
        pool.execute {
            try {
                val req = okhttp3.Request.Builder().url(url).build()
                val bytes = DownloaderImpl.instance.client.newCall(req).execute().use { it.body?.bytes() }
                    ?: return@execute
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                cache.put(url, bmp)
                main.post { if (view.tag == url) show(view, bmp) }
            } catch (_: Exception) {
            }
        }
    }

    private fun show(view: ImageView, bmp: Bitmap) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageBitmap(bmp)
    }
}
