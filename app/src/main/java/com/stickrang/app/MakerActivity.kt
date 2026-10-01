package com.stickrang.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.concurrent.Executors

/**
 * "Sticker Banao": text stickers (Roman Urdu / Urdu / English), photo stickers, background-free
 * cut-outs, hand-edited photos, animated stickers from GIF/video, and animation effects.
 */
class MakerActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var preview: ImageView
    private lateinit var input: EditText
    private lateinit var latinFont: Typeface
    private lateinit var urduFont: Typeface

    // Sources, most specific first: clip (GIF/video) > edited photo > cut-out > photo > plain text.
    private var photo: Bitmap? = null
    /** The photo's subject with the background removed. */
    private var cutout: Bitmap? = null
    /** 512x512 result of the hand editor; [editedTransparent] when parts were erased. */
    private var edited: Bitmap? = null
    private var editedTransparent = false
    private var clip: MediaFrames.Clip? = null
    private var clipCutouts: List<Bitmap>? = null
    private var clipBounds: Rect? = null

    private var color = COLORS[1]
    private var urdu = false
    private var shape = StickerRenderer.Shape.CIRCLE
    private var effect = StickerRenderer.Effect.NONE
    private var busy = false
    /** Set by the "Cut-out" shortcut: remove the background as soon as the photo loads. */
    private var autoCutout = false

    /** What will be saved: one frame for a static sticker, several for an animated one. */
    private var frames: List<Bitmap> = emptyList()
    private var frameMs = StickerRenderer.EFFECT_FRAME_MS
    private var generation = 0
    private var shown = 0
    private val animate = object : Runnable {
        override fun run() {
            if (frames.size < 2) return
            shown = (shown + 1) % frames.size
            preview.setImageBitmap(frames[shown])
            main.postDelayed(this, frameMs.toLong())
        }
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) load(uri) }
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) load(uri) }
    private val editPhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val transparent = res.data?.getBooleanExtra(EditorActivity.EXTRA_TRANSPARENT, false) ?: false
        io.execute {
            val bmp = BitmapFactory.decodeFile(EditorActivity.resultFile(this).path)
            runOnUiThread { if (bmp != null) { edited = bmp; editedTransparent = transparent; render() } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maker)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        latinFont = ResourcesCompat.getFont(this, R.font.lilita_one) ?: Typeface.DEFAULT_BOLD
        urduFont = ResourcesCompat.getFont(this, R.font.noto_nastaliq_urdu) ?: Typeface.DEFAULT_BOLD
        preview = findViewById(R.id.preview)
        input = findViewById(R.id.text)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })

        val colors = findViewById<ChipGroup>(R.id.colors)
        COLORS.forEachIndexed { i, c ->
            colors.addView(Chip(this).apply {
                text = " "
                isCheckable = true
                isChecked = i == 1
                chipStrokeWidth = 2f
                chipStrokeColor = android.content.res.ColorStateList.valueOf(Color.LTGRAY)
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(c)
                setOnClickListener { color = c; render() }
            })
        }
        colors.isSingleSelection = true

        val effects = findViewById<ChipGroup>(R.id.effects)
        EFFECTS.forEachIndexed { i, (e, label) ->
            effects.addView(Chip(this).apply {
                setText(label)
                isCheckable = true
                isChecked = i == 0
                setOnClickListener { effect = e; render() }
            })
        }
        effects.isSingleSelection = true

        findViewById<MaterialButtonToggleGroup>(R.id.font_toggle).addOnButtonCheckedListener { _, id, checked ->
            if (checked) { urdu = id == R.id.font_urdu; render() }
        }
        findViewById<MaterialButtonToggleGroup>(R.id.shape_toggle).addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                shape = when (id) {
                    R.id.shape_circle -> StickerRenderer.Shape.CIRCLE
                    R.id.shape_rounded -> StickerRenderer.Shape.ROUNDED
                    else -> StickerRenderer.Shape.NONE
                }
                render()
            }
        }
        findViewById<MaterialButton>(R.id.pick_photo).setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<MaterialButton>(R.id.pick_media).setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        findViewById<MaterialButton>(R.id.clear_photo).setOnClickListener { clearSources(); render() }
        findViewById<MaterialButton>(R.id.remove_bg).setOnClickListener { toggleBackground() }
        findViewById<MaterialButton>(R.id.edit_photo).setOnClickListener { openEditor() }
        findViewById<MaterialButton>(R.id.save).setOnClickListener { save() }
        render()
        if (savedInstanceState == null) startMode(intent.getStringExtra(EXTRA_MODE))
    }

    /** Home-screen shortcuts open the maker ready for one kind of sticker. */
    private fun startMode(mode: String?) {
        when (mode) {
            MODE_CUTOUT -> { autoCutout = true; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            MODE_PHOTO -> pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            MODE_MEDIA -> pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            MODE_TEXT -> {
                findViewById<MaterialButtonToggleGroup>(R.id.shape_toggle).check(R.id.shape_text)
                input.requestFocus()
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun clearSources() {
        photo = null; cutout = null; edited = null; clip = null; clipCutouts = null; clipBounds = null
    }

    /** Photos go to the photo flow; GIFs and videos become animated stickers. */
    private fun load(uri: Uri) {
        setBusy(true, getString(R.string.loading))
        io.execute {
            val animated = MediaFrames.isAnimated(this, uri)
            val c = if (animated) MediaFrames.load(this, uri) else null
            val bmp = if (!animated) decodeScaled(uri) else null
            runOnUiThread {
                setBusy(false)
                when {
                    animated && c == null -> Toast.makeText(this, R.string.media_failed, Toast.LENGTH_LONG).show()
                    c != null && c.frames.size == 1 -> { clearSources(); photo = c.frames[0] } // single-frame GIF
                    c != null -> { clearSources(); clip = c }
                    bmp != null -> { clearSources(); photo = bmp }
                    else -> Toast.makeText(this, R.string.media_failed, Toast.LENGTH_LONG).show()
                }
                render()
                if (autoCutout && photo != null) { autoCutout = false; toggleBackground() }
            }
        }
    }

    private fun setBusy(b: Boolean, message: String? = null) {
        busy = b
        findViewById<View>(R.id.progress).visibility = if (b) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.status).apply {
            text = message
            visibility = if (b && message != null) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.save).isEnabled = !b
        findViewById<View>(R.id.remove_bg).isEnabled = !b
        findViewById<View>(R.id.pick_photo).isEnabled = !b
        findViewById<View>(R.id.pick_media).isEnabled = !b
    }

    private fun render() {
        val text = input.text.toString().trim()
        val tf = if (urdu) urduFont else latinFont
        val hasPhoto = photo != null || clip != null
        val hasCutout = cutout != null || clipCutouts != null || (edited != null && editedTransparent)
        findViewById<View>(R.id.photo_tools).visibility = if (hasPhoto) View.VISIBLE else View.GONE
        findViewById<View>(R.id.edit_photo).visibility = if (photo != null) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.remove_bg).setText(if (hasCutout) R.string.restore_bg else R.string.remove_bg)
        findViewById<TextView>(R.id.colour_label).setText(
            if (hasPhoto || shape == StickerRenderer.Shape.NONE) R.string.text_colour else R.string.colour
        )
        findViewById<View>(R.id.shape_toggle).visibility = if (hasCutout) View.GONE else View.VISIBLE
        // GIFs and videos already move; effects are for still stickers.
        findViewById<View>(R.id.effect_row).visibility = if (clip != null) View.GONE else View.VISIBLE

        val c = clip
        val gen = ++generation
        val snapshot = Snapshot(text, tf, color, shape, effect, photo, cutout, edited, editedTransparent, c, clipCutouts, clipBounds)
        val placeholder = getString(R.string.maker_placeholder)
        io.execute {
            val out = snapshot.frames(placeholder)
            val ms = c?.frameMs ?: StickerRenderer.EFFECT_FRAME_MS
            runOnUiThread { if (gen == generation) show(out, ms) }
        }
    }

    private fun show(list: List<Bitmap>, ms: Int) {
        main.removeCallbacks(animate)
        frames = list
        frameMs = ms
        shown = 0
        preview.setImageBitmap(list.firstOrNull())
        if (list.size > 1) main.postDelayed(animate, ms.toLong())
    }

    /** Immutable copy of the editor state so frames can be drawn off the main thread. */
    private class Snapshot(
        val text: String, val tf: Typeface, val color: Int, val shape: StickerRenderer.Shape, val effect: StickerRenderer.Effect,
        val photo: Bitmap?, val cutout: Bitmap?, val edited: Bitmap?, val editedTransparent: Boolean,
        val clip: MediaFrames.Clip?, val clipCutouts: List<Bitmap>?, val clipBounds: Rect?,
    ) {
        fun frames(placeholder: String): List<Bitmap> {
            if (clip != null) {
                val cuts = clipCutouts
                return if (cuts != null) cuts.map { StickerRenderer.cutoutSticker(it, text, tf, color, clipBounds) }
                else clip.frames.map { StickerRenderer.photoSticker(it, text, tf, shape, color) }
            }
            val base = when {
                edited != null && editedTransparent -> StickerRenderer.cutoutSticker(edited, text, tf, color)
                edited != null -> StickerRenderer.photoSticker(edited, text, tf, shape, color)
                cutout != null -> StickerRenderer.cutoutSticker(cutout, text, tf, color)
                photo != null -> StickerRenderer.photoSticker(photo, text, tf, shape, color)
                else -> StickerRenderer.textSticker(text.ifEmpty { placeholder }, color, tf, shape)
            }
            return if (effect == StickerRenderer.Effect.NONE) listOf(base) else StickerRenderer.effectFrames(base, effect)
        }
    }

    private fun toggleBackground() {
        if (cutout != null || clipCutouts != null || (edited != null && editedTransparent)) {
            cutout = null; clipCutouts = null; clipBounds = null
            if (editedTransparent) edited = null
            render(); return
        }
        val c = clip
        if (c != null) {
            setBusy(true, getString(R.string.removing_bg_frames, 1, c.frames.size))
            BackgroundRemover.removeAll(c.frames, { i ->
                findViewById<TextView>(R.id.status).text = getString(R.string.removing_bg_frames, i + 1, c.frames.size)
            }) { list ->
                setBusy(false)
                if (clip !== c) return@removeAll // user picked something else meanwhile
                val bounds = list?.let { unionBounds(it) }
                when {
                    list == null -> Toast.makeText(this, R.string.remove_bg_failed, Toast.LENGTH_LONG).show()
                    bounds == null -> Toast.makeText(this, R.string.remove_bg_none, Toast.LENGTH_LONG).show()
                    else -> { clipCutouts = list; clipBounds = bounds }
                }
                render()
            }
            return
        }
        val p = photo ?: return
        setBusy(true, getString(R.string.removing_bg))
        BackgroundRemover.remove(p) { fg ->
            setBusy(false)
            if (photo !== p) return@remove // user picked another photo meanwhile
            when {
                fg == null -> Toast.makeText(this, R.string.remove_bg_failed, Toast.LENGTH_LONG).show()
                StickerRenderer.opaqueBounds(fg) == null -> Toast.makeText(this, R.string.remove_bg_none, Toast.LENGTH_LONG).show()
                else -> { cutout = fg; edited = null }
            }
            render()
        }
    }

    private fun unionBounds(list: List<Bitmap>): Rect? {
        var u: Rect? = null
        for (b in list) {
            val r = StickerRenderer.opaqueBounds(b) ?: continue
            u = u?.apply { union(r) } ?: Rect(r)
        }
        return u
    }

    /** Opens the crop / rotate / eraser screen, starting from the ML Kit cut-out if there is one. */
    private fun openEditor() {
        val p = photo ?: return
        val mask = cutout
        setBusy(true)
        io.execute {
            EditorActivity.photoFile(this).outputStream().use { p.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val mf = EditorActivity.maskFile(this)
            if (mask != null) mf.outputStream().use { mask.compress(Bitmap.CompressFormat.PNG, 100, it) } else mf.delete()
            runOnUiThread {
                setBusy(false)
                editPhoto.launch(Intent(this, EditorActivity::class.java))
            }
        }
    }

    private fun save() {
        val list = frames
        if (list.isEmpty() || busy) return
        if (photo == null && clip == null && input.text.isNullOrBlank()) {
            input.error = getString(R.string.maker_need_text); return
        }
        val label = input.text.toString().trim()
        val animated = list.size > 1
        val ms = frameMs
        val emoji = when {
            clip != null -> "🎬"
            photo != null -> "📸"
            animated -> "✨"
            else -> "💬"
        }
        setBusy(true, getString(R.string.saving))
        io.execute {
            val webp = if (animated) AnimatedWebp.encode(list, ms) else StickerRenderer.toWebp(list[0])
            val pack = StickerRepository.addCustomSticker(
                this, webp, StickerRenderer.toTrayPng(list[0]), emoji = emoji, label = label, animated = animated,
            )
            runOnUiThread {
                setBusy(false)
                val left = StickerPack.MIN_STICKERS - pack.stickers.size
                val msg = if (left > 0) getString(R.string.saved_need_more, left) else getString(R.string.saved_ready, pack.name)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                clearSources()
                input.setText("")
                render()
            }
        }
    }

    private fun decodeScaled(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1024 && bounds.outHeight / (sample * 2) >= 1024) sample *= 2
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_CUTOUT = "cutout"
        const val MODE_TEXT = "text"
        const val MODE_MEDIA = "media"
        const val MODE_PHOTO = "photo"

        private val COLORS = listOf(
            Color.WHITE, Color.parseColor("#6C2BD9"), Color.parseColor("#FF5A5F"), Color.parseColor("#FFB400"),
            Color.parseColor("#7B61FF"), Color.parseColor("#1FA2FF"), Color.parseColor("#E91E63"),
            Color.parseColor("#FF7A00"), Color.parseColor("#222222"),
        )
        private val EFFECTS = listOf(
            StickerRenderer.Effect.NONE to R.string.effect_none,
            StickerRenderer.Effect.PULSE to R.string.effect_pulse,
            StickerRenderer.Effect.BOUNCE to R.string.effect_bounce,
            StickerRenderer.Effect.SHAKE to R.string.effect_shake,
            StickerRenderer.Effect.RAINBOW to R.string.effect_rainbow,
        )
    }
}
