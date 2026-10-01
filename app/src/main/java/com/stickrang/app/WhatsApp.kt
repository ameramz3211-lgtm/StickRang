package com.stickrang.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

object WhatsApp {
    const val CONSUMER = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"
    private const val ENABLE_ACTION = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
        info.enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** True when the pack is already added to every installed WhatsApp. */
    fun isPackAdded(context: Context, packId: String): Boolean {
        val installed = listOf(CONSUMER, BUSINESS).filter { isInstalled(context, it) }
        if (installed.isEmpty()) return false
        return installed.all { isWhitelisted(context, packId, it) }
    }

    private fun isWhitelisted(context: Context, packId: String, pkg: String): Boolean {
        val authority = "$pkg.provider.sticker_whitelist_check"
        if (context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA) == null) return false
        val uri = Uri.Builder().scheme("content").authority(authority).appendPath("is_whitelisted")
            .appendQueryParameter("authority", BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .appendQueryParameter("identifier", packId)
            .build()
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("result")) == 1
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Intent that asks WhatsApp to add the pack, or null if WhatsApp is not installed. */
    fun addPackIntent(context: Context, pack: StickerPack): Intent? {
        val consumer = isInstalled(context, CONSUMER)
        val business = isInstalled(context, BUSINESS)
        if (!consumer && !business) return null
        val intent = Intent(ENABLE_ACTION)
            .putExtra("sticker_pack_id", pack.identifier)
            .putExtra("sticker_pack_authority", BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            .putExtra("sticker_pack_name", pack.name)
        // With both installed, prefer whichever does not have the pack yet; otherwise let Android pick.
        if (consumer && business) {
            when {
                !isWhitelisted(context, pack.identifier, CONSUMER) -> intent.setPackage(CONSUMER)
                !isWhitelisted(context, pack.identifier, BUSINESS) -> intent.setPackage(BUSINESS)
            }
        } else {
            intent.setPackage(if (consumer) CONSUMER else BUSINESS)
        }
        return intent
    }

    fun openPlayStore(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$CONSUMER")))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$CONSUMER")))
        }
    }
}
