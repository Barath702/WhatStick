package com.example.whatsapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.example.BuildConfig

data class WhatsAppTarget(
    val packageName: String,
    val displayName: String
)

sealed interface WhatsAppInstallOutcome {
    data class Success(val packName: String) : WhatsAppInstallOutcome
    data class Error(val message: String) : WhatsAppInstallOutcome
    data object Cancelled : WhatsAppInstallOutcome
}

object WhatsAppInstaller {
    const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
    const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
    const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"

    const val CONSUMER_WHATSAPP_PACKAGE = "com.whatsapp"
    const val BUSINESS_WHATSAPP_PACKAGE = "com.whatsapp.w4b"

    fun getInstalledTargets(context: Context): List<WhatsAppTarget> {
        val pm = context.packageManager
        val candidates = listOf(
            WhatsAppTarget(CONSUMER_WHATSAPP_PACKAGE, "WhatsApp"),
            WhatsAppTarget(BUSINESS_WHATSAPP_PACKAGE, "WhatsApp Business")
        )
        return candidates.filter { isPackageInstalled(pm, it.packageName) }
    }

    fun isPackAddedToWhatsApp(context: Context, packId: String, targetPackage: String): Boolean {
        return try {
            val providerUri = Uri.Builder()
                .scheme("content")
                .authority("$targetPackage.provider.sticker_whitelist_check")
                .appendPath("is_whitelisted")
                .appendQueryParameter("authority", BuildConfig.CONTENT_PROVIDER_AUTHORITY)
                .appendQueryParameter("identifier", packId)
                .build()
            context.contentResolver.query(providerUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val colIdx = cursor.getColumnIndex("result")
                    if (colIdx >= 0) cursor.getInt(colIdx) == 1 else false
                } else {
                    false
                }
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun buildInstallIntent(
        packId: String,
        packName: String,
        targetPackage: String? = null
    ): Intent {
        return Intent(ACTION_ENABLE_STICKER_PACK).apply {
            putExtra(EXTRA_STICKER_PACK_ID, packId)
            putExtra(EXTRA_STICKER_PACK_AUTHORITY, BuildConfig.CONTENT_PROVIDER_AUTHORITY)
            putExtra(EXTRA_STICKER_PACK_NAME, packName)
            if (targetPackage != null) {
                setPackage(targetPackage)
            }
        }
    }

    fun parseActivityResult(resultCode: Int, data: Intent?, packName: String): WhatsAppInstallOutcome {
        return when (resultCode) {
            Activity.RESULT_OK -> WhatsAppInstallOutcome.Success(packName)
            Activity.RESULT_CANCELED -> {
                val validationError = data?.getStringExtra("validation_error")
                if (!validationError.isNullOrBlank()) {
                    WhatsAppInstallOutcome.Error(
                        "WhatsApp rejected this pack: $validationError"
                    )
                } else {
                    WhatsAppInstallOutcome.Cancelled
                }
            }
            else -> WhatsAppInstallOutcome.Error("Unexpected response from WhatsApp (code $resultCode).")
        }
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
