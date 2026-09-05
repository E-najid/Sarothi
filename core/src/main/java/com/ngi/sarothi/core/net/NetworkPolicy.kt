package com.ngi.sarothi.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Coarse view of the active network, used to enforce the Wi-Fi-only default. */
enum class NetworkType { WIFI, CELLULAR, ETHERNET, OTHER, NONE }

/**
 * Decides whether a large model download may proceed right now.
 *
 * Sarothi defaults to Wi-Fi only: model files are 60–430 MB, and silently burning
 * a Bangladeshi mobile-data plan on a background download is exactly the kind of
 * surprise an on-device app must not cause. The user can flip this in Settings.
 */
class NetworkPolicy(private val context: Context) {

    fun activeType(): NetworkType {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkType.NONE
        val network = manager.activeNetwork ?: return NetworkType.NONE
        val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkType.NONE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }
    }

    fun isOnline(): Boolean = activeType() != NetworkType.NONE

    /** True when the current network may be used for a model download. */
    fun allowsDownload(allowMobileData: Boolean): Boolean = when (activeType()) {
        NetworkType.WIFI, NetworkType.ETHERNET -> true
        NetworkType.CELLULAR -> allowMobileData
        NetworkType.OTHER -> allowMobileData
        NetworkType.NONE -> false
    }

    /** Human-readable explanation, or null when the download may proceed. */
    fun blockReason(allowMobileData: Boolean): String? = when (activeType()) {
        NetworkType.NONE -> "No network connection. Sarothi downloads models inside the app, so it " +
            "needs a connection — connect to Wi-Fi and try again."
        NetworkType.CELLULAR -> if (allowMobileData) null else
            "You are on mobile data and 'Download over mobile data' is off. Model files are large " +
                "(60–430 MB). Connect to Wi-Fi, or enable mobile data downloads in " +
                "Settings → Models."
        NetworkType.OTHER -> if (allowMobileData) null else
            "The current network is not Wi-Fi and mobile-data downloads are disabled."
        NetworkType.WIFI, NetworkType.ETHERNET -> null
    }
}
