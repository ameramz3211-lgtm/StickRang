package com.stickrang.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class PackDetailActivity : AppCompatActivity() {

    private lateinit var packId: String
    private var pack: StickerPack? = null
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var adder: PackAdder
    private lateinit var addButton: MaterialButton
    private val grid = GridAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pack_detail)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        packId = intent.getStringExtra(EXTRA_PACK_ID) ?: run { finish(); return }
        adder = PackAdder(this) { refresh() }
        addButton = findViewById(R.id.add)
        addButton.setOnClickListener { pack?.let { adder.add(it) } }
        findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(this@PackDetailActivity, 4)
            adapter = grid
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (pack?.isCustom == true) menuInflater.inflate(R.menu.pack, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> { finish(); return true }
            R.id.share_pack -> { pack?.let { share(it) }; return true }
            R.id.delete_pack -> {
                val p = pack ?: return true
                MaterialAlertDialogBuilder(this).setMessage(R.string.delete_pack_confirm)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        io.execute { StickerRepository.deleteCustomPack(this, p.identifier); runOnUiThread { finish() } }
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /** Sends the pack as a zip; friends open it with StickRang to add it to their WhatsApp. */
    private fun share(p: StickerPack) {
        io.execute {
            val dir = java.io.File(cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val safe = p.name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "pack" }
            val file = java.io.File(dir, "StickRang-$safe.zip")
            StickerRepository.exportPack(this, p, file)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, getString(R.string.share_via)))
            }
        }
    }

    private fun refresh() {
        io.execute {
            val p = StickerRepository.findPack(this, packId)
            val added = p != null && WhatsApp.isPackAdded(this, p.identifier)
            runOnUiThread {
                if (p == null) { finish(); return@runOnUiThread }
                val first = pack == null
                pack = p
                if (first) invalidateOptionsMenu()
                title = p.name
                findViewById<TextView>(R.id.meta).text = getString(R.string.pack_meta, p.publisher, p.stickers.size)
                addButton.isEnabled = !added
                addButton.text = when {
                    added -> getString(R.string.added)
                    !p.canBeAdded -> getString(R.string.make_more)
                    else -> getString(R.string.add_to_whatsapp)
                }
                if (!p.canBeAdded && p.isCustom) {
                    addButton.isEnabled = true
                    addButton.setOnClickListener { startActivity(Intent(this, MakerActivity::class.java)) }
                } else {
                    addButton.setOnClickListener { adder.add(p) }
                }
                grid.submit(p)
            }
        }
    }

    private inner class GridAdapter : RecyclerView.Adapter<GridAdapter.VH>() {
        private var p: StickerPack? = null
        fun submit(pack: StickerPack) { p = pack; @Suppress("NotifyDataSetChanged") notifyDataSetChanged() }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) { val img: ImageView = v as ImageView }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_sticker, parent, false))

        override fun getItemCount() = p?.stickers?.size ?: 0

        override fun onBindViewHolder(h: VH, position: Int) {
            val pack = p ?: return
            val s = pack.stickers[position]
            if (pack.animated) Images.loadAnimated(h.img, pack, s.imageFile) else Images.load(h.img, pack, s.imageFile, 256)
            h.img.contentDescription = s.accessibilityText
            h.img.setOnLongClickListener {
                if (!pack.isCustom) return@setOnLongClickListener false
                MaterialAlertDialogBuilder(this@PackDetailActivity)
                    .setMessage(R.string.delete_sticker)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        io.execute { StickerRepository.deleteCustomSticker(this@PackDetailActivity, pack.identifier, s.imageFile); refresh() }
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
                true
            }
        }
    }

    companion object { const val EXTRA_PACK_ID = "pack_id" }
}
