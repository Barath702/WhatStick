package com.example

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.example.data.local.StickerDatabase
import com.example.data.local.StickerFileStorage
import com.example.data.local.ThemePreferences
import com.example.data.repository.StickerRepository

class WhatStickApp : Application(), ImageLoaderFactory {

    val database: StickerDatabase by lazy { StickerDatabase.getInstance(this) }
    val fileStorage: StickerFileStorage by lazy { StickerFileStorage(this) }
    val repository: StickerRepository by lazy {
        StickerRepository(database.stickerDao(), fileStorage)
    }
    val themePreferences: ThemePreferences by lazy { ThemePreferences(this) }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(false)
            .build()
    }
}
