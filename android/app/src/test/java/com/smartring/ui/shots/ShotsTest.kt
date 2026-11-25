package com.smartring.ui.shots

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.smartring.core.FakeGestureStudio
import com.smartring.core.FakeRingController
import com.smartring.core.RingServices
import com.smartring.ui.RingApp
import com.smartring.ui.RingAppActivity
import com.smartring.ui.theme.RingTheme
import com.smartring.ui.vm.RingAppViewModel
import androidx.activity.compose.setContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Integrator review screenshots (only when -Dshots.dir is set). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h900dp-night-xxhdpi")
class ShotsTest {
    @get:Rule val rule = createAndroidComposeRule<RingAppActivity>()

    @Test fun tabs() {
        val dir = System.getProperty("shots.dir") ?: System.getenv("SHOTS_DIR") ?: return
        val ring = FakeRingController().also { it.connect() }
        val studio = FakeGestureStudio()
        val vm = ViewModelProvider(rule.activity, RingAppViewModel.factory(rule.activity.application, ring, studio))["shots", RingAppViewModel::class.java]
        rule.mainClock.autoAdvance = false          // the UI has infinite animations; step time by hand
        rule.activity.setContent { RingTheme { RingApp(vm = vm, onConnect = {}) } }
        rule.mainClock.advanceTimeBy(500)
        for (tab in listOf("Ring", "Display", "Gestures", "Mouse", "Health")) {
            rule.onAllNodesWithText(tab)[0].performClick()
            rule.mainClock.advanceTimeBy(800)
            val v = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            File(dir).mkdirs()
            File(dir, "$tab.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
