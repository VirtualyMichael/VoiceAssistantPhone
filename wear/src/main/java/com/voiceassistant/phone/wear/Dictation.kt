package com.voiceassistant.phone.wear

import android.app.RemoteInput
import android.content.Intent
import android.speech.RecognizerIntent

/**
 * The watch's own text input screen (speak, type, or draw letters, whatever the watch offers), for replies that are not one of the quick
 * chips. Wear OS opens it for this intent; these are the same action and extras androidx's RemoteInputIntentHelper uses, written out here
 * because that small library is not one of the app's dependencies. A watch without that screen gets Android's plain speech recognition.
 */
object Dictation {
    private const val KEY = "reply"
    private const val ACTION_REMOTE_INPUT = "android.support.wearable.input.action.REMOTE_INPUT"
    private const val EXTRA_REMOTE_INPUTS = "android.support.wearable.input.extra.REMOTE_INPUTS"
    private const val EXTRA_TITLE = "android.support.wearable.input.extra.TITLE"

    fun inputIntent(): Intent = Intent(ACTION_REMOTE_INPUT)
        .putExtra(EXTRA_REMOTE_INPUTS, arrayOf(RemoteInput.Builder(KEY).setLabel("Your reply").build()))
        .putExtra(EXTRA_TITLE, "Your reply")

    fun speechIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Your reply")

    /** The text from either screen, or "" when nothing was said or typed. */
    fun text(result: Intent): String =
        RemoteInput.getResultsFromIntent(result)?.getCharSequence(KEY)?.toString()
            ?: result.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            ?: ""
}
