package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WhatStickUiRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `navigate bottom tabs create pack and switch theme and accent color`() {
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("home_create_sticker_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_entry_photo").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_entry_gif").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_entry_video").assertIsDisplayed()

        composeTestRule.onNodeWithTag("nav_tab_settings").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("accent_option_purple").performClick()
        composeTestRule.onNodeWithTag("theme_mode_dark").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("theme_mode_light").performClick()
        composeTestRule.onNodeWithTag("accent_option_blue").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("nav_tab_my_stickers").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("my_stickers_create_pack_fab").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("pack_name_input").performTextInput("Weekend Pack")
        composeTestRule.onNodeWithTag("confirm_create_pack_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule
                .onAllNodesWithTag("pack_add_to_whatsapp_button")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onAllNodesWithText("Weekend Pack").onFirst().assertIsDisplayed()
        composeTestRule.onNodeWithTag("pack_add_to_whatsapp_button").assertExists()
    }
}
