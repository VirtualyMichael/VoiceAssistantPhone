package com.voiceassistant.phone

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerCardTest {
    @Test
    fun sourcesAndSettingsAreReadAndOnlyWebLinksAreKept() {
        val card = AnswerCard.parse(JSONObject("""
            {"type": "card", "id": "abc", "sources": [
               {"title": "Sydney Opera House", "url": "https://en.wikipedia.org/wiki/Sydney_Opera_House", "site": "en.wikipedia.org"},
               {"title": "Sneaky", "url": "javascript:alert(1)", "site": "x"}],
             "settings": [{"section": "speech", "label": "Voice › Speaking speed"}], "images": []}
        """))
        assertEquals("abc", card.id)
        assertEquals(listOf("en.wikipedia.org"), card.sources.map { it.site })
        assertEquals("speech", card.settings.single().section)
        assertTrue(!card.isEmpty)
    }

    @Test
    fun anEmptyCardIsEmpty() {
        assertTrue(AnswerCard.parse(JSONObject("""{"id": "x"}""")).isEmpty)
    }
}
