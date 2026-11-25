package fr.lecteur

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/** Chargeur d'images minimal, cache limité en mémoire. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
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
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 640) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@execute
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
