package com.example.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.BuildConfig
import com.example.data.local.StickerDatabase
import com.example.data.local.StickerFileStorage
import com.example.data.model.StickerPackWithStickers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

class StickerContentProvider : ContentProvider() {

    companion object {
        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"

        private const val METADATA_CODE = 1
        private const val METADATA_CODE_FOR_SINGLE_PACK = 2
        private const val STICKERS_CODE = 3
        private const val STICKERS_ASSET_CODE = 4

        const val STICKER_PACK_IDENTIFIER_IN_QUERY = "sticker_pack_identifier"
        const val STICKER_PACK_NAME_IN_QUERY = "sticker_pack_name"
        const val STICKER_PACK_PUBLISHER_IN_QUERY = "sticker_pack_publisher"
        const val STICKER_PACK_ICON_IN_QUERY = "sticker_pack_icon"
        const val ANDROID_APP_DOWNLOAD_LINK_IN_QUERY = "android_play_store_link"
        const val IOS_APP_DOWNLOAD_LINK_IN_QUERY = "ios_app_download_link"
        const val PUBLISHER_EMAIL = "publisher_email"
        const val PUBLISHER_WEBSITE = "publisher_website"
        const val PRIVACY_POLICY_WEBSITE = "privacy_policy_website"
        const val LICENSE_AGREEMENT_WEBSITE = "license_agreement_website"
        const val IMAGE_DATA_VERSION = "image_data_version"
        const val AVOID_CACHE = "avoid_cache"
        const val ANIMATED_STICKER_PACK = "animated_sticker_pack"

        const val STICKER_FILE_NAME_IN_QUERY = "sticker_file_name"
        const val STICKER_FILE_EMOJI_IN_QUERY = "sticker_emoji"

        private val PACK_COLUMNS = arrayOf(
            STICKER_PACK_IDENTIFIER_IN_QUERY,
            STICKER_PACK_NAME_IN_QUERY,
            STICKER_PACK_PUBLISHER_IN_QUERY,
            STICKER_PACK_ICON_IN_QUERY,
            ANDROID_APP_DOWNLOAD_LINK_IN_QUERY,
            IOS_APP_DOWNLOAD_LINK_IN_QUERY,
            PUBLISHER_EMAIL,
            PUBLISHER_WEBSITE,
            PRIVACY_POLICY_WEBSITE,
            LICENSE_AGREEMENT_WEBSITE,
            IMAGE_DATA_VERSION,
            AVOID_CACHE,
            ANIMATED_STICKER_PACK
        )

        private val STICKER_COLUMNS = arrayOf(
            STICKER_FILE_NAME_IN_QUERY,
            STICKER_FILE_EMOJI_IN_QUERY
        )

        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH)
    }

    override fun onCreate(): Boolean {
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        MATCHER.addURI(authority, METADATA, METADATA_CODE)
        MATCHER.addURI(authority, "$METADATA/*", METADATA_CODE_FOR_SINGLE_PACK)
        MATCHER.addURI(authority, "$STICKERS/*", STICKERS_CODE)
        MATCHER.addURI(authority, "$STICKERS_ASSET/*/*", STICKERS_ASSET_CODE)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val dao = StickerDatabase.getInstance(ctx).stickerDao()
        return when (MATCHER.match(uri)) {
            METADATA_CODE -> {
                val packs = runBlocking(Dispatchers.IO) { dao.getAllPacksWithStickersOnce() }
                buildPackCursor(packs)
            }
            METADATA_CODE_FOR_SINGLE_PACK -> {
                val packId = uri.lastPathSegment ?: return null
                val pack = runBlocking(Dispatchers.IO) { dao.getPackWithStickersOnce(packId) }
                buildPackCursor(listOfNotNull(pack))
            }
            STICKERS_CODE -> {
                val packId = uri.lastPathSegment ?: return null
                val pack = runBlocking(Dispatchers.IO) { dao.getPackWithStickersOnce(packId) }
                val cursor = MatrixCursor(STICKER_COLUMNS)
                pack?.sortedStickers?.forEach { sticker ->
                    cursor.addRow(arrayOf(sticker.fileName, sticker.emojis))
                }
                cursor
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val ctx = context ?: return null
        if (mode.contains("w", ignoreCase = true) || mode.contains("t", ignoreCase = true)) {
            throw SecurityException("StickerContentProvider is read-only.")
        }
        if (MATCHER.match(uri) != STICKERS_ASSET_CODE) {
            throw IllegalArgumentException("Unknown asset URI: $uri")
        }
        val segments = uri.pathSegments
        if (segments.size < 3) {
            throw FileNotFoundException("Invalid sticker asset path: $uri")
        }
        val packId = segments[segments.size - 2]
        val fileName = segments[segments.size - 1]
        val file = StickerFileStorage(ctx).getAssetFile(packId, fileName)
        if (!file.exists() || !file.isFile) {
            throw FileNotFoundException("Sticker asset not found: $uri")
        }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String {
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        return when (MATCHER.match(uri)) {
            METADATA_CODE -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
            METADATA_CODE_FOR_SINGLE_PACK -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
            STICKERS_CODE -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
            STICKERS_ASSET_CODE -> {
                val name = uri.lastPathSegment.orEmpty()
                if (name.endsWith(".png", ignoreCase = true)) "image/png" else "image/webp"
            }
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun buildPackCursor(packs: List<StickerPackWithStickers>): MatrixCursor {
        val cursor = MatrixCursor(PACK_COLUMNS)
        packs.forEach { entry ->
            val pack = entry.pack
            cursor.addRow(
                arrayOf<Any>(
                    pack.id,
                    pack.name,
                    pack.publisher,
                    pack.trayIconFileName,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    pack.imageDataVersion.toString(),
                    0,
                    if (pack.isAnimated) 1 else 0
                )
            )
        }
        return cursor
    }
}
