package com.stickrang.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Draws WhatsApp-ready 512x512 stickers in the same die-cut style as the bundled packs. */
object StickerRenderer {
    const val SIZE = 512
    const val TRAY = 96
    private const val MAX_BYTES = 100 * 1024

    /** NONE = text only on a transparent background (no bubble). */
    enum class Shape { CIRCLE, ROUNDED, NONE }

    private const val DARK = 0xFF222222.toInt()

    fun textSticker(text: String, fill: Int, typeface: Typeface, shape: Shape): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (shape == Shape.NONE) {
            // Coloured letters with a thick white die-cut edge, readable on any chat wallpaper.
            drawCaption(c, text, typeface, RectF(28f, 28f, SIZE - 28f, SIZE - 28f), fill, Color.WHITE, startSize = 150f, shadow = true)
        } else {
            drawBubble(c, shape) { rect, p -> p.color = fill; drawShape(c, rect, shape, p) }
            val light = fill == Color.WHITE
            drawCaption(c, text, typeface, RectF(70f, 90f, SIZE - 70f, SIZE - 90f), if (light) DARK else Color.WHITE, if (light) Color.WHITE else DARK)
        }
        return bmp
    }

    /**
     * Die-cut sticker from a photo whose background was removed: the subject is trimmed,
     * centred, and given a white outline and soft shadow like a real vinyl sticker.
     */
    fun cutoutSticker(subject: Bitmap, caption: String, typeface: Typeface, textColor: Int, fixedBounds: Rect? = null): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Animated cut-outs pass the union of all frames' bounds so the subject doesn't jump around.
        val bounds = fixedBounds ?: opaqueBounds(subject) ?: return bmp
        val border = 12f
        val bottom = if (caption.isBlank()) SIZE - 20f else SIZE - 150f
        val box = RectF(20f + border, 20f + border, SIZE - 20f - border, bottom - border)
        val scale = min(box.width() / bounds.width(), box.height() / bounds.height())
        val w = bounds.width() * scale
        val h = bounds.height() * scale
        val dst = RectF(box.centerX() - w / 2, box.bottom - h, box.centerX() + w / 2, box.bottom)
        if (caption.isBlank()) dst.offset(0f, -(box.height() - h) / 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // shadow, then white outline: the subject's silhouette stamped in a ring around it
        p.colorFilter = PorterDuffColorFilter(0x40000000, PorterDuff.Mode.SRC_IN)
        c.drawBitmap(subject, bounds, RectF(dst).apply { offset(0f, border / 2 + 4f) }, p)
        p.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        for (r in listOf(border / 2, border)) {
            for (i in 0 until 24) {
                val a = Math.PI * 2 * i / 24
                c.drawBitmap(subject, bounds, RectF(dst).apply { offset((cos(a) * r).toFloat(), (sin(a) * r).toFloat()) }, p)
            }
        }
        p.colorFilter = null
        c.drawBitmap(subject, bounds, dst, p)
        if (caption.isNotBlank()) {
            drawCaption(c, caption, typeface, RectF(30f, SIZE - 150f, SIZE - 30f, SIZE - 20f), textColor, if (textColor == Color.WHITE) DARK else Color.WHITE)
        }
        return bmp
    }

    /** Bounding box of pixels that are not (almost) transparent, or null if there are none. */
    fun opaqueBounds(b: Bitmap): Rect? {
        val w = b.width
        val h = b.height
        val row = IntArray(w)
        var l = w; var t = h; var r = -1; var bt = -1
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) if (row[x] ushr 24 > 24) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                bt = y
            }
        }
        return if (r < 0) null else Rect(l, t, r + 1, bt + 1)
    }

    fun photoSticker(photo: Bitmap, caption: String, typeface: Typeface, shape: Shape, textColor: Int = Color.WHITE): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawBubble(c, shape) { rect, p ->
            // centre-crop the photo into the shape
            val scale = max(rect.width() / photo.width, rect.height() / photo.height)
            val m = Matrix().apply {
                setScale(scale, scale)
                postTranslate(rect.centerX() - photo.width * scale / 2, rect.centerY() - photo.height * scale / 2)
            }
            p.shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
            drawShape(c, rect, shape, p)
            p.shader = null
        }
        if (caption.isNotBlank()) {
            drawCaption(c, caption, typeface, RectF(50f, SIZE - 190f, SIZE - 50f, SIZE - 40f), textColor, if (textColor == Color.WHITE) DARK else Color.WHITE)
        }
        return bmp
    }

    private inline fun drawBubble(c: Canvas, shape: Shape, fill: (RectF, Paint) -> Unit) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val outer = RectF(24f, 24f, SIZE - 24f, SIZE - 24f)
        p.color = 0x33000000
        drawShape(c, RectF(outer.left, outer.top + 8f, outer.right, outer.bottom + 4f), shape, p)
        p.color = Color.WHITE
        drawShape(c, outer, shape, p)
        fill(RectF(outer.left + 14f, outer.top + 14f, outer.right - 14f, outer.bottom - 14f), p)
    }

    private fun drawShape(c: Canvas, r: RectF, shape: Shape, p: Paint) {
        when (shape) {
            Shape.CIRCLE -> c.drawOval(r, p)
            Shape.ROUNDED, Shape.NONE -> c.drawRoundRect(r, 90f, 90f, p)
        }
    }

    /** [textColor] text with a thick [outline], shrunk until it fits [box]. */
    private fun drawCaption(
        c: Canvas, text: String, typeface: Typeface, box: RectF,
        textColor: Int, outline: Int, startSize: Float = 110f, shadow: Boolean = false,
    ) {
        val width = box.width().toInt()
        var size = startSize
        var fill: StaticLayout
        var stroke: StaticLayout
        while (true) {
            val fp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = size; color = textColor }
            val sp = TextPaint(fp).apply {
                style = Paint.Style.STROKE; strokeWidth = size / 5f; strokeJoin = Paint.Join.ROUND; color = outline
                if (shadow) setShadowLayer(8f, 0f, 4f, 0x55000000)
            }
            fill = layout(text, fp, width)
            stroke = layout(text, sp, width)
            val widest = (0 until fill.lineCount).maxOfOrNull { fill.getLineWidth(it) } ?: 0f
            if ((fill.height <= box.height() && widest <= width) || size <= 28f) break
            size -= 6f
        }
        c.save()
        c.translate(box.left, box.centerY() - fill.height / 2f)
        stroke.draw(c)
        fill.draw(c)
        c.restore()
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 0.95f)
                .setIncludePad(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width, Layout.Alignment.ALIGN_CENTER, 0.95f, 0f, true)
        }

    /** WebP under WhatsApp's 100 KB limit for static stickers. */
    fun toWebp(bmp: Bitmap): ByteArray {
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        var q = 90
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(format, q, out)
            if (out.size() < MAX_BYTES || q <= 30) return out.toByteArray()
            q -= 10
        }
    }

    /** Motion applied to a finished sticker to make an animated one. */
    enum class Effect { NONE, PULSE, BOUNCE, SHAKE, RAINBOW }

    const val EFFECT_FRAMES = 16
    const val EFFECT_FRAME_MS = 70

    /** [EFFECT_FRAMES] frames of [sticker] moving with [effect], one smooth loop. */
    fun effectFrames(sticker: Bitmap, effect: Effect): List<Bitmap> = (0 until EFFECT_FRAMES).map { i ->
        val t = i.toFloat() / EFFECT_FRAMES
        val wave = sin(2 * Math.PI * t).toFloat()
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val m = Matrix()
        val mid = SIZE / 2f
        // shrink a little so the motion never leaves the 512x512 canvas
        m.setScale(0.88f, 0.88f, mid, mid)
        when (effect) {
            Effect.PULSE -> m.postScale(1f + 0.07f * wave, 1f + 0.07f * wave, mid, mid)
            Effect.BOUNCE -> m.postTranslate(0f, -26f * kotlin.math.abs(sin(Math.PI * t).toFloat()) + 13f)
            Effect.SHAKE -> m.postRotate(7f * wave, mid, mid)
            Effect.RAINBOW -> p.colorFilter = android.graphics.ColorMatrixColorFilter(hueRotation(360f * t))
            Effect.NONE -> {}
        }
        c.drawBitmap(sticker, m, p)
        out
    }

    /** Standard hue-rotation colour matrix (keeps white and black unchanged). */
    private fun hueRotation(degrees: Float): android.graphics.ColorMatrix {
        val r = Math.toRadians(degrees.toDouble())
        val cosA = cos(r).toFloat()
        val sinA = sin(r).toFloat()
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        return android.graphics.ColorMatrix(floatArrayOf(
            lr + cosA * (1 - lr) + sinA * (-lr), lg + cosA * (-lg) + sinA * (-lg), lb + cosA * (-lb) + sinA * (1 - lb), 0f, 0f,
            lr + cosA * (-lr) + sinA * 0.143f, lg + cosA * (1 - lg) + sinA * 0.140f, lb + cosA * (-lb) + sinA * (-0.283f), 0f, 0f,
            lr + cosA * (-lr) + sinA * (-(1 - lr)), lg + cosA * (-lg) + sinA * lg, lb + cosA * (1 - lb) + sinA * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    /** 96x96 PNG tray icon (WhatsApp limit 50 KB). */
    fun toTrayPng(bmp: Bitmap): ByteArray {
        val small = Bitmap.createScaledBitmap(bmp, TRAY, TRAY, true)
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}
