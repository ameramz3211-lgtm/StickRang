package com.stickrang.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Photo editor canvas for stickers:
 * - two fingers: move, zoom and rotate the photo inside the square sticker frame (crop)
 * - one finger: erase or restore parts of the photo with a round brush
 * Erased parts stay faintly visible so they are easy to restore.
 */
class EditorView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Tool { MOVE, ERASE, RESTORE }

    var tool = Tool.ERASE
    /** Brush diameter in screen pixels. */
    var brushPx = 60f
    var onChanged: (() -> Unit)? = null

    private var photo: Bitmap? = null
    private var mask: Bitmap? = null // ALPHA_8, same size as the photo; 0 = erased
    private var maskCanvas: Canvas? = null
    private val image = Matrix() // photo -> view
    private val inverse = Matrix()
    private val frame = RectF()
    private val undo = ArrayDeque<Bitmap>()

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val ghostPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 70 }
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE }
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.BLACK
    }
    private val checker: Paint by lazy {
        val tile = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        Canvas(tile).apply {
            drawColor(0xFFFFFFFF.toInt())
            val p = Paint().apply { color = 0xFFE3E8E5.toInt() }
            drawRect(0f, 0f, 24f, 24f, p); drawRect(24f, 24f, 48f, 48f, p)
        }
        Paint().apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    /** Starts editing [src]; [initialMask] (e.g. an ML Kit cut-out) supplies the starting alpha. */
    fun setPhoto(src: Bitmap, initialMask: Bitmap? = null) {
        photo = src
        val m = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ALPHA_8)
        maskCanvas = Canvas(m)
        if (initialMask != null && initialMask.width == src.width && initialMask.height == src.height) {
            maskCanvas?.drawBitmap(initialMask, 0f, 0f, null)
        } else {
            m.eraseColor(Color.BLACK) // fully opaque
        }
        mask = m
        undo.clear()
        fitToFrame()
        invalidate()
    }

    /** Replaces the whole mask, e.g. after automatic background removal (undoable). */
    fun applyMask(fromAlpha: Bitmap) {
        val c = maskCanvas ?: return
        pushUndo()
        mask?.eraseColor(Color.TRANSPARENT)
        c.drawBitmap(fromAlpha, 0f, 0f, null)
        invalidate(); onChanged?.invoke()
    }

    fun resetMask() {
        pushUndo()
        mask?.eraseColor(Color.BLACK)
        invalidate(); onChanged?.invoke()
    }

    val canUndo get() = undo.isNotEmpty()

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        mask?.eraseColor(Color.TRANSPARENT)
        maskCanvas?.drawBitmap(prev, 0f, 0f, null)
        invalidate(); onChanged?.invoke()
    }

    fun rotate90() {
        image.postRotate(90f, frame.centerX(), frame.centerY())
        invalidate()
    }

    /** The square frame's content as a 512x512 bitmap; erased parts are transparent. */
    fun export(): Bitmap? {
        val p = photo ?: return null
        val out = Bitmap.createBitmap(StickerRenderer.SIZE, StickerRenderer.SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val m = Matrix(image)
        m.postTranslate(-frame.left, -frame.top)
        m.postScale(StickerRenderer.SIZE / frame.width(), StickerRenderer.SIZE / frame.height())
        drawMasked(c, p, m, ghost = false)
        return out
    }

    /** True when some of the photo inside the frame was erased. */
    fun hasTransparency(b: Bitmap): Boolean {
        val step = 8
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            if (b.getPixel(x, y) ushr 24 < 250) return true
        }
        return false
    }

    private fun pushUndo() {
        val m = mask ?: return
        undo.addLast(m.copy(Bitmap.Config.ALPHA_8, false))
        while (undo.size > 12) undo.removeFirst()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val side = min(w, h) * 0.86f
        frame.set((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
        fitToFrame()
    }

    private fun fitToFrame() {
        val p = photo ?: return
        if (frame.isEmpty) return
        val s = maxOf(frame.width() / p.width, frame.height() / p.height)
        image.reset()
        image.postScale(s, s)
        image.postTranslate(frame.centerX() - p.width * s / 2, frame.centerY() - p.height * s / 2)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(frame, checker)
        val p = photo ?: return
        drawMasked(canvas, p, image, ghost = true)
        // dim everything outside the sticker frame
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, shade)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), shade)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, shade)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, shade)
        canvas.drawRect(frame, framePaint)
        if (touching && tool != Tool.MOVE && pointers == 1) canvas.drawCircle(lastX, lastY, brushPx / 2, cursorPaint)
    }

    private fun drawMasked(canvas: Canvas, p: Bitmap, m: Matrix, ghost: Boolean) {
        if (ghost) canvas.drawBitmap(p, m, ghostPaint)
        val layer = canvas.saveLayer(null, null)
        canvas.drawBitmap(p, m, bmpPaint)
        mask?.let { canvas.drawBitmap(it, m, maskPaint) }
        canvas.restoreToCount(layer)
    }

    // --- touch -------------------------------------------------------------------------

    private var touching = false
    private var pointers = 0
    private var lastX = 0f
    private var lastY = 0f
    private var painting = false
    private var gestureDist = 0f
    private var gestureAngle = 0f
    private var gestureX = 0f
    private var gestureY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        pointers = e.pointerCount
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                lastX = e.x; lastY = e.y
                if (tool != Tool.MOVE) { pushUndo(); painting = true; paintTo(e.x, e.y, e.x, e.y) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // a second finger turns the stroke into a move/zoom/rotate gesture
                if (painting) { painting = false; undo() }
                startGesture(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    moveGesture(e)
                } else if (tool == Tool.MOVE) {
                    image.postTranslate(e.x - lastX, e.y - lastY)
                } else if (painting) {
                    paintTo(lastX, lastY, e.x, e.y)
                }
                lastX = e.x; lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val keep = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(keep); lastY = e.getY(keep)
                if (e.pointerCount > 2) startGesture(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                if (painting) { painting = false; onChanged?.invoke() }
                invalidate()
            }
        }
        return true
    }

    private fun startGesture(e: MotionEvent) {
        gestureDist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
        gestureAngle = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
        gestureX = (e.getX(0) + e.getX(1)) / 2
        gestureY = (e.getY(0) + e.getY(1)) / 2
    }

    private fun moveGesture(e: MotionEvent) {
        val d = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
        val a = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
        val cx = (e.getX(0) + e.getX(1)) / 2
        val cy = (e.getY(0) + e.getY(1)) / 2
        image.postTranslate(cx - gestureX, cy - gestureY)
        if (gestureDist > 10f && d > 10f) image.postScale(d / gestureDist, d / gestureDist, cx, cy)
        image.postRotate(a - gestureAngle, cx, cy)
        gestureDist = d; gestureAngle = a; gestureX = cx; gestureY = cy
    }

    private fun paintTo(x0: Float, y0: Float, x1: Float, y1: Float) {
        val c = maskCanvas ?: return
        image.invert(inverse)
        val pts = floatArrayOf(x0, y0, x1, y1)
        inverse.mapPoints(pts)
        brush.strokeWidth = inverse.mapRadius(brushPx / 2) * 2
        brush.xfermode = if (tool == Tool.ERASE) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
        if (x0 == x1 && y0 == y1) {
            brush.style = Paint.Style.FILL
            c.drawCircle(pts[0], pts[1], brush.strokeWidth / 2, brush)
            brush.style = Paint.Style.STROKE
        } else {
            c.drawLine(pts[0], pts[1], pts[2], pts[3], brush)
        }
    }
}
