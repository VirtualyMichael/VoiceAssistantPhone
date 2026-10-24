package com.voiceassistant.phone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareAndActionsTest {
    @Test
    fun aSharedLinkIsToldToTheAssistantAsALink() {
        val share = IncomingShare(emptyList(), "Look at this https://example.com/deal")
        assertTrue(share.isLink)
        assertEquals("I shared this link: Look at this https://example.com/deal ", share.describe(emptyList()))
        assertTrue("Summarize this page" in share.suggestions())
    }

    @Test
    fun aMessageThatContainsALinkIsStillAMessage() {
        val sms = IncomingShare(emptyList(), "Congratulations! You won a 1000 dollar gift card. Claim now at http://walmart-giftclaim.xyz before it expires.")
        assertFalse(sms.isLink)
        assertEquals("Is this a scam?", sms.suggestions().first())
        assertTrue(sms.describe(emptyList()).startsWith("I shared this text: "))
    }

    @Test
    fun savedFilesAreNamedSoTheAssistantCanOpenThem() {
        val share = IncomingShare(emptyList(), "")
        assertEquals("I shared from my phone: a.jpg, b.pdf (now attached). ", share.describe(listOf("a.jpg", "b.pdf")))
        assertFalse(share.isLink)
    }

    @Test
    fun onlyKindsThePcCanUseAreKept() {
        assertTrue(IncomingShare.usable("image/png"))
        assertTrue(IncomingShare.usable("application/pdf"))
        assertTrue(IncomingShare.usable("text/plain"))
        assertFalse(IncomingShare.usable("application/zip"))
        assertFalse(IncomingShare.usable("application/vnd.android.package-archive"))
    }

    @Test
    fun actionsAreDescribedPlainlyBeforeTheUserAgrees() {
        val alarm = PhoneAction("1", "set_alarm", JSONObject().put("hour", 7).put("minute", 5).put("label", "Gym").put("days", JSONArray(listOf("mon", "fri"))))
        assertEquals("Set an alarm for 07:05 (“Gym”), every Mon, Fri?", alarm.describe())
        assertEquals("Start a 1 hour 30 minutes timer?", PhoneAction("2", "set_timer", JSONObject().put("seconds", 5400)).describe())
        val event = PhoneAction("3", "add_event", JSONObject().put("title", "Dentist").put("start", "2026-11-02T15:00").put("location", "Main St"))
        assertTrue(event.describe().startsWith("Add “Dentist” to your calendar on ") && event.describe().endsWith(" at Main St?"))
        assertFalse(PhoneAction("4", "wipe_phone", JSONObject()).known)
    }
}
