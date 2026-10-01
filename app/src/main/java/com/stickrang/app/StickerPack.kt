package com.stickrang.app

data class Sticker(
    val imageFile: String,
    val emojis: List<String>,
    val accessibilityText: String = "",
)

data class StickerPack(
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayImageFile: String,
    val imageDataVersion: String,
    val avoidCache: Boolean,
    val animated: Boolean,
    val publisherEmail: String,
    val publisherWebsite: String,
    val privacyPolicyWebsite: String,
    val licenseAgreementWebsite: String,
    val stickers: List<Sticker>,
    /** True for packs the user made in the app (stored in filesDir, not assets). */
    val isCustom: Boolean = false,
    /** Explore tab category (bundled packs only): funny, love, greetings, celebrations, desi, islamic. */
    val category: String = "",
) {
    val canBeAdded: Boolean get() = stickers.size in MIN_STICKERS..MAX_STICKERS

    companion object {
        const val MIN_STICKERS = 3
        const val MAX_STICKERS = 30
    }
}
