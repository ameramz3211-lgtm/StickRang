package com.stickrang.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/** Pulls frames out of a GIF or a short video clip for an animated sticker. */
object MediaFrames {
    /** WhatsApp plays up to 10 s, but short loops stay under the 500 KB limit with better quality. */
    const val MAX_CLIP_MS = 3_000
    const val FRAME_MS = 100
    private const val MAX_SIDE = 512

    class Clip(val frames: List<Bitmap>, val frameMs: Int, val durationMs: Int)

    fun isAnimated(context: Context, uri: Uri): Boolean {
        val type = context.contentResolver.getType(uri) ?: return false
        return type == "image/gif" || type.startsWith("video/")
    }

    /** Frames from [startMs] for up to [MAX_CLIP_MS], or null if the file can't be read. */
    fun load(context: Context, uri: Uri, startMs: Int = 0): Clip? = try {
        val type = context.contentResolver.getType(uri).orEmpty()
        if (type == "image/gif") gif(context, uri, startMs) else video(context, uri, startMs)
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    @Suppress("DEPRECATION") // Movie is the only GIF decoder on API 24-27 that can seek to a time.
    private fun gif(context: Context, uri: Uri, startMs: Int): Clip? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val movie = android.graphics.Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val w = movie.width()
        val h = movie.height()
        if (w <= 0 || h <= 0) return null
        val total = max(movie.duration(), 1)
        val start = if (startMs < total) startMs else 0
        val end = min(total, start + MAX_CLIP_MS)
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        val frames = mutableListOf<Bitmap>()
        var t = start
        while (t < end || frames.isEmpty()) {
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            movie.setTime(t)
            movie.draw(c, 0f, 0f)
            frames += scaled(full)
            t += FRAME_MS
        }
        return Clip(frames, FRAME_MS, total)
    }

    private fun video(context: Context, uri: Uri, startMs: Int): Clip? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val total = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: return null
            val start = if (startMs < total) startMs else 0
            val end = min(total, start + MAX_CLIP_MS)
            val frames = mutableListOf<Bitmap>()
            var t = start
            while (t < end) {
                r.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)?.let { frames += scaled(it) }
                t += FRAME_MS
            }
            return if (frames.isEmpty()) null else Clip(frames, FRAME_MS, total)
        } finally {
            r.release()
        }
    }

    private fun scaled(b: Bitmap): Bitmap {
        val s = MAX_SIDE.toFloat() / max(b.width, b.height)
        if (s >= 1f) return b.copy(Bitmap.Config.ARGB_8888, false)
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }
}
