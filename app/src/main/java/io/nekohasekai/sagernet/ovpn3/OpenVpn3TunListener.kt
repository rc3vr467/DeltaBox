package io.nekohasekai.sagernet.ovpn3

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

// Implements the native TUN callbacks on top of Android VpnService.Builder.
//
// Ownership: the ParcelFileDescriptor returned by Builder.establish() is kept here and
// closed in tunTeardown(). The native core only receives and uses the raw fd and never
// closes it.
//
// Limitations (reported to the core as failures, not silently ignored):
//   * Excluded routes: VpnService.Builder has no exclude API, so exclude routes fail.
//   * Remote address: VpnService.Builder has no remote-address setting; it is accepted
//     and used only for socket protection.
//   * DNS: servers and search domains are applied; per-domain routing, custom ports and
//     DoT/DoH are not supported by VpnService and are logged by the native side.
class OpenVpn3TunListener(
    private val service: VpnService,
    private val onEventCallback: (name: String, info: String, error: Boolean, fatal: Boolean) -> Unit,
    private val onLogCallback: (text: String) -> Unit,
) : OpenVpn3Listener {

    private var builder: VpnService.Builder? = null
    private var pfd: ParcelFileDescriptor? = null

    override fun onEvent(name: String, info: String, error: Boolean, fatal: Boolean) {
        onEventCallback(name, info, error, fatal)
    }

    override fun onLog(text: String) {
        onLogCallback(text)
    }

    override fun tunNew(): Boolean {
        builder = VpnService.Builder()
        return true
    }

    override fun tunSetRemoteAddress(address: String, ipv6: Boolean): Boolean = true

    override fun tunAddAddress(address: String, prefixLength: Int, gateway: String, ipv6: Boolean, net30: Boolean): Boolean {
        val b = builder ?: VpnService.Builder().also { builder = it }
        return try {
            b.addAddress(address, prefixLength)
            true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "addAddress rejected: ${e.message}")
            false
        }
    }

    override fun tunAddRoute(address: String, prefixLength: Int, metric: Int, ipv6: Boolean, exclude: Boolean): Boolean {
        if (exclude) return false
        val b = builder ?: VpnService.Builder().also { builder = it }
        return try {
            b.addRoute(address, prefixLength)
            true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "addRoute rejected: ${e.message}")
            false
        }
    }

    override fun tunSetMtu(mtu: Int): Boolean {
        val b = builder ?: VpnService.Builder().also { builder = it }
        return try {
            b.setMtu(mtu)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    override fun tunSetSessionName(name: String): Boolean {
        val b = builder ?: VpnService.Builder().also { builder = it }
        b.setSession(name)
        return true
    }

    override fun tunAddDnsServer(address: String): Boolean {
        val b = builder ?: VpnService.Builder().also { builder = it }
        return try {
            b.addDnsServer(address)
            true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "addDnsServer rejected: ${e.message}")
            false
        }
    }

    override fun tunAddSearchDomain(domain: String): Boolean {
        val b = builder ?: VpnService.Builder().also { builder = it }
        return try {
            b.addSearchDomain(domain)
            true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "addSearchDomain rejected: ${e.message}")
            false
        }
    }

    override fun tunEstablish(): Int {
        val b = builder ?: return -1
        val descriptor = try {
            b.establish()
        } catch (e: SecurityException) {
            Log.w(TAG, "establish denied: VPN permission not granted")
            null
        } ?: return -1
        pfd?.close()
        pfd = descriptor
        return descriptor.fd
    }

    override fun tunTeardown(disconnect: Boolean) {
        try {
            pfd?.close()
        } finally {
            pfd = null
            builder = null
        }
    }

    override fun protectSocket(fd: Int, ipv6: Boolean): Boolean = service.protect(fd)

    private companion object {
        const val TAG = "OpenVpn3TunListener"
    }
}
