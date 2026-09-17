package com.mani.orbit

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class SettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    private fun original() = JSONObject().put("name", "Orbit check").put("birthDate", "1999-03-11")
        .put("heightCm", 178.0).put("weightKg", 84.0).put("retained", "original")
    private fun save(name: String) = rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
        File(rule.activity.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun withModel(block: (OrbitModel, AppPreferences) -> Unit) {
        val app = rule.activity.application
        val prefs = AppPreferences(app)
        val keys = listOf("orbit-profile-v1", "orbit-steps-goal-v1", "orbit-reduce-motion-v1", MaterialReadability.KEY)
        val previous = keys.associateWith(prefs::read)
        val owner = ViewModelStore()
        try {
            assertTrue(prefs.write(keys[0], original().toString()))
            assertTrue(prefs.write(keys[1], "10000")); assertTrue(prefs.write(keys[2], "false"))
            assertTrue(prefs.write(MaterialReadability.KEY, MaterialReadability.document(null).toString()))
            val model = OrbitModel(app, SavedStateHandle()); owner.put("settings", model)
            rule.waitUntil(5000) { model.profile.value != null && model.stepsGoal.value != null && model.readability.value != null }
            block(model, prefs)
        } finally {
            rule.runOnUiThread { owner.clear() }
            val editor = app.getSharedPreferences("orbit-settings", Context.MODE_PRIVATE).edit()
            previous.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
            assertTrue(editor.commit())
        }
    }

    @Test fun profileAndGoalValidationPreserveOriginalsAndPersistAcknowledgedValues() = withModel { model, prefs ->
        val initial = prefs.read("orbit-profile-v1")
        runBlocking {
            for (birth in listOf("31022000", "29022001", LocalDate.now().plusDays(1).toString(), "1899-12-31"))
                assertNotNull(model.saveProfile("Orbit", birth, "178", "84"))
            assertNotNull(model.saveProfile("Orbit", "29022000", "178.25", "84"))
            assertNotNull(model.saveProfile("Orbit", "29022000", "NaN", "84"))
            assertNotNull(model.saveProfile("Orbit", "29022000", "178", "400"))
            assertNotNull(model.saveProfile("Orbit", "29022000", "178", "84", "unknown"))
            assertEquals(initial, prefs.read("orbit-profile-v1"))
            assertNull(model.saveProfile("Orbit", "29022000", "180.5", "85.2"))
            val saved = JSONObject(prefs.read("orbit-profile-v1")!!)
            assertEquals("2000-02-29", saved.getString("birthDate")); assertEquals("original", saved.getString("retained"))
            assertEquals(85.2, model.profile.value!!.getDouble("weightKg"), 0.0)
            for (goal in listOf("99", "100001", "12550", "NaN", "100.0")) assertNotNull(model.saveGoal(goal))
            assertEquals("10000", prefs.read("orbit-steps-goal-v1"))
            assertNull(model.saveGoal("12500")); assertEquals(12500, model.stepsGoal.value)
            assertNull(model.saveReducedMotion(true)); assertTrue(model.reducedMotion.value); assertFalse(model.globeRotation.value)
            assertEquals("true", prefs.read("orbit-reduce-motion-v1"))
            assertNull(model.saveProfile("", "", "", ""))
            assertTrue(model.profile.value!!.isNull("weightKg"))
            assertNull(model.saveProfile("", "", "", "")) // Stored JSON null is still an omitted sex setting.
            assertNull(model.saveProfile("Orbit", "29022000", "180", "85", "female"))
            assertNull(model.saveProfile("Orbit", "29022000", "180", "86"))
            assertEquals("female", model.profile.value!!.getString("sex"))
            assertNull(model.saveProfile("Orbit", "29022000", "180", "86", null))
            assertTrue(model.profile.value!!.isNull("sex"))
        }
        assertTrue(prefs.write("orbit-profile-v1", "damaged original"))
        model.reloadProfile()
        rule.waitUntil(5000) { model.profileError.value != null }
        assertNull(model.profile.value)
        runBlocking { assertNotNull(model.saveProfile("Replace", "", "", "")) }
        assertEquals("damaged original", prefs.read("orbit-profile-v1"))
    }

    @Test fun readabilityPreferencesRemainIndependentAndPreserveFutureOrDamagedOriginals() = withModel { model, prefs ->
        val original = MaterialReadability.document(null).put("retained", "future optional field")
        assertTrue(prefs.write(MaterialReadability.KEY, original.toString()))
        runBlocking {
            assertNull(model.saveReadability(reduceTransparency = true))
            assertNull(model.saveReadability(increaseContrast = true))
            assertNull(model.saveReducedMotion(true))
            assertEquals(MaterialReadability(true, true), model.readability.value)
            assertNull(model.saveReadability(reduceTransparency = false))
            assertTrue(model.reducedMotion.value)
            assertEquals(MaterialReadability(false, true), model.readability.value)
        }
        model.reloadProfile()
        rule.waitUntil(5000) { model.readability.value == MaterialReadability(false, true) }
        assertEquals("future optional field", JSONObject(prefs.read(MaterialReadability.KEY)!!).getString("retained"))
        val recreated = OrbitModel(rule.activity.application, SavedStateHandle())
        val owner = ViewModelStore().also { it.put("readability-reload", recreated) }
        try {
            rule.waitUntil(5000) { recreated.readability.value == MaterialReadability(false, true) }
            assertTrue(recreated.reducedMotion.value)
        } finally { rule.runOnUiThread { owner.clear() } }
        for (bad in listOf("broken", original.put("version", 2).toString(),
            original.put("version", 1).put("increaseContrast", "true").toString())) {
            assertTrue(prefs.write(MaterialReadability.KEY, bad))
            runBlocking { assertNotNull(model.saveReadability(reduceTransparency = true)) }
            assertEquals(bad, prefs.read(MaterialReadability.KEY))
            model.reloadProfile()
            rule.waitUntil(5000) { model.readabilityError.value != null }
            assertNull(model.readability.value)
        }
        assertTrue(GlassReadability.resolve(null, 0f, false).opaque)
        assertEquals(1f, GlassReadability.resolve(MaterialReadability(), 0f, true).contrast)
        assertEquals(.5f, GlassReadability.resolve(MaterialReadability(), .5f, false).contrast)
        assertEquals(0f, GlassReadability.resolve(MaterialReadability(), Float.NaN, false).contrast)
        assertFalse(GlassReadability.resolve(MaterialReadability(false, true), 0f, false).opaque)
    }

    @Test fun birthMaskKeepsAllCaretPositionsValid() {
        for (length in 0..8) {
            val result = BirthDateMask.filter(AnnotatedString("11031999".take(length)))
            for (i in 0..length) {
                val shown = result.offsetMapping.originalToTransformed(i)
                assertTrue(shown in 0..result.text.length)
                assertEquals(i, result.offsetMapping.transformedToOriginal(shown))
            }
            for (i in 0..result.text.length) assertTrue(result.offsetMapping.transformedToOriginal(i) in 0..length)
        }
        assertEquals("11/03/1999", BirthDateMask.filter(AnnotatedString("11031999")).text.text)
    }

    @Test fun failedProfileSaveKeepsTheDraftAndLargeTextFieldsRemainEditable() {
        var received = emptyList<String>(); var fail = true
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
                    Column(Modifier.width(320.dp).fillMaxHeight().background(Color(0xFF0B0A0F)).verticalScroll(rememberScrollState()).padding(22.dp)) {
                        ProfileEditor(original(), null, {}) { name, birth, height, weight, _ ->
                            received = listOf(name, birth, height, weight)
                            if (fail) "Could not save. Please try again." else null
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("settings-input-Date of birth").performTextReplacement("29022000")
        rule.onNodeWithTag("settings-input-Weight").performTextReplacement("85.2")
        rule.onNodeWithText("Save profile").performScrollTo().performClick()
        rule.onNodeWithText("Could not save. Please try again.").assertExists()
        rule.onNodeWithTag("settings-input-Weight").assertTextEquals("85.2")
        assertEquals(listOf("Orbit check", "29022000", "178.0", "85.2"), received)
        rule.runOnIdle { fail = false }
        rule.onNodeWithText("Save profile").performScrollTo().performClick()
        rule.onNodeWithText("Profile saved").assertExists()
        save("settings-profile-large.png")
    }

    @Test fun completeSettingsUseCurrentConnectionStateAndQuietDisclosureControls() = withModel { model, _ ->
        val actions = mutableListOf<String>()
        var health by mutableStateOf(HealthScreenState(loading = false, status = "Samsung Health connected", available = true, permitted = true,
            liveStatus = "Live phone and watch steps", liveConnected = true, recordCount = 508, lastSync = System.currentTimeMillis(), historyAllowed = true))
        rule.setContent { MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0F))) { SettingsRoute(model, health, false, { actions += it }, { actions += "watch" }) }
        } }
        rule.onNodeWithTag("settings-input-Daily step goal").performScrollTo().performTextReplacement("12000")
        rule.onNodeWithText("Save", substring = false).performScrollTo().performClick()
        rule.waitUntil(5000) { model.stepsGoal.value == 12000 }
        rule.onNodeWithText("Reduce motion").performScrollTo().performClick()
        rule.waitUntil(5000) { model.reducedMotion.value }
        rule.onNodeWithText("Globe rotation").assertIsNotEnabled()
        rule.onNodeWithText("Reduce transparency").performScrollTo().performClick()
        rule.waitUntil(5000) { model.readability.value?.reduceTransparency == true }
        rule.onNodeWithText("Increase contrast").performScrollTo().performClick()
        rule.waitUntil(5000) { model.readability.value?.increaseContrast == true }
        assertTrue(model.reducedMotion.value)
        save("settings-readability.png")
        rule.onNodeWithText("Connect music").performScrollTo().performClick()
        rule.onNodeWithText("Watch readings").performScrollTo().performClick()
        rule.onNodeWithText("Samsung Health").performScrollTo().performClick()
        rule.onNodeWithText("Live steps access").performScrollTo().performClick()
        rule.onNodeWithText("Import now").performScrollTo().performClick()
        rule.runOnIdle { health = health.copy(syncing = true) }
        rule.onNodeWithText("Import now").assertIsNotEnabled()
        rule.onNodeWithText("Connection & history").performScrollTo().performClick()
        rule.onNodeWithText("508 records saved", substring = true).assertExists()
        rule.onNodeWithText("Health permissions").performScrollTo().performClick()
        assertEquals(listOf("music", "watch", "live", "refresh", "permissions"), actions)
        rule.onNodeWithText("About Orbit").performScrollTo().performClick()
        rule.onNodeWithText("Past sessions keep their original values", substring = true).assertExists()
        rule.onNodeWithText("Phone–Watch sync uses Google Play services", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("end-to-end encrypted Google relay", substring = true).assertExists()
        save("settings-sync-privacy.png")
        rule.onNodeWithText("About Orbit").performScrollTo().performClick()
        rule.onNodeWithText("Phone–Watch sync uses Google Play services", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Profile").performScrollTo(); save("settings-native-overview.png")
    }
}
