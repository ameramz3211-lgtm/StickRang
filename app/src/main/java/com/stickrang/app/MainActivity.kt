package com.stickrang.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/** Home: Create (maker shortcuts), Explore (bundled packs by category + search), My Stickers (own + imported packs). */
class MainActivity : AppCompatActivity() {

    private val explore = PackAdapter()
    private val mine = PackAdapter()
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var adder: PackAdder
    private var allPacks: List<StickerPack> = emptyList()
    private var added: Map<String, Boolean> = emptyMap()
    private var query = ""
    private var category = ""

    private val pickPack = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirmImport(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        adder = PackAdder(this) { refresh() }

        findViewById<RecyclerView>(R.id.packs).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = explore }
        findViewById<RecyclerView>(R.id.mine).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = mine }

        mapOf(
            R.id.create_cutout to MakerActivity.MODE_CUTOUT,
            R.id.create_text to MakerActivity.MODE_TEXT,
            R.id.create_media to MakerActivity.MODE_MEDIA,
            R.id.create_photo to MakerActivity.MODE_PHOTO,
        ).forEach { (id, mode) -> findViewById<View>(id).setOnClickListener { openMaker(mode) } }
        findViewById<MaterialButton>(R.id.mine_make).setOnClickListener { openMaker(MakerActivity.MODE_TEXT) }
        findViewById<MaterialButton>(R.id.mine_import).setOnClickListener { importFromFiles() }

        val chips = findViewById<ChipGroup>(R.id.categories)
        CATEGORIES.forEachIndexed { i, (key, label) ->
            chips.addView(Chip(this).apply {
                setText(label)
                isCheckable = true
                isChecked = i == 0
                setOnClickListener { category = key; show() }
            })
        }

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item -> selectTab(item.itemId); true }
        if (savedInstanceState == null) handleShared(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    private fun openMaker(mode: String) {
        startActivity(Intent(this, MakerActivity::class.java).putExtra(MakerActivity.EXTRA_MODE, mode))
    }

    private fun selectTab(id: Int) {
        findViewById<View>(R.id.create_tab).visibility = if (id == R.id.tab_create) View.VISIBLE else View.GONE
        findViewById<View>(R.id.explore_tab).visibility = if (id == R.id.tab_explore) View.VISIBLE else View.GONE
        findViewById<View>(R.id.mine_tab).visibility = if (id == R.id.tab_mine) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        io.execute {
            val packs = StickerRepository.allPacks(this)
            val added = packs.associate { it.identifier to WhatsApp.isPackAdded(this, it.identifier) }
            runOnUiThread { allPacks = packs; this.added = added; show() }
        }
    }

    private fun show() {
        val bundled = allPacks.filter { !it.isCustom && (category.isEmpty() || it.category == category) }
        val shown = StickerSearch.filter(bundled, query)
        explore.submit(shown, added)
        findViewById<View>(R.id.empty).visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        val own = allPacks.filter { it.isCustom }
        mine.submit(own, added)
        findViewById<View>(R.id.mine_empty).visibility = if (own.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        (menu.findItem(R.id.search).actionView as SearchView).apply {
            queryHint = getString(R.string.search_hint)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = false
                override fun onQueryTextChange(q: String?): Boolean {
                    query = q.orEmpty()
                    if (query.isNotBlank()) findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.tab_explore
                    show()
                    return true
                }
            })
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.import_pack -> { importFromFiles(); return true }
            R.id.credits -> {
                val text = assets.open("licenses/CREDITS.txt").bufferedReader().use { it.readText() }
                MaterialAlertDialogBuilder(this).setTitle(R.string.credits).setMessage(text)
                    .setPositiveButton(android.R.string.ok, null).show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun importFromFiles() = pickPack.launch(arrayOf("application/zip", "application/octet-stream"))

    /** A pack zip opened from WhatsApp, Files, etc. */
    private fun handleShared(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        confirmImport(uri)
    }

    private fun confirmImport(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_pack).setMessage(R.string.import_confirm)
            .setPositiveButton(R.string.add) { _, _ -> importPack(uri) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun importPack(uri: Uri) {
        io.execute {
            val result = try {
                val pack = contentResolver.openInputStream(uri)?.use { StickerRepository.importPack(this, it) }
                if (pack == null) getString(R.string.import_bad) else getString(R.string.import_ok, pack.name)
            } catch (e: StickerRepository.ImportException) {
                getString(e.reason)
            } catch (e: Exception) {
                getString(R.string.import_bad)
            }
            runOnUiThread {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.tab_mine
                refresh()
            }
        }
    }

    private inner class PackAdapter : RecyclerView.Adapter<PackAdapter.VH>() {
        private var packs: List<StickerPack> = emptyList()
        private var added: Map<String, Boolean> = emptyMap()

        fun submit(p: List<StickerPack>, a: Map<String, Boolean>) {
            packs = p; added = a
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tray: ImageView = v.findViewById(R.id.tray)
            val name: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.meta)
            val previews: LinearLayout = v.findViewById(R.id.previews)
            val add: MaterialButton = v.findViewById(R.id.add)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pack, parent, false))

        override fun getItemCount() = packs.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = packs[position]
            holder.name.text = p.name
            holder.meta.text = getString(R.string.pack_meta, p.publisher, p.stickers.size)
            if (p.stickers.isNotEmpty()) Images.load(holder.tray, p, p.trayImageFile, 96) else holder.tray.setImageResource(R.drawable.ic_add_sticker)
            val px = resources.getDimensionPixelSize(R.dimen.preview_size)
            for (i in 0 until holder.previews.childCount) {
                val iv = holder.previews.getChildAt(i) as ImageView
                val s = p.stickers.getOrNull(i)
                iv.visibility = if (s == null) View.INVISIBLE else View.VISIBLE
                if (s != null) Images.load(iv, p, s.imageFile, px)
            }
            val isAdded = added[p.identifier] == true
            holder.add.isEnabled = !isAdded
            holder.add.text = when {
                isAdded -> getString(R.string.added)
                !p.canBeAdded -> getString(R.string.need_more, StickerPack.MIN_STICKERS - p.stickers.size)
                else -> getString(R.string.add)
            }
            holder.add.setOnClickListener { adder.add(p) }
            holder.itemView.setOnClickListener {
                startActivity(Intent(this@MainActivity, PackDetailActivity::class.java).putExtra(PackDetailActivity.EXTRA_PACK_ID, p.identifier))
            }
        }
    }

    companion object {
        private val CATEGORIES = listOf(
            "" to R.string.cat_all,
            "funny" to R.string.cat_funny,
            "love" to R.string.cat_love,
            "greetings" to R.string.cat_greetings,
            "celebrations" to R.string.cat_celebrations,
            "desi" to R.string.cat_desi,
            "islamic" to R.string.cat_islamic,
        )
    }
}

/** Shared "Add to WhatsApp" flow with WhatsApp's result handling. */
class PackAdder(private val activity: AppCompatActivity, private val onDone: () -> Unit) {
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_CANCELED) {
            res.data?.getStringExtra("validation_error")?.let { err ->
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.add_failed).setMessage(err).setPositiveButton(android.R.string.ok, null).show()
            }
        }
        onDone()
    }

    fun add(pack: StickerPack) {
        if (!pack.canBeAdded) {
            Toast.makeText(activity, activity.getString(R.string.need_more_long, StickerPack.MIN_STICKERS), Toast.LENGTH_LONG).show()
            if (pack.isCustom) activity.startActivity(Intent(activity, MakerActivity::class.java))
            return
        }
        val intent = WhatsApp.addPackIntent(activity, pack)
        if (intent == null) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.no_whatsapp_title).setMessage(R.string.no_whatsapp)
                .setPositiveButton(R.string.install) { _, _ -> WhatsApp.openPlayStore(activity) }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        try {
            launcher.launch(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(activity, R.string.add_failed, Toast.LENGTH_LONG).show()
        }
    }
}
