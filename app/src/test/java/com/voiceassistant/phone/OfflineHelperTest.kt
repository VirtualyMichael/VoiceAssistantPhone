package com.voiceassistant.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineHelperTest {
    private fun action(raw: String) = (OfflineHelper.parse(raw) as OfflineResult.Do).action

    @Test fun alarmBecomesAnAction() {
        val a = action("""{"action":"set_alarm","time":"06:30"}""")
        assertEquals("set_alarm", a.action)
        assertEquals(6, a.args.getInt("hour"))
        assertEquals(30, a.args.getInt("minute"))
    }

    @Test fun timerAcceptsAStringOrANumberAndCodeFences() {
        assertEquals(600, action("```json\n{\"action\":\"set_timer\",\"seconds\":600}\n```").args.getInt("seconds"))
        assertEquals(5400, action("""{"action":"set_timer","seconds":"5400"}""").args.getInt("seconds"))
    }

    @Test fun textAndCallKeepTheNameForThePhoneToLookUp() {
        val t = action("""{"action":"send_text","to":"Mom","message":"Running late"}""")
        assertEquals("Mom", t.args.getString("to"))
        assertTrue(t.needsRecipient)
        assertEquals("Dad", action("""{"action":"call","to":"Dad"}""").args.getString("to"))
    }

    @Test fun answersAndRefusalsAreSpoken() {
        assertEquals("12", (OfflineHelper.parse("""{"action":"answer","text":"12"}""") as OfflineResult.Say).text)
        val no = (OfflineHelper.parse("""{"action":"unavailable","reason":"needs the internet"}""") as OfflineResult.Say).text
        assertTrue(no.contains("out of reach") && no.contains("needs the internet"))
    }

    @Test fun nonsenseFailsInsteadOfDoingSomething() {
        for (raw in listOf("", "Sure!", """{"action":"set_alarm","time":"25:99"}""", """{"action":"set_timer","seconds":0}""",
            """{"action":"send_text","to":"Mom"}""", """{"action":"launch_missiles"}""", "{broken")) {
            assertEquals(raw, OfflineResult.Failed, OfflineHelper.parse(raw))
        }
    }
}
