package com.mani.orbit.wear

import android.Manifest
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WatchSetupTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun trackingRequiresExplicitOptInAndRecoversAfterPermissionSettings() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val store = WatchStore(context)
        val previous = store.enabled()
        // The separate denial replay revokes permission before starting instrumentation, never mid-process.
        val denial = InstrumentationRegistry.getArguments().getString("setupPermissionMode") == "denied"
        if (denial) assertFalse(WatchPermissions.granted(context, Manifest.permission.ACTIVITY_RECOGNITION))
        else automation.grantRuntimePermission(context.packageName, Manifest.permission.ACTIVITY_RECOGNITION)
        store.enable(false)
        try {
            ActivityScenario.launch(WatchActivity::class.java).use { scenario ->
                compose.onNodeWithText("Turn on").assertIsDisplayed()
                scenario.recreate()
                compose.onNodeWithText("Turn on").assertIsDisplayed()
                assertFalse(store.enabled())
                compose.onNodeWithText("Turn on").performClick()
                compose.waitUntil { store.enabled() }
                if (denial) {
                    fun denyDialog() {
                        fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
                            if (node == null) return null
                            if (predicate(node)) return node
                            for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
                            return null
                        }
                        // The Wear permission dialog is Compose and its denial action starts below the fold.
                        var scroll: AccessibilityNodeInfo? = null
                        compose.waitUntil(10_000) {
                            val root = automation.rootInActiveWindow
                            scroll = if (root?.packageName == "com.google.android.permissioncontroller") find(root) {
                                it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
                            } else null
                            scroll != null
                        }
                        assertTrue(scroll!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                        var deny: AccessibilityNodeInfo? = null
                        compose.waitUntil(10_000) {
                            deny = find(automation.rootInActiveWindow) { it.text?.startsWith("Don") == true }
                            deny != null
                        }
                        while (deny?.isClickable == false) deny = deny?.parent
                        assertTrue(deny!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    }
                    denyDialog()
                    compose.onNodeWithText("Allow").assertIsDisplayed().performClick()
                    denyDialog()
                    compose.onNodeWithText("Settings").assertIsDisplayed().performClick()
                    compose.waitUntil(10_000) { automation.rootInActiveWindow?.packageName?.contains("settings") == true }
                    automation.grantRuntimePermission(context.packageName, Manifest.permission.ACTIVITY_RECOGNITION)
                    assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                }
                compose.onNodeWithTag("today-steps").assertIsDisplayed()
                compose.onNodeWithTag("today-setup").assertDoesNotExist()
                scenario.recreate()
                compose.onNodeWithTag("today-steps").assertIsDisplayed()
                assertTrue(store.enabled())
                repeat(2) { compose.onNodeWithTag("watch-home-pager").performTouchInput { swipeLeft() } }
                compose.onNodeWithText("Turn off").performScrollTo().performClick()
                compose.waitUntil { !store.enabled() }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithText("Turn on").performScrollTo().assertIsDisplayed()
                assertFalse(store.enabled())
            }
        } finally {
            store.enable(previous)
            WatchCollectionWorker.schedule(context)
        }
    }
}
