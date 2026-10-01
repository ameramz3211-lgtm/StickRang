package com.stickrang.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bundled packs live in assets/<id>/ (listed in assets/contents.json).
 * Packs made in the app live in filesDir/custom/<id>/ (listed in filesDir/custom/packs.json).
 */
object StickerRepository {
    const val CUSTOM_DIR = "custom"
    private const val CUSTOM_PREFIX = "my_stickers_"
    private const val ANIMATED_PREFIX = "my_animated_"
    private const val IMPORT_PREFIX = "imported_"

    @Volatile private var bundled: List<StickerPack>? = null
    var playStoreLink: String = ""
        private set

    fun allPacks(context: Context): List<StickerPack> = customPacks(context) + bundledPacks(context)

    fun findPack(context: Context, id: String): StickerPack? = allPacks(context).firstOrNull { it.identifier == id }

    fun bundledPacks(context: Context): List<StickerPack> {
        bundled?.let { return it }
        val json = context.assets.open("contents.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        playStoreLink = root.optString("android_play_store_link")
        return parsePacks(root.getJSONArray("sticker_packs"), isCustom = false).also { bundled = it }
    }

    fun customPacks(context: Context): List<StickerPack> {
        val file = customIndex(context)
        if (!file.exists()) return emptyList()
        return parsePacks(JSONObject(file.readText()).getJSONArray("sticker_packs"), isCustom = true)
    }

    fun customDir(context: Context): File = File(context.filesDir, CUSTOM_DIR).apply { mkdirs() }

    private fun customIndex(context: Context) = File(customDir(context), "packs.json")

    /**
     * Adds a finished 512x512 WebP to the newest custom pack of the same kind that still has room.
     * WhatsApp needs every sticker in a pack to be all static or all animated, so they get separate packs.
     */
    @Synchronized
    fun addCustomSticker(
        context: Context, webp: ByteArray, tray: ByteArray, emoji: String, label: String, animated: Boolean = false,
    ): StickerPack {
        val packs = customPacks(context).toMutableList()
        val prefix = if (animated) ANIMATED_PREFIX else CUSTOM_PREFIX
        var pack = packs.lastOrNull { it.identifier.startsWith(prefix) }?.takeIf { it.stickers.size < StickerPack.MAX_STICKERS }
        if (pack == null) {
            val n = nextNumber(packs, prefix)
            pack = newPack(
                context, "$prefix$n",
                context.getString(if (animated) R.string.my_animated_name else R.string.my_stickers_name, n), animated,
            )
            packs += pack
        }
        val dir = File(customDir(context), pack.identifier).apply { mkdirs() }
        val fileName = "s_${System.currentTimeMillis()}.webp"
        File(dir, fileName).writeBytes(webp)
        if (pack.stickers.isEmpty()) File(dir, "tray.png").writeBytes(tray)
        val updated = pack.copy(
            stickers = pack.stickers + Sticker(fileName, listOf(emoji), label),
            // WhatsApp only re-reads a pack's images when this version changes.
            imageDataVersion = ((pack.imageDataVersion.toIntOrNull() ?: 0) + 1).toString(),
        )
        packs[packs.indexOfFirst { it.identifier == pack.identifier }] = updated
        saveCustom(context, packs)
        return updated
    }

    private fun nextNumber(packs: List<StickerPack>, prefix: String) =
        (packs.mapNotNull { it.identifier.removePrefix(prefix).takeIf { _ -> it.identifier.startsWith(prefix) }?.toIntOrNull() }.maxOrNull() ?: 0) + 1

    private fun newPack(context: Context, id: String, name: String, animated: Boolean) = StickerPack(
        identifier = id,
        name = name,
        publisher = context.getString(R.string.app_name),
        trayImageFile = "tray.png",
        imageDataVersion = "0",
        avoidCache = false,
        animated = animated,
        publisherEmail = "", publisherWebsite = "", privacyPolicyWebsite = "", licenseAgreementWebsite = "",
        stickers = emptyList(),
        isCustom = true,
    )

    @Synchronized
    fun deleteCustomPack(context: Context, packId: String) {
        val packs = customPacks(context).filterNot { it.identifier == packId }
        File(customDir(context), packId).deleteRecursively()
        saveCustom(context, packs)
    }

    /** Zips a custom pack (pack.json + images) so it can be sent to friends and imported in their app. */
    fun exportPack(context: Context, pack: StickerPack, out: File) {
        val dir = File(customDir(context), pack.identifier)
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(PACK_JSON))
            zip.write(toJson(pack).put("format", PACK_FORMAT).toString().toByteArray())
            zip.closeEntry()
            (listOf(pack.trayImageFile) + pack.stickers.map { it.imageFile }).distinct().forEach { name ->
                val f = File(dir, name)
                if (f.exists()) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    class ImportException(val reason: Int) : Exception()

    /**
     * Imports a pack shared from this app. Everything is checked against WhatsApp's limits
     * (file names, sizes, 512x512 images, 3-30 stickers) before anything is saved.
     */
    @Synchronized
    fun importPack(context: Context, input: java.io.InputStream): StickerPack {
        val files = HashMap<String, ByteArray>()
        var json: JSONObject? = null
        java.util.zip.ZipInputStream(input.buffered()).use { zip ->
            var total = 0L
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                val data = readLimited(zip, MAX_IMPORT_FILE)
                total += data.size
                if (total > MAX_IMPORT_TOTAL || files.size > StickerPack.MAX_STICKERS + 2) throw ImportException(R.string.import_bad)
                if (name == PACK_JSON) json = JSONObject(String(data)) else if (SAFE_NAME.matches(name)) files[name] = data
            }
        }
        val o = json ?: throw ImportException(R.string.import_bad)
        o.put("identifier", "tmp")
        val src = parsePacks(JSONArray().put(o), isCustom = true).first()
        val stickers = src.stickers.filter { SAFE_NAME.matches(it.imageFile) && files.containsKey(it.imageFile) }.distinctBy { it.imageFile }
        if (stickers.size !in StickerPack.MIN_STICKERS..StickerPack.MAX_STICKERS) throw ImportException(R.string.import_bad)
        val limit = if (src.animated) 500 * 1024 else 100 * 1024
        for (s in stickers) {
            val b = files.getValue(s.imageFile)
            if (b.size > limit || !isImage(b, StickerRenderer.SIZE)) throw ImportException(R.string.import_bad)
        }
        val tray = files[src.trayImageFile]?.takeIf { it.size <= 50 * 1024 && isImage(it, StickerRenderer.TRAY) }
            ?: throw ImportException(R.string.import_bad)
        val packs = customPacks(context).toMutableList()
        val id = "$IMPORT_PREFIX${nextNumber(packs, IMPORT_PREFIX)}"
        val dir = File(customDir(context), id).apply { mkdirs() }
        File(dir, "tray.png").writeBytes(tray)
        stickers.forEach { File(dir, it.imageFile).writeBytes(files.getValue(it.imageFile)) }
        val pack = newPack(context, id, src.name.take(120).ifBlank { id }, src.animated).copy(
            publisher = src.publisher.take(120).ifBlank { context.getString(R.string.app_name) },
            stickers = stickers.map { it.copy(emojis = it.emojis.take(3), accessibilityText = it.accessibilityText.take(125)) },
            imageDataVersion = "1",
        )
        packs += pack
        saveCustom(context, packs)
        return pack
    }

    private fun readLimited(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw ImportException(R.string.import_bad)
        }
        return out.toByteArray()
    }

