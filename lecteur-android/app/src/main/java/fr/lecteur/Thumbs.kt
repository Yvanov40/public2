package fr.lecteur

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
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
        val app = view.context.applicationContext
        pool.execute {
            try {
                if (url.startsWith("content://")) {
                    if (Build.VERSION.SDK_INT < 29) return@execute
                    val bmp = app.contentResolver.loadThumbnail(Uri.parse(url), Size(480, 270), null)
                    cache.put(url, bmp)
                    main.post { if (view.tag == url) show(view, bmp) }
                    return@execute
                }
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

    /** Récupère l'image (pour en extraire les couleurs). */
    fun get(ctx: android.content.Context, url: String?, cb: (Bitmap?) -> Unit) {
        if (url == null) return cb(null)
        cache.get(url)?.let { return cb(it) }
        val app = ctx.applicationContext
        pool.execute {
            val bmp = try {
                if (url.startsWith("content://")) {
                    if (Build.VERSION.SDK_INT >= 29) app.contentResolver.loadThumbnail(Uri.parse(url), Size(480, 480), null) else null
                } else {
                    val req = okhttp3.Request.Builder().url(url).build()
                    DownloaderImpl.instance.client.newCall(req).execute().use { it.body?.bytes() }
                        ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            } catch (_: Exception) {
                null
            }
            bmp?.let { cache.put(url, it) }
            main.post { cb(bmp) }
        }
    }

    private fun show(view: ImageView, bmp: Bitmap) {
        view.scaleType = ImageView.ScaleType.CENTER_CROP
        view.setImageBitmap(bmp)
    }
}
