package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.data.model.AccentColorOption

data class AccentPalette(
    val primaryLight: Color,
    val onPrimaryLight: Color,
    val primaryContainerLight: Color,
    val onPrimaryContainerLight: Color,
    val secondaryLight: Color,
    val backgroundLight: Color,
    val surfaceLight: Color,
    val surfaceVariantLight: Color,
    val primaryDark: Color,
    val onPrimaryDark: Color,
    val primaryContainerDark: Color,
    val onPrimaryContainerDark: Color,
    val secondaryDark: Color,
    val backgroundDark: Color,
    val surfaceDark: Color,
    val surfaceVariantDark: Color,
    val swatchColor: Color
)

object WhatStickPalettes {
    val Blue = AccentPalette(
        primaryLight = Color(0xFF2563EB),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFDBEAFE),
        onPrimaryContainerLight = Color(0xFF1E3A8A),
        secondaryLight = Color(0xFF0284C7),
        backgroundLight = Color(0xFFF6F9FF),
        surfaceLight = Color(0xFFFFFFFF),
        surfaceVariantLight = Color(0xFFEBF2FE),
        primaryDark = Color(0xFF60A5FA),
        onPrimaryDark = Color(0xFF08204B),
        primaryContainerDark = Color(0xFF1E3A8A),
        onPrimaryContainerDark = Color(0xFFDBEAFE),
        secondaryDark = Color(0xFF38BDF8),
        backgroundDark = Color(0xFF0D111A),
        surfaceDark = Color(0xFF141A26),
        surfaceVariantDark = Color(0xFF1E2738),
        swatchColor = Color(0xFF3B82F6)
    )

    val Purple = AccentPalette(
        primaryLight = Color(0xFF7C3AED),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFEDE9FE),
        onPrimaryContainerLight = Color(0xFF3B0764),
        secondaryLight = Color(0xFF6366F1),
        backgroundLight = Color(0xFFF9F7FF),
        surfaceLight = Color(0xFFFFFFFF),
        surfaceVariantLight = Color(0xFFF0ECFE),
        primaryDark = Color(0xFFA78BFA),
        onPrimaryDark = Color(0xFF280B5E),
        primaryContainerDark = Color(0xFF4C1D95),
        onPrimaryContainerDark = Color(0xFFEDE9FE),
        secondaryDark = Color(0xFF818CF8),
        backgroundDark = Color(0xFF100E1A),
        surfaceDark = Color(0xFF181526),
        surfaceVariantDark = Color(0xFF242038),
        swatchColor = Color(0xFF8B5CF6)
    )

    val Green = AccentPalette(
        primaryLight = Color(0xFF047857),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFD1FAE5),
        onPrimaryContainerLight = Color(0xFF064E3B),
        secondaryLight = Color(0xFF0D9488),
        backgroundLight = Color(0xFFF5FBF8),
        surfaceLight = Color(0xFFFFFFFF),
        surfaceVariantLight = Color(0xFFE5F6EE),
        primaryDark = Color(0xFF34D399),
        onPrimaryDark = Color(0xFF023323),
        primaryContainerDark = Color(0xFF065F46),
        onPrimaryContainerDark = Color(0xFFD1FAE5),
        secondaryDark = Color(0xFF2DD4BF),
        backgroundDark = Color(0xFF0B1512),
        surfaceDark = Color(0xFF121F1B),
        surfaceVariantDark = Color(0xFF1B2E28),
        swatchColor = Color(0xFF10B981)
    )

    val Pink = AccentPalette(
        primaryLight = Color(0xFFDB2777),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFFCE7F3),
        onPrimaryContainerLight = Color(0xFF831843),
        secondaryLight = Color(0xFFE11D48),
        backgroundLight = Color(0xFFFFF7FB),
        surfaceLight = Color(0xFFFFFFFF),
        surfaceVariantLight = Color(0xFFFDEBF4),
        primaryDark = Color(0xFFF472B6),
        onPrimaryDark = Color(0xFF500724),
        primaryContainerDark = Color(0xFF9D174D),
        onPrimaryContainerDark = Color(0xFFFCE7F3),
        secondaryDark = Color(0xFFFB7185),
        backgroundDark = Color(0xFF180D14),
        surfaceDark = Color(0xFF23141E),
        surfaceVariantDark = Color(0xFF331E2C),
        swatchColor = Color(0xFFEC4899)
    )

    val Orange = AccentPalette(
        primaryLight = Color(0xFFD9480F),
        onPrimaryLight = Color(0xFFFFFFFF),
        primaryContainerLight = Color(0xFFFFEDD5),
        onPrimaryContainerLight = Color(0xFF7C2D12),
        secondaryLight = Color(0xFFD97706),
        backgroundLight = Color(0xFFFFF9F5),
        surfaceLight = Color(0xFFFFFFFF),
        surfaceVariantLight = Color(0xFFFEF0E6),
        primaryDark = Color(0xFFFB923C),
        onPrimaryDark = Color(0xFF431407),
        primaryContainerDark = Color(0xFF9A3412),
        onPrimaryContainerDark = Color(0xFFFFEDD5),
        secondaryDark = Color(0xFFFBBF24),
        backgroundDark = Color(0xFF18110B),
        surfaceDark = Color(0xFF231911),
        surfaceVariantDark = Color(0xFF33251A),
        swatchColor = Color(0xFFF97316)
    )

    fun forOption(option: AccentColorOption): AccentPalette = when (option) {
        AccentColorOption.BLUE -> Blue
        AccentColorOption.PURPLE -> Purple
        AccentColorOption.GREEN -> Green
        AccentColorOption.PINK -> Pink
        AccentColorOption.ORANGE -> Orange
    }
}