    private fun isImage(bytes: ByteArray, size: Int): Boolean {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth == size && o.outHeight == size
    }

    private const val PACK_JSON = "pack.json"
    private const val PACK_FORMAT = "stickrang-pack-1"
    private const val MAX_IMPORT_FILE = 600 * 1024
    private const val MAX_IMPORT_TOTAL = 16L * 1024 * 1024
    /** Only plain file names: no folders, so a crafted zip can't write outside the pack folder. */
    private val SAFE_NAME = Regex("[A-Za-z0-9_\\-]{1,64}\\.(webp|png)")

    @Synchronized
    fun deleteCustomSticker(context: Context, packId: String, fileName: String) {
        val packs = customPacks(context).toMutableList()
        val i = packs.indexOfFirst { it.identifier == packId }
        if (i < 0) return
        File(File(customDir(context), packId), fileName).delete()
        val p = packs[i]
        packs[i] = p.copy(
            stickers = p.stickers.filterNot { it.imageFile == fileName },
            imageDataVersion = ((p.imageDataVersion.toIntOrNull() ?: 0) + 1).toString(),
        )
        saveCustom(context, packs)
    }

    private fun saveCustom(context: Context, packs: List<StickerPack>) {
        val arr = JSONArray()
        packs.forEach { arr.put(toJson(it)) }
        val tmp = File(customDir(context), "packs.json.tmp")
        tmp.writeText(JSONObject().put("sticker_packs", arr).toString())
        tmp.renameTo(customIndex(context))
    }

    private fun parsePacks(arr: JSONArray, isCustom: Boolean): List<StickerPack> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val st = o.getJSONArray("stickers")
            StickerPack(
                identifier = o.getString("identifier"),
                name = o.getString("name"),
                publisher = o.getString("publisher"),
                trayImageFile = o.getString("tray_image_file"),
                imageDataVersion = o.optString("image_data_version", "1"),
                avoidCache = o.optBoolean("avoid_cache", false),
                animated = o.optBoolean("animated_sticker_pack", false),
                publisherEmail = o.optString("publisher_email"),
                publisherWebsite = o.optString("publisher_website"),
                privacyPolicyWebsite = o.optString("privacy_policy_website"),
                licenseAgreementWebsite = o.optString("license_agreement_website"),
                stickers = (0 until st.length()).map { j ->
                    val s = st.getJSONObject(j)
                    val em = s.optJSONArray("emojis") ?: JSONArray()
                    Sticker(
                        imageFile = s.getString("image_file"),
                        emojis = (0 until em.length()).map { em.getString(it) },
                        accessibilityText = s.optString("accessibility_text"),
                    )
                },
                isCustom = isCustom,
                category = o.optString("category"),
            )
        }

    private fun toJson(p: StickerPack) = JSONObject()
        .put("identifier", p.identifier)
        .put("name", p.name)
        .put("publisher", p.publisher)
        .put("tray_image_file", p.trayImageFile)
        .put("image_data_version", p.imageDataVersion)
        .put("avoid_cache", p.avoidCache)
        .put("animated_sticker_pack", p.animated)
        .put("publisher_email", p.publisherEmail)
        .put("publisher_website", p.publisherWebsite)
        .put("privacy_policy_website", p.privacyPolicyWebsite)
        .put("license_agreement_website", p.licenseAgreementWebsite)
        .put("stickers", JSONArray().apply {
            p.stickers.forEach {
                put(JSONObject()
                    .put("image_file", it.imageFile)
                    .put("emojis", JSONArray(it.emojis))
                    .put("accessibility_text", it.accessibilityText))
            }
        })
}
