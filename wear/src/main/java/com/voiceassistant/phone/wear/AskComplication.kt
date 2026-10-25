package com.voiceassistant.phone.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** A watch-face slot that says "Ask" and opens the app already listening. Short text only: it never shows anything from your conversations. */
class AskComplication : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = if (type == ComplicationType.SHORT_TEXT) data() else null

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        if (request.complicationType == ComplicationType.SHORT_TEXT) data() else null

    private fun data(): ComplicationData {
        val open = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TALK, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return ShortTextComplicationData.Builder(PlainComplicationText.Builder("Ask").build(), PlainComplicationText.Builder("Ask the assistant").build())
            .setTapAction(tap).build()
    }
}
