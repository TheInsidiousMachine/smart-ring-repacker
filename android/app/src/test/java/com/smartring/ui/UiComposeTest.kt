package com.smartring.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.smartring.core.ApiSettings
import com.smartring.core.ApiStatus
import com.smartring.core.Connection
import com.smartring.core.ConnectionState
import com.smartring.core.CustomGesture
import com.smartring.core.GestureAction
import com.smartring.core.GestureMessage
import com.smartring.core.MouseSettings
import com.smartring.core.RingMode
import com.smartring.core.RingStatus
import com.smartring.ui.screens.DisplayScreen
import com.smartring.ui.screens.GesturesScreen
import com.smartring.ui.screens.HealthScreen
import com.smartring.ui.screens.MouseScreen
import com.smartring.ui.screens.RingScreen
import com.smartring.ui.theme.RingTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Composes each tab under Robolectric. Bitmap capture is not available in this environment. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h1800dp-xxhdpi")
class UiComposeTest {
    @get:Rule
    val rule = createAndroidComposeRule<RingAppActivity>()

    @Test fun ringDark() = shot(dark = true) {
        RingScreen(
            connection = connected,
            status = status,
            clockText = "Nov 19, 10:19 PM",
            devMode = false,
            onConnectionClick = {},
            onMode = {},
            onSyncClock = {},
            onBrightness = {},
            onTimeout = {},
            onFind = {},
            onDevMode = {},
        )
    }

    @Test fun ringLight() = shot(dark = false) {
        RingScreen(
            connection = connected,
            status = status,
            clockText = "Nov 19, 10:19 PM",
            devMode = false,
            onConnectionClick = {},
            onMode = {},
            onSyncClock = {},
            onBrightness = {},
            onTimeout = {},
            onFind = {},
            onDevMode = {},
        )
    }

    @Test fun displayDark() = shot() {
        DisplayScreen(
            message = GestureMessage("Hi", scroll = false, msPerStep = 400, plays = 1),
            status = status,
            saved = listOf("HELLO", "TIME"),
            lastGesture = null,
            onMessage = {},
            onPlay = {},
            onDetector = {},
            onShowNow = {},
            onSave = {},
            onDeleteSaved = {},
            animate = false,
        )
    }

    @Test fun gesturesDark() = shot() {
        GesturesScreen(
            gestures = listOf(
                CustomGesture("g1", "Swipe right", 5, GestureAction.PcKeys("alt+tab")),
                CustomGesture("g2", "Circle", 1, GestureAction.None, enabled = false),
            ),
            listening = true,
            training = null,
            feed = emptyList(),
            feedTimes = emptyList(),
            apiSettings = ApiSettings(enabled = true, port = 8765, token = "abcd1234abcd1234abcd1234abcd1234", lan = true),
            apiStatus = ApiStatus(true, listOf("http://192.0.2.10:8765"), 0),
            onListening = {},
            onCreate = {},
            onRename = { _, _ -> },
            onDelete = {},
            onEnabled = { _, _ -> },
            onStrictness = { _, _ -> },
            onAction = { _, _ -> },
            onTest = {},
            onTrain = {},
            onStopTraining = {},
            onClearExamples = {},
            onApi = { null },
            onRegenerateToken = {},
        )
    }

    @Test fun mouseDark() = shot() {
        MouseScreen(settings = MouseSettings(speed = 62, curve = 70, deadzoneDeg = 4, rightClickHoldMs = 700), onChange = {})
    }

    @Test fun healthDark() = shot() {
        HealthScreen(mode = RingMode.HEART, bpm = 72, pulse = 0, onHeart = {})
    }

    private fun shot(dark: Boolean = true, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.runOnUiThread {
            rule.activity.setContent {
                RingTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    private companion object {
        val connected = Connection(ConnectionState.CONNECTED, "SY01", "Connected")
        val status = RingStatus(
            batteryPercent = 76,
            charging = false,
            firmwareVersion = "2.1.4",
            customBuild = "build 12 (b046ee55)",
            mode = RingMode.CLOCK,
            clockSyncedAtMs = 1_763_620_000_000,
            gestureDetectorOn = true,
            gestureFired = 4,
            gestureArmed = 9,
            screenBrightness = 2,
            screenTimeoutSec = 5,
        )
    }
}
