package io.nekohasekai.sagernet.utils

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.runBlocking
import java.net.UnknownHostException

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage()
        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(val key: Any) : NetworkMessage()

        class Put(val network: Network) : NetworkMessage()
        class Update(val network: Network) : NetworkMessage()
        class Lost(val network: Network) : NetworkMessage()
    }

    private val networkActor = GlobalScope.actor<NetworkMessage>(Dispatchers.Unconfined) {
        val listeners = mutableMapOf<Any, (Network?) -> Unit>()
        var network: Network? = null
        val pendingRequests = arrayListOf<NetworkMessage.Get>()
        for (message in channel) when (message) {
            is NetworkMessage.Start -> {
                if (listeners.isEmpty()) register()
                listeners[message.key] = message.listener
                if (network == null && fallback) {
                    // register() above failed (SecurityException, the per-app
                    // 100-outstanding-request cap being hit, or some other
                    // platform quirk on this device/API level) and fell back
                    // to `fallback = true`. In that mode Callback.onAvailable
                    // is never going to fire - nothing actually registered -
                    // so `network` would otherwise stay null for the entire
                    // session: every listener (including the one in
                    // BaseService.kt that calls Libcore.updateDefaultInterface)
                    // would simply never run. That leaves sing-box's
                    // interfaceMonitor.DefaultInterface() permanently nil,
                    // which makes dispatchInterfaceUpdateLocked() call
                    // pauseManager.NetworkPause() on every interface-update
                    // dispatch (not just a one-off "missing default interface"
                    // log line) - which in turn, through the wireguard
                    // endpoint's onPauseUpdated/updateNetworkPause callback,
                    // calls wgDevice.Down(), undoing Up() and leaving the
                    // handshake/traffic permanently stuck even though packets
                    // appear to go out fine. Set `network` here directly from
                    // the current activeNetwork - the same one get()'s own
                    // fallback path already uses - instead of going through
                    // Put via the actor's own channel (sending to this actor's
                    // channel from inside its own message loop would just
                    // fail silently: the default RENDEZVOUS/no-buffer channel
                    // has no receiver ready to take it yet, since this coroutine
                    // is still busy handling the current Start message).
                    @TargetApi(23) {
                        network = SagerNet.connectivity.activeNetwork
                    }
                }
                if (network != null) message.listener(network)
            }
            is NetworkMessage.Get -> {
                check(listeners.isNotEmpty()) { "Getting network without any listeners is not supported" }
                if (network == null) pendingRequests += message else message.response.complete(
                    network
                )
            }
            is NetworkMessage.Stop -> if (listeners.isNotEmpty() && // was not empty
                listeners.remove(message.key) != null && listeners.isEmpty()
            ) {
                network = null
                unregister()
            }

            is NetworkMessage.Put -> {
                network = message.network
                pendingRequests.forEach { it.response.complete(message.network) }
                pendingRequests.clear()
                listeners.values.forEach { it(network) }
            }
            is NetworkMessage.Update -> if (network == message.network) listeners.values.forEach {
                it(
                    network
                )
            }
            is NetworkMessage.Lost -> if (network == message.network) {
                network = null
                listeners.values.forEach { it(null) }
            }
        }
    }

    suspend fun start(key: Any, listener: (Network?) -> Unit) =
        networkActor.send(NetworkMessage.Start(key, listener))

    suspend fun get() = if (fallback) @TargetApi(23) {
        SagerNet.connectivity.activeNetwork
            ?: throw UnknownHostException() // failed to listen, return current if available
    } else NetworkMessage.Get().run {
        networkActor.send(this)
        response.await()
    }

    suspend fun stop(key: Any) = networkActor.send(NetworkMessage.Stop(key))

    // NB: this runs in ConnectivityThread, and this behavior cannot be changed until API 26
    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) =
            runBlocking { networkActor.send(NetworkMessage.Put(network)) }

        override fun onCapabilitiesChanged(
            network: Network, networkCapabilities: NetworkCapabilities
        ) { // it's a good idea to refresh capabilities
            runBlocking { networkActor.send(NetworkMessage.Update(network)) }
        }

        override fun onLost(network: Network) =
            runBlocking { networkActor.send(NetworkMessage.Lost(network)) }
    }

    private var fallback = false
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        if (Build.VERSION.SDK_INT == 23) {  // workarounds for OEM bugs
            removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
    }.build()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        try {
            fallback = false
            when (Build.VERSION.SDK_INT) {
                in 31..Int.MAX_VALUE -> @TargetApi(31) {
                    SagerNet.connectivity.registerBestMatchingNetworkCallback(
                        request, Callback, mainHandler
                    )
                }
                in 28 until 31 -> @TargetApi(28) {  // we want REQUEST here instead of LISTEN
                    SagerNet.connectivity.requestNetwork(request, Callback, mainHandler)
                }
                in 26 until 28 -> @TargetApi(26) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback, mainHandler)
                }
                in 24 until 26 -> @TargetApi(24) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback)
                }
                else -> {
                    SagerNet.connectivity.requestNetwork(request, Callback)
                    // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
            }
        } catch (e: Exception) {
            Logs.w(e)
            fallback = true
        }
    }

    private fun unregister() = SagerNet.connectivity.unregisterNetworkCallback(Callback)
}
