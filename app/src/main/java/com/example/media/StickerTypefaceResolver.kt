package com.example.media

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import com.example.R

object StickerTypefaceResolver {

    fun fontResId(style: StickerFontStyle): Int = when (style) {
        StickerFontStyle.SANS -> R.font.plus_jakarta_sans
        StickerFontStyle.COMIC -> R.font.bangers
        StickerFontStyle.DISPLAY -> R.font.outfit
        StickerFontStyle.SCRIPT -> R.font.pacifico
        StickerFontStyle.SERIF -> R.font.playfair_display
        StickerFontStyle.MONO -> R.font.jetbrains_mono
    }

    fun composeFontFamily(style: StickerFontStyle): FontFamily =
        FontFamily(Font(resId = fontResId(style), weight = FontWeight.Normal, style = FontStyle.Normal))

    fun resolve(
        context: Context,
        style: StickerFontStyle,
        bold: Boolean,
        italic: Boolean
    ): Typeface {
        val base = try {
            ResourcesCompat.getFont(context, fontResId(style)) ?: Typeface.DEFAULT
        } catch (_: Exception) {
            Typeface.DEFAULT
        }
        val tfStyle = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(base, tfStyle)
    }
}
