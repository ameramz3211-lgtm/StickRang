package com.stickrang.app

import java.util.Locale

/** Offline search over pack names, sticker captions and emojis, with a few Roman Urdu synonyms. */
object StickerSearch {
    private val SYNONYMS = mapOf(
        "khush" to listOf("hahaha", "😂", "🤣", "😊", "khush"),
        "hansi" to listOf("hahaha", "😂", "🤣"),
        "funny" to listOf("hahaha", "😂", "🤣", "pagle"),
        "gussa" to listOf("naraz", "🙄", "🤨"),
        "udaas" to listOf("miss", "😢", "🥺", "naraz"),
        "sad" to listOf("miss", "😢", "🥺"),
        "love" to listOf("love", "jaan", "❤️", "😘", "😍"),
        "pyar" to listOf("love", "jaan", "❤️", "😘", "😍"),
        "salam" to listOf("assalam", "walaikum", "👋"),
        "dua" to listOf("dua", "ameen", "inshaallah", "🤲"),
        "shukriya" to listOf("jazakallah", "thank"),
        "thanks" to listOf("jazakallah", "thank"),
        "chai" to listOf("chai", "☕"),
        "sona" to listOf("night", "so jao", "neend", "😴", "💤"),
        "morning" to listOf("subah", "morning", "🌅"),
        "night" to listOf("shab", "night", "😴"),
        "ramzan" to listOf("ramadan", "iftar", "sehri", "roza", "taraweeh"),
        "eid" to listOf("eid", "eidi", "chaand", "🌙"),
        "sorry" to listOf("sorry", "🙏"),
        "ok" to listOf("theek", "set", "👍", "👌"),
    )

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\p{So} ]"), "").trim()

    private fun terms(query: String): List<String> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        return listOf(q) + SYNONYMS.filterKeys { it.startsWith(q) || q.startsWith(it) }.values.flatten().map(::norm)
    }

    fun matches(sticker: Sticker, terms: List<String>): Boolean {
        val hay = norm(sticker.accessibilityText)
        return terms.any { t -> hay.contains(t) || sticker.emojis.any { it.contains(t) } }
    }

    /**
     * Packs that match [query], each with its matching stickers moved to the front so the
     * row previews show them. An empty query returns [packs] unchanged.
     */
    fun filter(packs: List<StickerPack>, query: String): List<StickerPack> {
        val t = terms(query)
        if (t.isEmpty()) return packs
        return packs.mapNotNull { p ->
            val (hit, rest) = p.stickers.partition { matches(it, t) }
            when {
                hit.isNotEmpty() -> p.copy(stickers = hit + rest)
                t.any { norm(p.name).contains(it) } -> p
                else -> null
            }
        }
    }
}
