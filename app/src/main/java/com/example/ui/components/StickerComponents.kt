package com.example.ui.components

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemColors
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.example.data.model.StickerPackWithStickers
import com.example.media.StickerFontStyle
import com.example.media.StickerTextAlign
import com.example.media.StickerTypefaceResolver
import com.example.media.TextOverlayItem
import java.io.File
import java.util.UUID

@Composable
fun whatStickFilterChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.4f),
    disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
)

@Composable
fun whatStickFilterChipBorder(selected: Boolean): BorderStroke =
    FilterChipDefaults.filterChipBorder(
        enabled = true,
        selected = selected,
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        selectedBorderColor = MaterialTheme.colorScheme.primary,
        borderWidth = 1.dp,
        selectedBorderWidth = 1.dp
    )

@Composable
fun whatStickNavBarItemColors(): NavigationBarItemColors = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
fun whatStickNavRailItemColors(): NavigationRailItemColors = NavigationRailItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
fun whatStickSegmentedButtonColors(): SegmentedButtonColors = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primary,
    activeContentColor = MaterialTheme.colorScheme.onPrimary,
    activeBorderColor = MaterialTheme.colorScheme.primary,
    inactiveContainerColor = MaterialTheme.colorScheme.surface,
    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant
)

@Composable
fun CheckerboardBackground(
    modifier: Modifier = Modifier,
    cellSize: Dp = 14.dp
) {
    val dark = isSystemInDarkTheme()
    val lightCell = if (dark) Color(0xFF1F2735) else Color(0xFFF3F6FA)
    val darkCell = if (dark) Color(0xFF283244) else Color(0xFFE2E8F0)

    Spacer(
        modifier = modifier.drawWithCache {
            val px = cellSize.toPx().coerceAtLeast(8f)
            val cols = (size.width / px).toInt() + 1
            val rows = (size.height / px).toInt() + 1
            val darkCellsPath = Path().apply {
                for (r in 0 until rows) {
                    val top = r * px
                    val bottom = top + px
                    for (c in 0 until cols) {
                        if ((r + c) % 2 == 1) {
                            val left = c * px
                            addRect(Rect(left, top, left + px, bottom))
                        }
                    }
                }
            }
            onDrawBehind {
                drawRect(color = lightCell)
                drawPath(path = darkCellsPath, color = darkCell)
            }
        }
    )
}

@Composable
fun StickerFileImage(
    file: File,
    versionKey: Any,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    val context = LocalContext.current
    val request = remember(file.absolutePath, versionKey) {
        ImageRequest.Builder(context)
            .data(file)
            .setParameter("v", versionKey, memoryCacheKey = "${file.absolutePath}_$versionKey")
            .build()
    }
    val painter = rememberAsyncImagePainter(model = request)
    Image(
        painter = painter,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale
    )
}

