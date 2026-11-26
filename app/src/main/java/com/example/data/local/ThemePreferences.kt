package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.AccentColorOption
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "whatstick_theme_prefs")

data class ThemeSettings(
    val accentColor: AccentColorOption = AccentColorOption.BLUE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)

class ThemePreferences(private val context: Context) {
    private val accentKey = stringPreferencesKey("accent_color")
    private val modeKey = stringPreferencesKey("theme_mode")

    val settingsFlow: Flow<ThemeSettings> = context.themeDataStore.data.map { prefs ->
        ThemeSettings(
            accentColor = AccentColorOption.fromKey(prefs[accentKey]),
            themeMode = ThemeMode.fromKey(prefs[modeKey])
        )
    }

    suspend fun setAccentColor(accent: AccentColorOption) {
        context.themeDataStore.edit { prefs ->
            prefs[accentKey] = accent.key
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { prefs ->
            prefs[modeKey] = mode.key
        }
    }
}
