package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.data.model.AccentColorOption
import com.example.data.model.ThemeMode

val WhatStickShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun WhatStickTheme(
    accentColor: AccentColorOption = AccentColorOption.BLUE,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val palette = WhatStickPalettes.forOption(accentColor)

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.primaryDark,
            onPrimary = palette.onPrimaryDark,
            primaryContainer = palette.primaryContainerDark,
            onPrimaryContainer = palette.onPrimaryContainerDark,
            secondary = palette.secondaryDark,
            onSecondary = palette.onPrimaryDark,
            secondaryContainer = palette.primaryContainerDark,
            onSecondaryContainer = palette.onPrimaryContainerDark,
            tertiary = palette.secondaryDark,
            onTertiary = palette.onPrimaryDark,
            tertiaryContainer = palette.primaryContainerDark,
            onTertiaryContainer = palette.onPrimaryContainerDark,
            surfaceTint = palette.primaryDark,
            background = palette.backgroundDark,
            onBackground = Color(0xFFF1F5F9),
            surface = palette.surfaceDark,
            onSurface = Color(0xFFF1F5F9),
            surfaceVariant = palette.surfaceVariantDark,
            onSurfaceVariant = Color(0xFFCBD5E1),
            surfaceContainerLowest = palette.backgroundDark,
            surfaceContainerLow = Color(0xFF111622),
            surfaceContainer = palette.surfaceDark,
            surfaceContainerHigh = palette.surfaceVariantDark,
            surfaceContainerHighest = Color(0xFF263042),
            outline = Color(0xFF334155),
            outlineVariant = Color(0xFF1E293B),
            error = Color(0xFFF87171),
            onError = Color(0xFF450A0A)
        )
    } else {
        lightColorScheme(
            primary = palette.primaryLight,
            onPrimary = palette.onPrimaryLight,
            primaryContainer = palette.primaryContainerLight,
            onPrimaryContainer = palette.onPrimaryContainerLight,
            secondary = palette.secondaryLight,
            onSecondary = Color.White,
            secondaryContainer = palette.primaryContainerLight,
            onSecondaryContainer = palette.onPrimaryContainerLight,
            tertiary = palette.secondaryLight,
            onTertiary = Color.White,
            tertiaryContainer = palette.primaryContainerLight,
            onTertiaryContainer = palette.onPrimaryContainerLight,
            surfaceTint = palette.primaryLight,
            background = palette.backgroundLight,
            onBackground = Color(0xFF0F172A),
            surface = palette.surfaceLight,
            onSurface = Color(0xFF0F172A),
            surfaceVariant = palette.surfaceVariantLight,
            onSurfaceVariant = Color(0xFF475569),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF8FAFC),
            surfaceContainer = Color(0xFFF1F5F9),
            surfaceContainerHigh = palette.surfaceVariantLight,
            surfaceContainerHighest = Color(0xFFE2E8F0),
            outline = Color(0xFFCBD5E1),
            outlineVariant = Color(0xFFE2E8F0),
            error = Color(0xFFDC2626),
            onError = Color.White
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = WhatStickTypography,
        shapes = WhatStickShapes,
        content = content
    )
}
