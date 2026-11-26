package com.example.data.repository

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import com.example.data.local.StickerDao
import com.example.data.local.StickerFileStorage
import com.example.data.model.StickerEntity
import com.example.data.model.StickerPackEntity
import com.example.data.model.StickerPackWithStickers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class StickerRepository(
    private val dao: StickerDao,
    val storage: StickerFileStorage
) {
    companion object {
        const val MAX_STICKERS_PER_PACK = 30
    }

    val allPacksWithStickers: Flow<List<StickerPackWithStickers>> = dao.observePacksWithStickers()
    val recentStickers: Flow<List<StickerEntity>> = dao.observeRecentStickers(12)

    fun observePack(packId: String): Flow<StickerPackWithStickers?> =
        dao.observePackWithStickers(packId)

    suspend fun getAllPacksOnce(): List<StickerPackWithStickers> = withContext(Dispatchers.IO) {
        dao.getAllPacksWithStickersOnce()
    }

    suspend fun getPackOnce(packId: String): StickerPackWithStickers? = withContext(Dispatchers.IO) {
        dao.getPackWithStickersOnce(packId)
    }

    suspend fun getStickerById(stickerId: String): StickerEntity? = withContext(Dispatchers.IO) {
        dao.getStickerById(stickerId)
    }

    suspend fun createPack(
        name: String,
        publisher: String = "WhatStick",
        isAnimated: Boolean = false,
        defaultTrayColor: Int = 0xFF2563EB.toInt()
    ): StickerPackEntity = withContext(Dispatchers.IO) {
        val packId = "pack_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val trayFile = "tray.png"
        storage.writeDefaultTrayIcon(packId, trayFile, defaultTrayColor)
        val entity = StickerPackEntity(
            id = packId,
            name = name.trim().ifEmpty { if (isAnimated) "Animated Pack" else "My Stickers" },
            publisher = publisher.trim().ifEmpty { "WhatStick" },
            trayIconFileName = trayFile,
            isAnimated = isAnimated,
            imageDataVersion = 1
        )
        dao.insertPack(entity)
        entity
    }

    suspend fun renamePack(packId: String, newName: String, newPublisher: String? = null) =
        withContext(Dispatchers.IO) {
            val current = dao.getPackWithStickersOnce(packId)?.pack ?: return@withContext
            val trimmed = newName.trim()
            if (trimmed.isEmpty()) return@withContext
            dao.updatePack(
                current.copy(
                    name = trimmed,
                    publisher = newPublisher?.trim()?.takeIf { it.isNotEmpty() } ?: current.publisher,
                    imageDataVersion = current.imageDataVersion + 1,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

    suspend fun updatePackTrayIcon(packId: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        val current = dao.getPackWithStickersOnce(packId)?.pack ?: return@withContext
        val trayFile = "tray_${System.currentTimeMillis()}.png"
        if (current.trayIconFileName != trayFile) {
            storage.deleteStickerFile(packId, current.trayIconFileName)
        }
        storage.writeTrayIconBitmap(packId, trayFile, bitmap)
        dao.updatePack(
            current.copy(
                trayIconFileName = trayFile,
                imageDataVersion = current.imageDataVersion + 1,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setStickerAsTrayIcon(packId: String, sticker: StickerEntity) =
        withContext(Dispatchers.IO) {
            val file = storage.getAssetFile(packId, sticker.fileName)
            if (!file.exists()) return@withContext
            val bmp = decodeFirstFrameBitmap(file) ?: return@withContext
            try {
                updatePackTrayIcon(packId, bmp)
            } finally {
                bmp.recycle()
            }
        }

    suspend fun addOrUpdateSticker(
        packId: String,
        webpBytes: ByteArray,
        isAnimated: Boolean,
        emojis: String = "😄",
        durationMs: Long = 0L,
        existingStickerId: String? = null
    ): Result<StickerEntity> = withContext(Dispatchers.IO) {
        if (!storage.hasEnoughDiskSpace(webpBytes.size.toLong() + 1024L * 1024L)) {
            return@withContext Result.failure(IllegalStateException("Insufficient device storage space."))
        }
        val packWithStickers = dao.getPackWithStickersOnce(packId)
            ?: return@withContext Result.failure(IllegalArgumentException("Sticker pack not found."))
        val pack = packWithStickers.pack
        if (pack.isAnimated != isAnimated) {
            return@withContext Result.failure(
                IllegalStateException(
                    if (pack.isAnimated) "Animated packs can only contain animated stickers."
                    else "Static packs can only contain static stickers."
                )
            )
        }

        val existing = existingStickerId?.let { dao.getStickerById(it) }
        if (existing == null && packWithStickers.stickers.size >= MAX_STICKERS_PER_PACK) {
            return@withContext Result.failure(
                IllegalStateException("WhatsApp allows a maximum of 30 stickers per pack.")
            )
        }

        val stickerId = existing?.id ?: "stk_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val fileName = "${stickerId}.webp"
        storage.writeStickerBytes(packId, fileName, webpBytes)

        val cleanedEmojis = emojis.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .joinToString(",")
            .ifEmpty { "😄" }

        val sticker = StickerEntity(
            id = stickerId,
            packId = packId,
            fileName = fileName,
            emojis = cleanedEmojis,
            isAnimated = isAnimated,
            fileSizeBytes = webpBytes.size.toLong(),
            durationMs = durationMs,
            sortOrder = existing?.sortOrder ?: packWithStickers.stickers.size,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        dao.insertSticker(sticker)

        // If this is the first real sticker and tray is still default placeholder, auto-update tray icon!
        var newTrayFile = pack.trayIconFileName
        if (packWithStickers.stickers.isEmpty() && pack.trayIconFileName == "tray.png") {
            val file = storage.getAssetFile(packId, fileName)
            decodeFirstFrameBitmap(file)?.let { firstFrame ->
                try {
                    newTrayFile = "tray_${System.currentTimeMillis()}.png"
                    storage.deleteStickerFile(packId, pack.trayIconFileName)
                    storage.writeTrayIconBitmap(packId, newTrayFile, firstFrame)
                } finally {
                    firstFrame.recycle()
                }
            }
        }

        dao.updatePack(
            pack.copy(
                trayIconFileName = newTrayFile,
                imageDataVersion = pack.imageDataVersion + 1,
                updatedAt = System.currentTimeMillis()
            )
        )
        Result.success(sticker)
    }

    suspend fun updateStickerEmojis(stickerId: String, emojis: String) = withContext(Dispatchers.IO) {
        val sticker = dao.getStickerById(stickerId) ?: return@withContext
        val cleaned = emojis.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
            .joinToString(",")
            .ifEmpty { "😄" }
        dao.updateSticker(sticker.copy(emojis = cleaned))
        val pack = dao.getPackWithStickersOnce(sticker.packId)?.pack ?: return@withContext
        dao.updatePack(
            pack.copy(
                imageDataVersion = pack.imageDataVersion + 1,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteSticker(sticker: StickerEntity) = withContext(Dispatchers.IO) {
        dao.deleteStickerById(sticker.id)
        storage.deleteStickerFile(sticker.packId, sticker.fileName)
        val pack = dao.getPackWithStickersOnce(sticker.packId)?.pack ?: return@withContext
        dao.updatePack(
            pack.copy(
                imageDataVersion = pack.imageDataVersion + 1,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deletePack(packId: String) = withContext(Dispatchers.IO) {
        dao.deletePackById(packId)
        storage.deletePackDir(packId)
    }

    fun getStickerFile(packId: String, fileName: String): File =
        storage.getAssetFile(packId, fileName)

    private fun decodeFirstFrameBitmap(file: File): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val src = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                BitmapFactory.decodeFile(file.absolutePath)
            }
        } catch (_: Exception) {
            BitmapFactory.decodeFile(file.absolutePath)
        }
    }
}
