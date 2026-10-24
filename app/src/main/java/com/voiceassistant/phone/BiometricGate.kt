package com.voiceassistant.phone

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Asks for the phone owner's fingerprint (or screen-lock PIN) before a safety setting is sent to the PC. */
object BiometricGate {
    /** [onResult] gets (true, null) when confirmed, (false, null) when cancelled, and (false, reason) when the phone cannot check. */
    fun confirm(activity: FragmentActivity, title: String, onResult: (Boolean, String?) -> Unit) {
        val modern = Build.VERSION.SDK_INT >= 30
        val allowed = if (modern) BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK
        if (BiometricManager.from(activity).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(false, "Set up a fingerprint or screen lock on this phone to change safety settings.")
            return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true, null)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false, null)
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("This changes a safety setting on your PC.")
            .setAllowedAuthenticators(allowed)
            .apply { if (!modern) setNegativeButtonText("Cancel") } // a negative button is not allowed together with the screen-lock option
            .build()
        prompt.authenticate(info)
    }
}
