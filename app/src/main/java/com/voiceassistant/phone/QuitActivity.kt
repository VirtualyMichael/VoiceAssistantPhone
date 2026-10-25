package com.voiceassistant.phone

import android.app.Activity
import android.os.Bundle

/** Opened from the "Quit" shortcut on the app icon: no screen, it only ends the app (see [Controller.quitCompletely]). */
class QuitActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Controller.quitCompletely(this)
    }
}
