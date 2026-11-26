package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.data.model.AccentColorOption
import com.example.data.model.ThemeMode
import com.example.ui.screens.AnimatedEditorScreen
import com.example.ui.screens.StaticEditorScreen
import com.example.ui.theme.WhatStickTheme
import com.example.ui.viewmodel.AnimatedEditorViewModel
import com.example.ui.viewmodel.StaticEditorViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WhatStickEditorUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `static editor removes AI cutout keeps erase and restore brushes and previews all curated fonts`() {
        val app = ApplicationProvider.getApplicationContext<WhatStickApp>()
        val vm = StaticEditorViewModel(app)
        runBlocking {
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).drawColor(Color.CYAN)
            }
            vm.initWithBitmap(bmp, packId = null, stickerId = null, emojis = "😄")
        }

        composeTestRule.setContent {
            WhatStickTheme(accentColor = AccentColorOption.BLUE, themeMode = ThemeMode.LIGHT) {
                StaticEditorScreen(
                    viewModel = vm,
                    packs = emptyList(),
                    initialTargetPackId = null,
                    onCreatePack = { _, _, _ -> "p1" },
                    onSavedToPack = {},
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // AI Cutout options must be completely removed
        assertEquals(0, composeTestRule.onAllNodesWithText("AI Cutout").fetchSemanticsNodes().size)

        // Open Erase tab and verify Smart Object, Erase Brush, Restore Brush, and Reset are available
        composeTestRule.onNodeWithTag("static_tool_eraser").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("smart_object_button").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Smart Object").assertIsDisplayed()
        composeTestRule.onNodeWithTag("eraser_erase_brush_chip").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("eraser_restore_brush_chip").performScrollTo().assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("eraser_reset_button").performScrollTo().assertIsDisplayed().performClick()

        // Open Text tab, launch Text dialog, verify live curated font previews, and apply text
        composeTestRule.onNodeWithTag("static_tool_text").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("add_text_overlay_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("font_option_sans").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("font_option_comic").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("font_option_display").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("font_option_script").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("font_option_serif").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("font_option_mono").performScrollTo().assertIsDisplayed().performClick()

        composeTestRule.onNodeWithTag("sticker_text_input").performScrollTo().performTextInput("WOW")
        composeTestRule.onNodeWithTag("confirm_text_overlay_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("edit_selected_text_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `animated editor has no bottom AI cutout notice banner`() {
        val app = ApplicationProvider.getApplicationContext<WhatStickApp>()
        val vm = AnimatedEditorViewModel(app)

        composeTestRule.setContent {
            WhatStickTheme(accentColor = AccentColorOption.PURPLE, themeMode = ThemeMode.DARK) {
                AnimatedEditorScreen(
                    viewModel = vm,
                    packs = emptyList(),
                    initialTargetPackId = null,
                    onCreatePack = { _, _, _ -> "p1" },
                    onSavedToPack = {},
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(
            0,
            composeTestRule.onAllNodesWithText(
                "GIF transparency is preserved automatically. AI background removal is available for static photo stickers."
            ).fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithText("Trim & Speed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Crop & Zoom").assertIsDisplayed()
        composeTestRule.onNodeWithText("Text").assertIsDisplayed()
    }

    @Test
    fun `all accent color themes bind secondaryContainer to accent primaryContainer without grey fallback`() {
        val results = mutableListOf<Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>>()
        composeTestRule.setContent {
            AccentColorOption.entries.forEach { option ->
                ThemeMode.entries.filter { it != ThemeMode.SYSTEM }.forEach { mode ->
                    WhatStickTheme(accentColor = option, themeMode = mode) {
                        results += MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.secondaryContainer
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals(10, results.size)
        results.forEach { (primaryContainer, secondaryContainer) ->
            assertEquals(primaryContainer, secondaryContainer)
            assertNotEquals(androidx.compose.ui.graphics.Color(0xFFE8DEF8), secondaryContainer)
        }
    }

    @Test
    @Config(sdk = [36])
    fun `pack detail screen renders unclipped action buttons and clean gallery thumbnails without checkerboard`() {
        val app = ApplicationProvider.getApplicationContext<WhatStickApp>()
        val pack = runBlocking {
            val created = app.repository.createPack("Gallery Fixes Pack", "WhatStick", isAnimated = false)
            val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                setHasAlpha(true)
                val c = Canvas(this)
                c.drawColor(Color.TRANSPARENT)
                c.drawCircle(256f, 256f, 180f, android.graphics.Paint().apply { color = Color.BLUE })
            }
            val bytes = com.example.media.StaticStickerRenderer.encodeWebpUnder100Kb(bmp).getOrThrow()
            bmp.recycle()
            repeat(3) { idx ->
                app.repository.addOrUpdateSticker(created.id, bytes, isAnimated = false, emojis = "😎")
            }
            app.repository.getPackOnce(created.id)!!
        }
        val firstSticker = pack.sortedStickers.first()

        composeTestRule.setContent {
            val baseDensity = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides Density(
                    density = baseDensity.density,
                    fontScale = 1.3f
                )
            ) {
                WhatStickTheme(accentColor = AccentColorOption.BLUE, themeMode = ThemeMode.LIGHT) {
                    com.example.ui.screens.PackDetailScreen(
                        packWithStickers = pack,
                        storage = app.repository.storage,
                        onBack = {},
                        onAddStickerClick = {},
                        onEditStaticSticker = { _, _ -> },
                        onRenamePack = { _, _, _ -> },
                        onDeleteSticker = {},
                        onSetAsTrayIcon = { _, _ -> },
                        onUpdateTrayIconFromUri = { _, _ -> Result.success(Unit) },
                        onUpdateStickerEmojis = { _, _ -> },
                        onDeletePack = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val addStickerBounds = composeTestRule.onNodeWithTag("pack_add_sticker_button")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val addWhatsAppBounds = composeTestRule.onNodeWithTag("pack_add_to_whatsapp_button")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        composeTestRule.onNodeWithText("Add Sticker").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add to WhatsApp").assertIsDisplayed()

        // Both buttons must meet >= 48dp touch height and match height cleanly without clipping
        val minPx = with(composeTestRule.density) { 48.dp.toPx() }
        org.junit.Assert.assertTrue("Add Sticker height=${addStickerBounds.height} < $minPx", addStickerBounds.height >= minPx)
        org.junit.Assert.assertTrue("Add to WhatsApp height=${addWhatsAppBounds.height} < $minPx", addWhatsAppBounds.height >= minPx)
        assertEquals(addStickerBounds.height, addWhatsAppBounds.height, 2f)

        // Gallery tile renders cleanly and opens individual preview dialog on tap
        composeTestRule.onNodeWithTag("pack_sticker_tile_${firstSticker.id}").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Sticker Preview").assertIsDisplayed()
        composeTestRule.onNodeWithTag("preview_edit_sticker_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Tray Icon").performScrollTo().assertIsDisplayed()

        runBlocking { app.repository.deletePack(pack.pack.id) }
    }

    @Test
    @Config(sdk = [36], qualifiers = "w500dp-h700dp-xxhdpi")
    fun `pack detail screen aligns side by side action buttons cleanly at normal font scale`() {
        val app = ApplicationProvider.getApplicationContext<WhatStickApp>()
        val pack = runBlocking {
            val created = app.repository.createPack("SideBySide Pack", "WhatStick", isAnimated = false)
            val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
                setHasAlpha(true)
                val c = Canvas(this)
                c.drawColor(Color.TRANSPARENT)
                c.drawCircle(256f, 256f, 180f, android.graphics.Paint().apply { color = Color.RED })
            }
            val bytes = com.example.media.StaticStickerRenderer.encodeWebpUnder100Kb(bmp).getOrThrow()
            bmp.recycle()
            repeat(3) {
                app.repository.addOrUpdateSticker(created.id, bytes, isAnimated = false, emojis = "🔥")
            }
            app.repository.getPackOnce(created.id)!!
        }

        composeTestRule.setContent {
            WhatStickTheme(accentColor = AccentColorOption.BLUE, themeMode = ThemeMode.DARK) {
                com.example.ui.screens.PackDetailScreen(
                    packWithStickers = pack,
                    storage = app.repository.storage,
                    onBack = {},
                    onAddStickerClick = {},
                    onEditStaticSticker = { _, _ -> },
                    onRenamePack = { _, _, _ -> },
                    onDeleteSticker = {},
                    onSetAsTrayIcon = { _, _ -> },
                    onUpdateTrayIconFromUri = { _, _ -> Result.success(Unit) },
                    onUpdateStickerEmojis = { _, _ -> },
                    onDeletePack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        val addStickerBounds = composeTestRule.onNodeWithTag("pack_add_sticker_button")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val addWhatsAppBounds = composeTestRule.onNodeWithTag("pack_add_to_whatsapp_button")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot

        val minPx = with(composeTestRule.density) { 48.dp.toPx() }
        org.junit.Assert.assertTrue(addStickerBounds.height >= minPx)
        org.junit.Assert.assertTrue(addWhatsAppBounds.height >= minPx)
        // Side-by-side: equal height and same vertical band
        assertEquals(addStickerBounds.height, addWhatsAppBounds.height, 2f)
        assertEquals(addStickerBounds.top, addWhatsAppBounds.top, 2f)
        org.junit.Assert.assertTrue(addWhatsAppBounds.left >= addStickerBounds.right)

        runBlocking { app.repository.deletePack(pack.pack.id) }
    }

    @Test
    fun `static editor shows horizontal and vertical stretch sliders reset stretch and responsive outline slider`() {
        val app = ApplicationProvider.getApplicationContext<WhatStickApp>()
        val vm = StaticEditorViewModel(app)
        runBlocking {
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).drawColor(Color.GREEN)
            }
            vm.initWithBitmap(bmp, packId = null, stickerId = null, emojis = "🟩")
        }

        composeTestRule.setContent {
            WhatStickTheme(accentColor = AccentColorOption.BLUE, themeMode = ThemeMode.LIGHT) {
                StaticEditorScreen(
                    viewModel = vm,
                    packs = emptyList(),
                    initialTargetPackId = null,
                    onCreatePack = { _, _, _ -> "p1" },
                    onSavedToPack = {},
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Default Crop tab displays Horizontal Stretch & Vertical Stretch at 100%
        composeTestRule.onNodeWithText("Horizontal Stretch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Vertical Stretch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("static_horizontal_stretch_slider")
            .performScrollTo()
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        composeTestRule.onNodeWithTag("static_vertical_stretch_slider")
            .performScrollTo()
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
        composeTestRule.waitForIdle()

        assertEquals(1.5f, vm.uiState.value.document.stretchX, 0.02f)
        assertEquals(0.75f, vm.uiState.value.document.stretchY, 0.02f)
        composeTestRule.onNodeWithText("150%").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("75%").performScrollTo().assertIsDisplayed()

        // Reset Stretch restores 100% / 100%
        composeTestRule.onNodeWithTag("static_reset_stretch_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1f, vm.uiState.value.document.stretchX, 0.01f)
        assertEquals(1f, vm.uiState.value.document.stretchY, 0.01f)

        // Outline tab slider updates immediately
        composeTestRule.onNodeWithTag("static_tool_outline").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("outline_thickness_slider")
            .performScrollTo()
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(14f) }
        composeTestRule.waitForIdle()
        assertEquals(14f, vm.uiState.value.document.outlineThicknessPx, 0.1f)
        composeTestRule.onNodeWithText("Border 14px").assertIsDisplayed()
    }

    @Test
    fun `animated editor crop tab provides horizontal and vertical stretch sliders and reset stretch`() {
        val app = ApplicationProvider.getApplicationContext<WhatStickApp>()
        val vm = AnimatedEditorViewModel(app)

        composeTestRule.setContent {
            WhatStickTheme(accentColor = AccentColorOption.GREEN, themeMode = ThemeMode.LIGHT) {
                AnimatedEditorScreen(
                    viewModel = vm,
                    packs = emptyList(),
                    initialTargetPackId = null,
                    onCreatePack = { _, _, _ -> "p1" },
                    onSavedToPack = {},
                    onBack = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("animated_tool_crop").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Horizontal Stretch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Vertical Stretch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("animated_horizontal_stretch_slider")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.6f) }
        composeTestRule.onNodeWithTag("animated_vertical_stretch_slider")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.8f) }
        composeTestRule.waitForIdle()

        assertEquals(1.6f, vm.uiState.value.stretchX, 0.02f)
        assertEquals(0.8f, vm.uiState.value.stretchY, 0.02f)
        composeTestRule.onNodeWithText("160%").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("80%").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("animated_reset_stretch_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1f, vm.uiState.value.stretchX, 0.01f)
        assertEquals(1f, vm.uiState.value.stretchY, 0.01f)
    }
}
