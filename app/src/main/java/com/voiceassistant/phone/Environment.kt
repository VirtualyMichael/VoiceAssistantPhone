package com.voiceassistant.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.net.InetSocketAddress
import java.net.Socket

/** Looks at the phone around the app: is Tailscale installed and on, and does the PC answer. */
object Environment {
    private const val TAILSCALE = "com.tailscale.ipn"

    fun tailscaleInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TAILSCALE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Tailscale works as a VPN, so "a VPN is up" is the closest thing to "Tailscale is on" that Android lets an app see. */
    @Suppress("DEPRECATION")
    fun vpnActive(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /** True when something accepts a connection at the PC's address. Run off the main thread. */
    fun canReach(host: String, port: Int, timeoutMs: Int = 3000): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }

    fun openTailscale(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(TAILSCALE)
        if (launch != null) context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else installTailscale(context)
    }

    fun installTailscale(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (e: Exception) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
