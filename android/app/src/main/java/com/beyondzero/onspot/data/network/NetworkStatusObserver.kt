package com.beyondzero.onspot.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import java.lang.reflect.Method

class NetworkStatusObserver(private val context: Context) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun observeStatus(): Flow<NetworkState> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getNetworkState())
            }

            override fun onLost(network: Network) {
                trySend(getNetworkState())
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(getNetworkState())
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Initial state
        trySend(getNetworkState())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.onStart { emit(getNetworkState()) }.distinctUntilChanged()

    private fun getNetworkState(): NetworkState {
        val isWifiEnabled = wifiManager.isWifiEnabled
        val isHotspotEnabled = isHotspotEnabled()
        
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val hasWifiConnection = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        return NetworkState(
            isWifiEnabled = isWifiEnabled,
            isHotspotEnabled = isHotspotEnabled,
            isConnectedToWifi = hasWifiConnection,
            isReady = isWifiEnabled || isHotspotEnabled || hasWifiConnection
        )
    }

    private fun isHotspotEnabled(): Boolean {
        return try {
            val method: Method = wifiManager.javaClass.getDeclaredMethod("getWifiApState")
            method.isAccessible = true
            val actualState = method.invoke(wifiManager) as Int
            // 13 is WIFI_AP_STATE_ENABLED
            actualState == 13
        } catch (e: Exception) {
            // Fallback: Check if wifi is disabled but there's a local address or other heuristics
            // On some newer Androids, this reflection might fail, but it's a common way.
            false
        }
    }
}

data class NetworkState(
    val isWifiEnabled: Boolean,
    val isHotspotEnabled: Boolean,
    val isConnectedToWifi: Boolean,
    val isReady: Boolean
)
