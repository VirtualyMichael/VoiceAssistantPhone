package com.voiceassistant.phone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelTest {
    private val json = """
    {"allow_safety": false, "restart_pending": false, "sections": [
      {"id": "speech", "title": "Speech", "blurb": "How it sounds.", "items": [
        {"key": "tts_speed", "label": "Speaking speed", "help": "1 is normal.", "kind": "number", "value": 1.25, "default": 1.0,
         "min": 0.6, "max": 1.6, "step": 0.05, "integer": false, "unit": "", "apply": "restart", "sensitive": false, "locked": false, "essential": true},
        {"key": "tts_threads", "label": "Threads", "help": "Speech threads.", "kind": "number", "value": 6, "default": 6,
         "min": 1, "max": 16, "step": 1, "integer": true, "unit": "", "apply": "restart", "sensitive": false, "locked": false, "essential": false},
        {"key": "persona_jarvis", "label": "Butler personality", "help": "A dry-witted butler.", "kind": "toggle", "value": true, "default": false,
         "apply": "restart", "sensitive": false, "locked": false, "essential": true}
      ]},
      {"id": "safety", "title": "Safety and permissions", "blurb": "Locked unless allowed.", "items": [
        {"key": "allow_shell", "label": "Run PowerShell commands", "help": "It still asks before each one.", "kind": "toggle", "value": false, "default": false,
         "apply": "restart", "sensitive": true, "locked": true, "essential": false},
        {"key": "approved_folders", "label": "Folders it may use", "help": "Edit on the PC.", "kind": "readonly", "summary": "2 items",
         "items": ["C:/a", "C:/b"], "apply": "restart", "sensitive": false, "locked": false, "essential": false},
        {"key": "remote_output", "label": "Where replies play", "help": "Phone, PC or both.", "kind": "choice", "value": "both", "default": "phone",
         "choices": [{"value": "phone", "label": "On my phone"}, {"value": "both", "label": "Phone and PC"}], "allow_other": false,
         "apply": "live", "sensitive": false, "locked": false, "essential": true}
      ]}
    ]}
    """

    private val snapshot = SettingsSnapshot.parse(JSONObject(json))

    @Test
    fun parsesSectionsAndItemsWithTheirSectionTitle() {
        assertEquals(listOf("speech", "safety"), snapshot.sections.map { it.id })
        assertEquals(6, snapshot.all.size)
        assertEquals("Speech", snapshot.find("tts_speed")?.sectionTitle)
        assertFalse(snapshot.allowSafety)
    }

    @Test
    fun numbersTogglesAndChoicesGetTheRightTypesAndShowReadableValues() {
        val speed = snapshot.find("tts_speed")!!
        assertEquals(1.25, speed.value)
        assertEquals("1.25", speed.valueText())
        assertTrue(speed.restartNeeded)
        assertEquals("6", snapshot.find("tts_threads")!!.valueText())
        assertEquals("On", snapshot.find("persona_jarvis")!!.valueText())
        assertEquals("Phone and PC", snapshot.find("remote_output")!!.valueText())
        assertFalse(snapshot.find("remote_output")!!.restartNeeded)
        assertEquals("2 items", snapshot.find("approved_folders")!!.valueText())
        assertEquals(listOf("C:/a", "C:/b"), snapshot.find("approved_folders")!!.items)
    }

    @Test
    fun unitsAreShown() {
        val item = SettingItem("k", "l", "h", "number", value = 2.0, step = 1.0, unit = "s", integer = true)
        assertEquals("2 s", item.valueText())
        assertEquals("$5", item.copy(unit = "$", value = 5.0).valueText())
    }

    @Test
    fun lockedAndSensitiveFlagsComeThrough() {
        val shell = snapshot.find("allow_shell")!!
        assertTrue(shell.locked)
        assertTrue(shell.sensitive)
        assertFalse(snapshot.find("tts_speed")!!.locked)
    }

    @Test
    fun essentialsAreTheFlaggedOnes() {
        assertEquals(setOf("tts_speed", "persona_jarvis", "remote_output"), snapshot.essentials().map { it.key }.toSet())
    }

    @Test
    fun searchMatchesNameHelpAndSectionAndNeedsEveryWord() {
        assertEquals(listOf("tts_speed"), snapshot.search("speed").map { it.key })
        assertTrue(snapshot.search("butler").any { it.key == "persona_jarvis" })
        assertTrue(snapshot.search("safety").any { it.key == "allow_shell" }) // matches the section name
        assertEquals(listOf("allow_shell"), snapshot.search("asks before").map { it.key })
        assertTrue(snapshot.search("speed nonsense").isEmpty())
        assertTrue(snapshot.search("   ").isEmpty())
    }

    @Test
    fun changingAValueTouchesOnlyThatSetting() {
        val changed = snapshot.withValue("tts_speed", 0.8)
        assertEquals(0.8, changed.find("tts_speed")!!.value)
        assertEquals(1.25, snapshot.find("tts_speed")!!.value) // the original is untouched
        assertEquals(true, changed.find("persona_jarvis")!!.value)
    }

    @Test
    fun anEmptySnapshotIsHandled() {
        val empty = SettingsSnapshot.parse(JSONObject("{}"))
        assertTrue(empty.sections.isEmpty())
        assertNull(empty.find("anything"))
        assertNotNull(empty)
    }

    @Test
    fun restartPendingOnlyEverTurnsOn() {
        assertTrue(snapshot.withRestartPending(true).restartPending)
        assertTrue(snapshot.withRestartPending(true).withRestartPending(false).restartPending)
    }
}
