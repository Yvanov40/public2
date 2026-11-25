package fr.lecteur

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Assemble la vidéo seule (H.264) et l'audio seul (AAC) de YouTube en un seul MP4. */
object Muxer {
    fun mux(video: File, audio: File, out: File) {
        val ve = MediaExtractor().apply { setDataSource(video.path) }
        val ae = MediaExtractor().apply { setDataSource(audio.path) }
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vi = track(ve, "video/")
            val ai = track(ae, "audio/")
            ve.selectTrack(vi)
            ae.selectTrack(ai)
            val vt = muxer.addTrack(ve.getTrackFormat(vi))
            val at = muxer.addTrack(ae.getTrackFormat(ai))
            muxer.start()

            val buf = ByteBuffer.allocate(8 shl 20)
            val info = MediaCodec.BufferInfo()
            var vDone = false
            var aDone = false
            // Échantillons entrelacés par horodatage
            while (!vDone || !aDone) {
                val useVideo = when {
                    vDone -> false
                    aDone -> true
                    else -> ve.sampleTime <= ae.sampleTime
                }
                val ex = if (useVideo) ve else ae
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) {
                    if (useVideo) vDone = true else aDone = true
                    continue
                }
                info.offset = 0
                info.size = n
                info.presentationTimeUs = ex.sampleTime
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(if (useVideo) vt else at, buf, info)
                ex.advance()
            }
            muxer.stop()
        } finally {
            try { muxer.release() } catch (_: Exception) {}
            ve.release()
            ae.release()
        }
    }

    private fun track(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        throw IOException("piste $prefix introuvable")
    }
}