@Composable
fun CreatePackDialog(
    initialAnimated: Boolean = false,
    allowTypeSwitch: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (name: String, publisher: String, isAnimated: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var publisher by remember { mutableStateOf("WhatStick") }
    var isAnimated by remember { mutableStateOf(initialAnimated) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Sticker Pack", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 64) name = it },
                    label = { Text("Pack name") },
                    placeholder = { Text(if (isAnimated) "Reaction GIFs" else "Daily Vibes") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pack_name_input")
                )
                OutlinedTextField(
                    value = publisher,
                    onValueChange = { if (it.length <= 64) publisher = it },
                    label = { Text("Author / Publisher") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pack_publisher_input")
                )
                if (allowTypeSwitch) {
                    Text("Sticker Type", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !isAnimated,
                            onClick = { isAnimated = false },
                            label = { Text("Static") },
                            colors = whatStickFilterChipColors(),
                            border = whatStickFilterChipBorder(!isAnimated),
                            leadingIcon = {
                                Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            modifier = Modifier.testTag("pack_type_static_chip")
                        )
                        FilterChip(
                            selected = isAnimated,
                            onClick = { isAnimated = true },
                            label = { Text("Animated") },
                            colors = whatStickFilterChipColors(),
                            border = whatStickFilterChipBorder(isAnimated),
                            leadingIcon = {
                                Icon(Icons.Outlined.Animation, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            modifier = Modifier.testTag("pack_type_animated_chip")
                        )
                    }
                    Text(
                        "WhatsApp keeps static and animated stickers in separate packs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = name.trim().ifEmpty {
                        if (isAnimated) "Animated Pack" else "My Sticker Pack"
                    }
                    onConfirm(finalName, publisher.trim().ifEmpty { "WhatStick" }, isAnimated)
                },
                modifier = Modifier.testTag("confirm_create_pack_button")
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PackPickerSaveDialog(
    packs: List<StickerPackWithStickers>,
    isAnimated: Boolean,
    initialPackId: String? = null,
    initialEmojis: String = "😄",
    onDismiss: () -> Unit,
    onCreateNewPackAndSave: (packName: String, publisher: String, emojis: String) -> Unit,
    onSelectPackAndSave: (packId: String, emojis: String) -> Unit
) {
    val compatiblePacks = remember(packs, isAnimated) {
        packs.filter { it.pack.isAnimated == isAnimated && it.stickers.size < 30 }
    }
    var selectedPackId by remember(compatiblePacks, initialPackId) {
        mutableStateOf(
            initialPackId?.takeIf { id -> compatiblePacks.any { it.pack.id == id } }
                ?: compatiblePacks.firstOrNull()?.pack?.id
        )
    }
    var createNewMode by remember(compatiblePacks) {
        mutableStateOf(compatiblePacks.isEmpty())
    }
    var newPackName by remember { mutableStateOf("") }
    var emojis by remember { mutableStateOf(initialEmojis.ifBlank { "😄" }) }

    val quickEmojis = listOf("😄", "😂", "❤️", "🔥", "🥳", "✨", "👍", "😎", "🥹", "💯")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isAnimated) "Save Animated Sticker" else "Save Static Sticker",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Emoji tags (for WhatsApp search)", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickEmojis.forEach { emoji ->
                        val active = emojis.contains(emoji)
                        Surface(
                            shape = CircleShape,
                            color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            border = if (active) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable {
                                    val list = emojis.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                                    if (list.contains(emoji)) {
                                        if (list.size > 1) list.remove(emoji)
                                    } else {
                                        if (list.size >= 3) list.removeAt(0)
                                        list.add(emoji)
                                    }
                                    emojis = list.joinToString(",")
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(emoji, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = emojis,
                    onValueChange = { if (it.length <= 24) emojis = it },
                    label = { Text("Emojis (comma separated, max 3)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Destination Pack", style = MaterialTheme.typography.labelLarge)
                if (compatiblePacks.isNotEmpty()) {
                    compatiblePacks.forEach { entry ->
                        val isSelected = !createNewMode && selectedPackId == entry.pack.id
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                }
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    createNewMode = false
                                    selectedPackId = entry.pack.id
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        createNewMode = false
                                        selectedPackId = entry.pack.id
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.pack.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${entry.stickers.size}/30 stickers",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { createNewMode = !createNewMode },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("toggle_new_pack_in_save")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (createNewMode) "Creating New Pack" else "Create New Pack Instead")
                }

                if (createNewMode) {
                    OutlinedTextField(
                        value = newPackName,
                        onValueChange = { if (it.length <= 64) newPackName = it },
                        label = { Text("New pack name") },
                        placeholder = { Text(if (isAnimated) "My Animated Pack" else "My Sticker Pack") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("save_new_pack_name_input")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val safeEmojis = emojis.ifBlank { "😄" }
                    if (createNewMode || selectedPackId == null) {
                        val finalName = newPackName.trim().ifEmpty {
                            if (isAnimated) "Animated Pack" else "My Sticker Pack"
                        }
                        onCreateNewPackAndSave(finalName, "WhatStick", safeEmojis)
                    } else {
                        onSelectPackAndSave(selectedPackId!!, safeEmojis)
                    }
                },
                modifier = Modifier.testTag("confirm_save_sticker_button")
            ) {
                Text("Save Sticker")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun TextOverlayEditorDialog(
    initialItem: TextOverlayItem? = null,
    onDismiss: () -> Unit,
    onConfirm: (TextOverlayItem) -> Unit
) {
    var text by remember { mutableStateOf(initialItem?.text ?: "") }
    var fontStyle by remember { mutableStateOf(initialItem?.fontStyle ?: StickerFontStyle.SANS) }
    var fontSize by remember { mutableFloatStateOf(initialItem?.fontSizeSp ?: 42f) }
    var isBold by remember { mutableStateOf(initialItem?.isBold ?: true) }
    var isItalic by remember { mutableStateOf(initialItem?.isItalic ?: false) }
    var alignment by remember { mutableStateOf(initialItem?.alignment ?: StickerTextAlign.CENTER) }
    var hasOutline by remember { mutableStateOf(initialItem?.hasOutline ?: true) }
    var textColor by remember { mutableIntStateOf(initialItem?.colorArgb ?: Color.White.toArgb()) }
    var outlineColor by remember { mutableIntStateOf(initialItem?.outlineColorArgb ?: Color.Black.toArgb()) }

    val colorSwatches = listOf(
        Color.White,
        Color.Black,
        Color(0xFF3B82F6),
        Color(0xFFEF4444),
        Color(0xFFF59E0B),
        Color(0xFF10B981),
        Color(0xFFEC4899),
        Color(0xFF8B5CF6)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialItem == null) "Add Text" else "Edit Text") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Sticker caption") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sticker_text_input")
                )

                Text("Font Style", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StickerFontStyle.entries.forEach { style ->
                        val selected = fontStyle == style
                        val previewFamily = remember(style) {
                            StickerTypefaceResolver.composeFontFamily(style)
                        }
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            border = BorderStroke(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { fontStyle = style }
                                .testTag("font_option_${style.name.lowercase()}")
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "Aa",
                                    fontFamily = previewFamily,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    text = style.label,
                                    fontFamily = previewFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = isBold,
                            onClick = { isBold = !isBold },
                            colors = whatStickFilterChipColors(),
                            border = whatStickFilterChipBorder(isBold),
                            label = { Icon(Icons.Default.FormatBold, contentDescription = "Bold") }
                        )
                        FilterChip(
                            selected = isItalic,
                            onClick = { isItalic = !isItalic },
                            colors = whatStickFilterChipColors(),
                            border = whatStickFilterChipBorder(isItalic),
                            label = { Icon(Icons.Default.FormatItalic, contentDescription = "Italic") }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            StickerTextAlign.LEFT to (Icons.Default.FormatAlignLeft to "Align Left"),
                            StickerTextAlign.CENTER to (Icons.Default.FormatAlignCenter to "Align Center"),
                            StickerTextAlign.RIGHT to (Icons.Default.FormatAlignRight to "Align Right")
                        ).forEach { (alignOpt, iconPair) ->
                            val selected = alignment == alignOpt
                            Surface(
                                shape = CircleShape,
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            ) {
                                IconButton(onClick = { alignment = alignOpt }) {
                                    Icon(
                                        iconPair.first,
                                        contentDescription = iconPair.second,
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Text("Size (${fontSize.toInt()}sp)", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    valueRange = 18f..88f
                )

                Text("Text Color", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    colorSwatches.forEach { c ->
                        val argb = c.toArgb()
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (textColor == argb) 3.dp else 1.dp,
                                    color = if (textColor == argb) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                    shape = CircleShape
                                )
                                .clickable { textColor = argb },
                            contentAlignment = Alignment.Center
                        ) {
                            if (textColor == argb) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = if (c == Color.White) Color.Black else Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Text Outline", style = MaterialTheme.typography.labelLarge)
                    Switch(checked = hasOutline, onCheckedChange = { hasOutline = it })
                }

                if (hasOutline) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        colorSwatches.forEach { c ->
                            val argb = c.toArgb()
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(
                                        width = if (outlineColor == argb) 3.dp else 1.dp,
                                        color = if (outlineColor == argb) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                        shape = CircleShape
                                    )
                                    .clickable { outlineColor = argb }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        onConfirm(
                            TextOverlayItem(
                                id = initialItem?.id ?: UUID.randomUUID().toString(),
                                text = trimmed,
                                fontStyle = fontStyle,
                                fontSizeSp = fontSize,
                                colorArgb = textColor,
                                outlineColorArgb = outlineColor,
                                hasOutline = hasOutline,
                                isBold = isBold,
                                isItalic = isItalic,
                                alignment = alignment,
                                normX = initialItem?.normX ?: 0.5f,
                                normY = initialItem?.normY ?: 0.78f,
                                rotationDeg = initialItem?.rotationDeg ?: 0f
                            )
                        )
                    } else {
                        onDismiss()
                    }
                },
                modifier = Modifier.testTag("confirm_text_overlay_button")
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
