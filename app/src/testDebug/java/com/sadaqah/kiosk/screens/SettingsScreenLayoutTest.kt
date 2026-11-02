package com.sadaqah.kiosk.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.sadaqah.kiosk.TranslationManager
import com.sadaqah.kiosk.model.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the Lenovo TB305FU in landscape (1340x800 px at 180 dpi). responsiveDp scales
 * by width only, so this short, wide screen is where the right column overflows first.
 * Debug-only: createComposeRule needs the ComponentActivity that ui-test-manifest
 * registers, and that manifest is merged into the debug variant alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1191dp-h711dp-land-180dpi")
class SettingsScreenLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val strings = TranslationManager.currentStrings()

    @Test
    fun theDangerZoneActionsCanBeScrolledIntoViewOnATB305FU() {
        compose.setContent {
            SettingsScreen(
                settings = Settings(),
                onSettingsChange = {},
                openColorPicker = {},
                onResetApp = {},
                onBack = {},
                onRefresh = {},
                onExportSettings = { _, _ -> "" },
                onImportSettings = { _, _ -> error("unused") },
                onImportSettingsOnly = { error("unused") },
                connectCardReader = {},
                isLoggedIn = true,
                isPinned = true,
                onUnpinApp = {},
                onPinApp = {},
                onShowSetupStatus = {},
                onActivateScreensaver = {},
                onTestModeChange = {},
                onLogout = {}
            )
        }

        compose.onNodeWithText(strings.dangerZone, substring = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        compose.onNodeWithText(strings.logOut).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(strings.resetApp).performScrollTo().assertIsDisplayed()
    }
}
