package com.stickrang.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * The contract WhatsApp uses to read sticker packs from this app.
 * Column names and paths must match WhatsApp's spec exactly.
 */
class StickerContentProvider : ContentProvider() {

    private val matcher = UriMatcher(UriMatcher.NO_MATCH)

    override fun onCreate(): Boolean {
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        matcher.addURI(authority, METADATA, METADATA_CODE)
        matcher.addURI(authority, "$METADATA/*", METADATA_SINGLE_CODE)
        matcher.addURI(authority, "$STICKERS/*", STICKERS_CODE)
        matcher.addURI(authority, "$STICKERS_ASSET/*/*", STICKERS_ASSET_CODE)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = context ?: throw IllegalStateException("no context")
        return when (matcher.match(uri)) {
            METADATA_CODE -> packsCursor(StickerRepository.allPacks(ctx).filter { it.canBeAdded })
            METADATA_SINGLE_CODE -> packsCursor(listOfNotNull(StickerRepository.findPack(ctx, uri.lastPathSegment ?: "")))
            STICKERS_CODE -> stickersCursor(StickerRepository.findPack(ctx, uri.lastPathSegment ?: ""))
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }.also { it.setNotificationUri(ctx.contentResolver, uri) }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val ctx = context ?: return null
        if (matcher.match(uri) != STICKERS_ASSET_CODE) throw IllegalArgumentException("Unknown URI: $uri")
        val segs = uri.pathSegments
        if (segs.size != 3) throw IllegalArgumentException("Bad path: $uri")
        val packId = segs[1]
        val fileName = segs[2]
        val pack = StickerRepository.findPack(ctx, packId) ?: throw FileNotFoundException(packId)
        val known = fileName == pack.trayImageFile || pack.stickers.any { it.imageFile == fileName }
        if (!known) throw FileNotFoundException(fileName)
        return if (pack.isCustom) {
            val f = File(File(StickerRepository.customDir(ctx), packId), fileName)
            val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        } else {
            ctx.assets.openFd("$packId/$fileName")
        }
    }

    override fun getType(uri: Uri): String? {
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        return when (matcher.match(uri)) {
            METADATA_CODE -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
            METADATA_SINGLE_CODE -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
            STICKERS_CODE -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
            STICKERS_ASSET_CODE -> if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    private fun packsCursor(packs: List<StickerPack>): Cursor {
        val c = MatrixCursor(arrayOf(
            STICKER_PACK_IDENTIFIER, STICKER_PACK_NAME, STICKER_PACK_PUBLISHER, STICKER_PACK_ICON,
            ANDROID_APP_DOWNLOAD_LINK, IOS_APP_DOWNLOAD_LINK, PUBLISHER_EMAIL, PUBLISHER_WEBSITE,
            PRIVACY_POLICY_WEBSITE, LICENSE_AGREEMENT_WEBSITE, IMAGE_DATA_VERSION, AVOID_CACHE, ANIMATED_STICKER_PACK,
        ))
        packs.forEach { p ->
            c.addRow(arrayOf<Any>(
                p.identifier, p.name, p.publisher, p.trayImageFile,
                StickerRepository.playStoreLink, "", p.publisherEmail, p.publisherWebsite,
                p.privacyPolicyWebsite, p.licenseAgreementWebsite, p.imageDataVersion,
                if (p.avoidCache) 1 else 0, if (p.animated) 1 else 0,
            ))
        }
        return c
    }

    private fun stickersCursor(pack: StickerPack?): Cursor {
        val c = MatrixCursor(arrayOf(STICKER_FILE_NAME, STICKER_FILE_EMOJI, STICKER_ACCESSIBILITY_TEXT))
        pack?.stickers?.forEach { c.addRow(arrayOf<Any>(it.imageFile, it.emojis.joinToString(","), it.accessibilityText)) }
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"
        private const val METADATA_CODE = 1
        private const val METADATA_SINGLE_CODE = 2
        private const val STICKERS_CODE = 3
        private const val STICKERS_ASSET_CODE = 4

        const val STICKER_PACK_IDENTIFIER = "sticker_pack_identifier"
        const val STICKER_PACK_NAME = "sticker_pack_name"
        const val STICKER_PACK_PUBLISHER = "sticker_pack_publisher"
        const val STICKER_PACK_ICON = "sticker_pack_icon"
        const val ANDROID_APP_DOWNLOAD_LINK = "android_play_store_link"
        const val IOS_APP_DOWNLOAD_LINK = "ios_app_download_link"
        const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        const val IMAGE_DATA_VERSION = "image_data_version"
        const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        const val ANIMATED_STICKER_PACK = "animated_sticker_pack"
        const val STICKER_FILE_NAME = "sticker_file_name"
        const val STICKER_FILE_EMOJI = "sticker_emoji"
        const val STICKER_ACCESSIBILITY_TEXT = "sticker_accessibility_text"
    }
}
