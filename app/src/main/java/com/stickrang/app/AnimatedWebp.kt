package com.stickrang.app

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream

/**
 * Builds animated WebP files for WhatsApp animated stickers. Android can only encode
 * single-frame WebP, so each frame is encoded with [Bitmap.compress] and its image
 * chunks are wrapped in ANMF frames inside a VP8X + ANIM container (WebP container spec).
 */
object AnimatedWebp {
    /** WhatsApp limits for animated stickers. */
    const val MAX_BYTES = 500 * 1024
    const val MAX_DURATION_MS = 10_000
    const val MIN_FRAME_MS = 8

    /**
     * Encodes [frames] (all the same size) shown for [frameMs] each, looping forever.
     * Lowers quality, then drops every other frame, until the file fits [MAX_BYTES].
     */
    fun encode(frames: List<Bitmap>, frameMs: Int): ByteArray {
        require(frames.isNotEmpty())
        var list = frames
        var ms = frameMs.coerceAtLeast(MIN_FRAME_MS)
        while (list.size * ms > MAX_DURATION_MS && list.size > 1) { list = list.filterIndexed { i, _ -> i % 2 == 0 }; ms *= 2 }
        while (true) {
            for (q in intArrayOf(75, 60, 45, 30)) {
                val out = build(list, ms, q)
                if (out.size <= MAX_BYTES) return out
            }
            if (list.size <= 2) return build(list, ms, 20)
            list = list.filterIndexed { i, _ -> i % 2 == 0 }
            ms *= 2
        }
    }

    private fun build(frames: List<Bitmap>, frameMs: Int, quality: Int): ByteArray =
        mux(frames.map { encodeFrame(it, quality) }, frames[0].width, frames[0].height, frameMs)

    /** Wraps single-image WebP files (same size) into one looping animated WebP. */
    fun mux(encodedFrames: List<ByteArray>, w: Int, h: Int, frameMs: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray())
        // VP8X: animation + alpha flags, canvas size
        chunk(body, "VP8X", ByteArrayOutputStream().apply {
            write(0x10 or 0x02); write(0); write(0); write(0)
            u24(this, w - 1); u24(this, h - 1)
        }.toByteArray())
        // ANIM: transparent background, loop forever
        chunk(body, "ANIM", byteArrayOf(0, 0, 0, 0, 0, 0))
        for (f in encodedFrames) {
            val data = imageChunks(f)
            val anmf = ByteArrayOutputStream()
            u24(anmf, 0); u24(anmf, 0) // x/2, y/2 offset
            u24(anmf, w - 1); u24(anmf, h - 1)
            u24(anmf, frameMs)
            anmf.write(0x02) // do not blend with the previous frame, no disposal
            anmf.write(data)
            chunk(body, "ANMF", anmf.toByteArray())
        }
        val payload = body.toByteArray()
        return ByteArrayOutputStream(payload.size + 8).apply {
            write("RIFF".toByteArray()); u32(this, payload.size); write(payload)
        }.toByteArray()
    }

    private fun encodeFrame(bmp: Bitmap, quality: Int): ByteArray {
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        return ByteArrayOutputStream().also { bmp.compress(format, quality, it) }.toByteArray()
    }

    /** The ALPH / VP8 / VP8L chunks (with headers) of a single-image WebP file. */
    private fun imageChunks(webp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 12 // skip "RIFF" size "WEBP"
        while (i + 8 <= webp.size) {
            val tag = String(webp, i, 4, Charsets.US_ASCII)
            val size = (webp[i + 4].toInt() and 0xFF) or ((webp[i + 5].toInt() and 0xFF) shl 8) or
                ((webp[i + 6].toInt() and 0xFF) shl 16) or ((webp[i + 7].toInt() and 0xFF) shl 24)
            val total = 8 + size + (size and 1)
            if (tag == "ALPH" || tag == "VP8 " || tag == "VP8L") out.write(webp, i, minOf(total, webp.size - i))
            i += total
        }
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, tag: String, data: ByteArray) {
        out.write(tag.toByteArray(Charsets.US_ASCII))
        u32(out, data.size)
        out.write(data)
        if (data.size and 1 == 1) out.write(0)
    }

    private fun u24(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF)
    }

    private fun u32(out: ByteArrayOutputStream, v: Int) {
        u24(out, v); out.write((v shr 24) and 0xFF)
    }
}
