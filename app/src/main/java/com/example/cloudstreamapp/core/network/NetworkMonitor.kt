package com.example.cloudstreamapp.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.cloudstreamapp.domain.model.NetworkState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val networkState: Flow<NetworkState> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(NetworkState.Connected)
            }

            override fun onLost(network: Network) {
                trySend(NetworkState.Offline)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val state = when {
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkState.Offline
                    caps.linkDownstreamBandwidthKbps < 512 -> NetworkState.Slow
                    else -> NetworkState.Connected
                }
                trySend(state)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
