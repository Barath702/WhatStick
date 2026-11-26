package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.StickerEntity
import com.example.data.model.StickerPackEntity
import com.example.data.model.StickerPackWithStickers
import kotlinx.coroutines.flow.Flow

@Dao
interface StickerDao {
    @Transaction
    @Query("SELECT * FROM sticker_packs ORDER BY updatedAt DESC")
    fun observePacksWithStickers(): Flow<List<StickerPackWithStickers>>

    @Transaction
    @Query("SELECT * FROM sticker_packs WHERE id = :packId")
    fun observePackWithStickers(packId: String): Flow<StickerPackWithStickers?>

    @Transaction
    @Query("SELECT * FROM sticker_packs ORDER BY updatedAt DESC")
    suspend fun getAllPacksWithStickersOnce(): List<StickerPackWithStickers>

    @Transaction
    @Query("SELECT * FROM sticker_packs WHERE id = :packId")
    suspend fun getPackWithStickersOnce(packId: String): StickerPackWithStickers?

    @Query("SELECT * FROM stickers ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentStickers(limit: Int = 12): Flow<List<StickerEntity>>

    @Query("SELECT * FROM stickers WHERE id = :stickerId")
    suspend fun getStickerById(stickerId: String): StickerEntity?

    @Query("SELECT COUNT(*) FROM stickers WHERE packId = :packId")
    suspend fun getStickerCountForPack(packId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPack(pack: StickerPackEntity)

    @Update
    suspend fun updatePack(pack: StickerPackEntity)

    @Query("DELETE FROM sticker_packs WHERE id = :packId")
    suspend fun deletePackById(packId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSticker(sticker: StickerEntity)

    @Update
    suspend fun updateSticker(sticker: StickerEntity)

    @Query("DELETE FROM stickers WHERE id = :stickerId")
    suspend fun deleteStickerById(stickerId: String)
}
