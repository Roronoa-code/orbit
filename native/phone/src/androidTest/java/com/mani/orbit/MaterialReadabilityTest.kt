package com.mani.orbit

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class MaterialReadabilityTest {
    @Volatile private var observed: GlassReadability? = null
    @Before fun emulatorOnly() { check(Build.HARDWARE in setOf("ranchu", "goldfish")) }

    @Test fun platformContrastCallbacksAndCompositionRestorationUsePublicSignals() {
        assumeTrue(Build.VERSION.SDK_INT >= 36)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        // These AOSP settings are only emulator stimulus. Production uses public manager APIs.
        val keys = listOf("contrast_level", "high_text_contrast_enabled")
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText().trim() }
        val previous = keys.associateWith { shell("settings --user current get secure $it") }
        require(previous.values.all { it == "null" || it.matches(Regex("-?[0-9]+(?:\\.[0-9]+)?")) })
        val preference = mutableStateOf(MaterialReadability())
        val visible = mutableStateOf(true)
        fun setting(index: Int, value: String) {
            assertEquals("", shell("settings --user current put secure ${keys[index]} $value"))
        }
        fun expect(value: GlassReadability) {
            try {
                val deadline = android.os.SystemClock.elapsedRealtime() + 5000
                while (observed != value && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
                assertEquals(value, observed)
            }
            catch (failure: Throwable) {
                throw AssertionError("Expected $value, observed $observed; platform contrast=" +
                    application.getSystemService(android.app.UiModeManager::class.java).contrast +
                    ", text=" + application.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isHighContrastTextEnabled +
                    ", raw=" + keys.associateWith { shell("settings --user current get secure $it") }, failure)
            }
        }
        val content: @Composable () -> Unit = {
            val policy = if (visible.value) rememberGlassReadability(preference.value) else null
            if (policy != null) {
                androidx.compose.material3.Text("Contrast ${policy.contrast}, opaque ${policy.opaque}")
            }
            SideEffect { observed = policy }
        }
        // Android may recreate the Activity for contrast changes (CONFIG_ASSETS_PATHS).
        // Reinstall the fixture on create, just as MainActivity installs OrbitApp in onCreate.
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityPostCreated(activity: Activity, state: Bundle?) {
                if (activity.javaClass == ComponentActivity::class.java) (activity as ComponentActivity).setContent(content = content)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            setting(0, "0"); setting(1, "0")
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            expect(GlassReadability(false, 0f))
            setting(0, "0.5")
            expect(GlassReadability(false, .5f))
            setting(1, "1")
            expect(GlassReadability(false, 1f))
            instrumentation.runOnMainSync { preference.value = MaterialReadability(true, false) }
            expect(GlassReadability(true, 1f))
            setting(1, "0")
            expect(GlassReadability(true, .5f))
            // Observe native composition directly. Espresso's next-frame idle barrier can spin
            // indefinitely after the entire fixture disappears during a configuration change.
            instrumentation.runOnMainSync { visible.value = false }
            val deadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (observed != null && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertNull(observed)
            setting(0, "1")
            instrumentation.runOnMainSync { visible.value = true }
            expect(GlassReadability(true, 1f))
            scenario.recreate()
            expect(GlassReadability(true, 1f))
            }
        } finally {
            try {
                previous.forEach { (key, value) ->
                    if (value == "null") shell("settings --user current delete secure $key")
                    else shell("settings --user current put secure $key $value")
                    assertEquals(value, shell("settings --user current get secure $key"))
                }
            } finally { application.unregisterActivityLifecycleCallbacks(callbacks) }
        }
    }
}
