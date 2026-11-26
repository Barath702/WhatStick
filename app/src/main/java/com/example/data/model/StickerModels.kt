package com.example.data.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "sticker_packs")
data class StickerPackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val publisher: String = "WhatStick",
    val trayIconFileName: String = "tray.png",
    val isAnimated: Boolean = false,
    val imageDataVersion: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "stickers",
    foreignKeys = [
        ForeignKey(
            entity = StickerPackEntity::class,
            parentColumns = ["id"],
            childColumns = ["packId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["packId"])]
)
data class StickerEntity(
    @PrimaryKey val id: String,
    val packId: String,
    val fileName: String,
    val emojis: String = "😄",
    val isAnimated: Boolean = false,
    val fileSizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

data class StickerPackWithStickers(
    @Embedded val pack: StickerPackEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "packId"
    )
    val stickers: List<StickerEntity>
) {
    val sortedStickers: List<StickerEntity>
        get() = stickers.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))
}

enum class AccentColorOption(val label: String, val key: String) {
    BLUE("Blue", "blue"),
    PURPLE("Purple", "purple"),
    GREEN("Green", "green"),
    PINK("Pink", "pink"),
    ORANGE("Orange", "orange");

    companion object {
        fun fromKey(key: String?): AccentColorOption =
            entries.firstOrNull { it.key == key } ?: BLUE
    }
}

enum class ThemeMode(val label: String, val key: String) {
    SYSTEM("System", "system"),
    LIGHT("Light", "light"),
    DARK("Dark", "dark");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
