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
    fun numbersAndNamesAreToldApart() {
        assertTrue(Contacts.isNumber("+1 (555) 010-2030"))
        assertTrue(Contacts.isNumber("555 0102"))
        assertFalse(Contacts.isNumber("Mom"))
        assertFalse(Contacts.isNumber("Room 101"))
    }

    @Test
    fun exactNamesAndMobileNumbersComeFirstAndEachNumberOnce() {
        val mobile = android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        val home = android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_HOME
        val ranked = Contacts.rank(listOf(
            ContactMatch("Mom's work", "555-1", "Work") to home,
            ContactMatch("Mom", "555-2", "Home") to home,
            ContactMatch("Mom", "555-3", "Mobile") to mobile,
            ContactMatch("Mom", "5553", "Mobile") to mobile,
        ), "mom")
        assertEquals(listOf("555-3", "555-2", "555-1"), ranked.map { it.number })
    }

    @Test
    fun textsAndCallsNeedSomeoneAndNotificationsAreKnown() {
        val text = PhoneAction("5", "send_text", JSONObject().put("to", "Mom").put("message", "Running late"))
        assertTrue(text.needsRecipient && text.known)
        assertEquals("Text Mom: “Running late”", text.describe())
        assertTrue(PhoneAction("6", "call", JSONObject().put("to", "Dad")).needsRecipient)
        assertTrue(PhoneAction("7", "read_notifications", JSONObject()).known)
        assertEquals("5 min ago", NotificationReader.ago(5 * 60_000L))
        assertEquals("2 h ago", NotificationReader.ago(2 * 3_600_000L))
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
