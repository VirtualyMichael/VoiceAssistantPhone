package com.voiceassistant.phone

/** What the person can do about a problem. The screen turns each into a button. */
enum class Fix { None, InstallTailscale, OpenTailscale, PairAgain, WakePc }

/** What is known about the connection right now. `null` means "not checked". */
data class Facts(
    val paired: Boolean,
    val connected: Boolean,
    val tailscaleInstalled: Boolean? = null,
    val vpnActive: Boolean? = null,
    val pcKnowsPhone: Boolean = true,
    val pcReachable: Boolean? = null,
)

data class Diagnosis(val ok: Boolean, val message: String, val fix: Fix = Fix.None)

/** Picks the single most likely reason the phone cannot reach the PC, in the order a person would check them. */
object ConnectionDoctor {
    fun diagnose(f: Facts): Diagnosis = when {
        !f.paired -> Diagnosis(false, "This phone isn't paired with a PC yet.", Fix.PairAgain)
        f.connected -> Diagnosis(true, "Connected. Everything is working.")
        f.tailscaleInstalled == false ->
            Diagnosis(false, "Tailscale isn't installed on this phone. It's what connects your phone to your PC.", Fix.InstallTailscale)
        f.vpnActive == false -> Diagnosis(false, "Tailscale is turned off on this phone. Open it and switch it on.", Fix.OpenTailscale)
        !f.pcKnowsPhone -> Diagnosis(false, "Your PC no longer knows this phone. Pair it again.", Fix.PairAgain)
        f.pcReachable == false ->
            Diagnosis(false, "Your PC isn't answering. It may be asleep, or the assistant isn't running with its phone link turned on. Check the PC, then try again.", Fix.WakePc)
        else -> Diagnosis(false, "The connection isn't up yet, but nothing obvious is wrong. Try again in a moment.")
    }

    /** Plain words for what the PC said when it refused the phone. */
    fun refusal(code: String): String = when (code) {
        "bad_pair_code" -> "That code has expired or was already used. On your PC press “Show a new code”, then scan again."
        "not_paired" -> "Your PC doesn't know this phone any more. Pair it again."
        else -> "Your PC refused the connection."
    }
}
