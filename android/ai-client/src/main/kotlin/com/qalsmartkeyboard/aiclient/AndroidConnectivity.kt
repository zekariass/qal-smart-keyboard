package com.qalsmartkeyboard.aiclient

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [Connectivity] over the system's default-network callback (API 24+). The state is a cached
 * flag, so reading it is instant. If the callback cannot be registered the state stays "online" and
 * a call that cannot connect reports the offline error itself.
 */
class AndroidConnectivity(context: Context) : Connectivity {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var online = true

    init {
        val manager = cm
        if (manager != null) {
            online = manager.activeNetwork?.let { hasInternet(manager.getNetworkCapabilities(it)) } ?: false
            try {
                manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = set(true)

                    override fun onLost(network: Network) = set(false)

                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = set(hasInternet(caps))
                })
            } catch (_: RuntimeException) {
                online = true
            }
        }
    }

    private fun hasInternet(caps: NetworkCapabilities?) = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun set(value: Boolean) {
        if (online != value) {
            online = value
            listeners.forEach { it() }
        }
    }

    override fun isOnline() = online

    override fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}
