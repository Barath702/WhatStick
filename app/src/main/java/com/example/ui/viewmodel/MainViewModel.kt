package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.WhatStickApp
import com.example.data.local.ThemeSettings
import com.example.data.model.AccentColorOption
import com.example.data.model.StickerEntity
import com.example.data.model.StickerPackEntity
import com.example.data.model.StickerPackWithStickers
import com.example.data.model.ThemeMode
import com.example.media.BitmapLoader
import com.example.media.InspectedMedia
import com.example.media.MediaInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WhatStickApp
    val repository = app.repository
    private val themePrefs = app.themePreferences

    val themeSettings: StateFlow<ThemeSettings> = themePrefs.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeSettings()
        )

    val packs: StateFlow<List<StickerPackWithStickers>> = repository.allPacksWithStickers
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val recentStickers: StateFlow<List<StickerEntity>> = repository.recentStickers
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun observePack(packId: String): Flow<StickerPackWithStickers?> =
        repository.observePack(packId)

    fun setAccentColor(accent: AccentColorOption) {
        viewModelScope.launch { themePrefs.setAccentColor(accent) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themePrefs.setThemeMode(mode) }
    }

    suspend fun createPack(
        name: String,
        publisher: String,
        isAnimated: Boolean
    ): StickerPackEntity {
        return repository.createPack(name, publisher, isAnimated)
    }

    fun renamePack(packId: String, newName: String, newPublisher: String? = null) {
        viewModelScope.launch { repository.renamePack(packId, newName, newPublisher) }
    }

    fun deletePack(packId: String) {
        viewModelScope.launch { repository.deletePack(packId) }
    }

    fun deleteSticker(sticker: StickerEntity) {
        viewModelScope.launch { repository.deleteSticker(sticker) }
    }

    fun setStickerAsTrayIcon(packId: String, sticker: StickerEntity) {
        viewModelScope.launch { repository.setStickerAsTrayIcon(packId, sticker) }
    }

    fun updateStickerEmojis(stickerId: String, emojis: String) {
        viewModelScope.launch { repository.updateStickerEmojis(stickerId, emojis) }
    }

    suspend fun updatePackTrayIconFromUri(packId: String, uri: Uri): Result<Unit> {
        val loaded = BitmapLoader.loadWorkingBitmap(app, uri, maxDimension = 256)
        return loaded.map { bmp ->
            try {
                repository.updatePackTrayIcon(packId, bmp)
            } finally {
                bmp.recycle()
            }
        }
    }

    suspend fun inspectMedia(uri: Uri): Result<InspectedMedia> = withContext(Dispatchers.IO) {
        MediaInspector.inspect(app, uri)
    }
}
