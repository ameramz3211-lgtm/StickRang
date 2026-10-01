package com.stickrang.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.io.File
import java.util.concurrent.Executors

/**
 * Crop / zoom / rotate and hand-eraser for photo stickers.
 * Input: [photoFile] (+ optional [maskFile], an ML Kit cut-out used as the starting mask).
 * Output: a 512x512 PNG in [resultFile]; transparent where the user erased.
 */
class EditorActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var editor: EditorView
    private var photo: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        editor = findViewById(R.id.editor)
        editor.onChanged = { findViewById<View>(R.id.undo).isEnabled = editor.canUndo }

        val p = BitmapFactory.decodeFile(photoFile(this).path)
        if (p == null) { finish(); return }
        photo = p
        val mask = maskFile(this).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        editor.setPhoto(p, mask)
        editor.tool = if (mask != null) EditorView.Tool.ERASE else EditorView.Tool.MOVE
        findViewById<MaterialButtonToggleGroup>(R.id.tools).apply {
            check(if (mask != null) R.id.tool_erase else R.id.tool_move)
            addOnButtonCheckedListener { _, id, checked ->
                if (checked) editor.tool = when (id) {
                    R.id.tool_move -> EditorView.Tool.MOVE
                    R.id.tool_restore -> EditorView.Tool.RESTORE
                    else -> EditorView.Tool.ERASE
                }
            }
        }
        val density = resources.displayMetrics.density
        findViewById<SeekBar>(R.id.brush).apply {
            editor.brushPx = (10 + progress) * density
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { editor.brushPx = (10 + v) * density }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        findViewById<MaterialButton>(R.id.auto).setOnClickListener { autoRemove() }
        findViewById<MaterialButton>(R.id.rotate).setOnClickListener { editor.rotate90() }
        findViewById<MaterialButton>(R.id.undo).apply { isEnabled = false; setOnClickListener { editor.undo() } }
        findViewById<MaterialButton>(R.id.reset).setOnClickListener { editor.resetMask() }
        findViewById<MaterialButton>(R.id.done).setOnClickListener { done() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun autoRemove() {
        val p = photo ?: return
        val progress = findViewById<View>(R.id.progress)
        progress.visibility = View.VISIBLE
        findViewById<View>(R.id.auto).isEnabled = false
        BackgroundRemover.remove(p) { fg ->
            progress.visibility = View.GONE
            findViewById<View>(R.id.auto).isEnabled = true
            if (fg == null) {
                Toast.makeText(this, R.string.remove_bg_failed, Toast.LENGTH_LONG).show()
            } else {
                editor.applyMask(fg)
                findViewById<MaterialButtonToggleGroup>(R.id.tools).check(R.id.tool_erase)
            }
        }
    }

    private fun done() {
        val out = editor.export() ?: return
        val transparent = editor.hasTransparency(out)
        io.execute {
            resultFile(this).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            runOnUiThread {
                setResult(Activity.RESULT_OK, intent.putExtra(EXTRA_TRANSPARENT, transparent))
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_TRANSPARENT = "transparent"
        fun photoFile(a: android.content.Context) = File(a.cacheDir, "editor_photo.png")
        fun maskFile(a: android.content.Context) = File(a.cacheDir, "editor_mask.png")
        fun resultFile(a: android.content.Context) = File(a.cacheDir, "editor_result.png")
    }
}
