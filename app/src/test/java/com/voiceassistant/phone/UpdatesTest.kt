package com.voiceassistant.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun versionsCompareNumberByNumber() {
        assertTrue(Updates.newer("0.16.1", "0.16.0"))
        assertTrue(Updates.newer("v0.17.0", "0.16.9"))
        assertTrue(Updates.newer("1.0.0", "0.99.99"))
        assertTrue(Updates.newer("0.16.10", "0.16.9")) // 10 is more than 9, not "1" before "9"
        assertFalse(Updates.newer("0.16.0", "0.16.0"))
        assertFalse(Updates.newer("0.15.1", "0.16.0"))
        assertFalse(Updates.newer("1.0", "1.0.0"))
        assertFalse(Updates.newer("garbage", "0.16.0"))
        assertEquals(listOf(0, 16, 1), Updates.parts("v0.16.1-draft"))
    }

    @Test
    fun onlyRealGitHubReleasesAreOffered() {
        val ok = """{"tag_name":"v0.17.0","html_url":"https://github.com/VirtualyMichael/VoiceAssistantPhone/releases/tag/v0.17.0","body":"New things","draft":false,"prerelease":false}"""
        val release = Updates.parse(ok)
        assertNotNull(release)
        assertEquals("0.17.0", release!!.version)
        assertNull(Updates.parse(ok.replace("\"draft\":false", "\"draft\":true")))
        assertNull(Updates.parse(ok.replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(Updates.parse(ok.replace("https://github.com/", "https://evil.example/")))
        assertNull(Updates.parse("not json"))
    }
}
