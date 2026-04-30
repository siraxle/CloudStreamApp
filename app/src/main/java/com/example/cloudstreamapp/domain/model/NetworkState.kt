package com.example.cloudstreamapp.domain.model

sealed class NetworkState {
    object Connected : NetworkState()
    object Slow : NetworkState()       // < 512 kbps
    object Unstable : NetworkState()   // > 20% packet loss
    object Offline : NetworkState()
}
