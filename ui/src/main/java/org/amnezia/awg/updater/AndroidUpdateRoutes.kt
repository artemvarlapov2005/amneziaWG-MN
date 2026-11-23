package org.amnezia.awg.updater

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.amnezia.awg.R
import org.amnezia.awg.util.UpdateTransfer
import java.net.HttpURLConnection

object AndroidUpdateRoutes {
    /** Bind only this HTTPS connection. Never bind the process or toggle the VPN. */
    @Suppress("DEPRECATION")
    fun available(context: Context): List<RoutedUpdateTransfer.Route> {
        val result = mutableListOf<RoutedUpdateTransfer.Route>()
        try {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            fun add(network: Network) {
                val caps = manager.getNetworkCapabilities(network) ?: return
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                val label = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> R.string.mn_update_route_vpn
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.string.mn_update_route_wifi
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.string.mn_update_route_mobile
                    else -> R.string.mn_update_route_system
                }
                result.add(RoutedUpdateTransfer.Route("network:${network.networkHandle}", context.getString(label),
                    UpdateTransfer.ConnectionFactory { url -> network.openConnection(url) as HttpURLConnection }))
            }
            manager.activeNetwork?.let { add(it) }
            val all = manager.allNetworks.toList()
            for (type in listOf(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR)) {
                all.filter { manager.getNetworkCapabilities(it)?.hasTransport(type) == true }.forEach { add(it) }
            }
        } catch (_: SecurityException) { }
        if (result.isEmpty()) result.add(RoutedUpdateTransfer.Route("system", context.getString(R.string.mn_update_route_system),
            UpdateTransfer.ConnectionFactory { url -> url.openConnection() as HttpURLConnection }))
        return result.distinctBy { it.id }
    }
}
