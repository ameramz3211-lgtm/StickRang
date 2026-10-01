package com.stickrang.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/** Tiny async loader for sticker thumbnails, so the app needs no image library. */
object Images {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)

    fun load(view: ImageView, pack: StickerPack, fileName: String, sizePx: Int) {
        val key = "${pack.identifier}/${pack.imageDataVersion}/$fileName@$sizePx"
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        val ctx = view.context.applicationContext
        pool.execute {
            val bmp = decode(ctx, pack, fileName, sizePx) ?: return@execute
            cache.put(key, bmp)
            view.post { if (view.tag == key) view.setImageBitmap(bmp) }
        }
    }

    /** Plays an animated sticker (Android 9+); older phones show its first frame. */
    fun loadAnimated(view: ImageView, pack: StickerPack, fileName: String) {
        if (android.os.Build.VERSION.SDK_INT < 28) { load(view, pack, fileName, 256); return }
        val key = "${pack.identifier}/${pack.imageDataVersion}/$fileName@anim"
        view.tag = key
        view.setImageDrawable(null)
        val ctx = view.context.applicationContext
        pool.execute {
            val d = try {
                val bytes = readBytes(ctx, pack, fileName)
                android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)))
            } catch (e: Exception) {
                null
            } ?: return@execute
            view.post {
                if (view.tag != key) return@post
                view.setImageDrawable(d)
                (d as? android.graphics.drawable.AnimatedImageDrawable)?.start()
            }
        }
    }

    private fun readBytes(ctx: Context, pack: StickerPack, fileName: String): ByteArray =
        if (pack.isCustom) File(File(StickerRepository.customDir(ctx), pack.identifier), fileName).readBytes()
        else ctx.assets.open("${pack.identifier}/$fileName").use { it.readBytes() }

    private fun decode(ctx: Context, pack: StickerPack, fileName: String, sizePx: Int): Bitmap? = try {
        val bytes = readBytes(ctx, pack, fileName)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= sizePx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }
}
