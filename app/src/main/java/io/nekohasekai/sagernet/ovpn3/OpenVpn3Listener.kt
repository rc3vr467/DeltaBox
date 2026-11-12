package io.nekohasekai.sagernet.ovpn3

// Callbacks invoked from the native connect() thread. Method names and signatures are
// referenced by GetMethodID in ovpn3_jni.cpp and must stay in sync with it.
interface OpenVpn3Listener {
    fun onEvent(name: String, info: String, error: Boolean, fatal: Boolean)
    fun onLog(text: String)

    // Starts a fresh VpnService.Builder for a new connect attempt. Must return true.
    fun tunNew(): Boolean
    fun tunSetRemoteAddress(address: String, ipv6: Boolean): Boolean
    fun tunAddAddress(address: String, prefixLength: Int, gateway: String, ipv6: Boolean, net30: Boolean): Boolean
    fun tunAddRoute(address: String, prefixLength: Int, metric: Int, ipv6: Boolean, exclude: Boolean): Boolean
    fun tunSetMtu(mtu: Int): Boolean
    fun tunSetSessionName(name: String): Boolean
    fun tunAddDnsServer(address: String): Boolean
    fun tunAddSearchDomain(domain: String): Boolean

    // Returns the TUN file descriptor, or -1 on failure.
    fun tunEstablish(): Int
    fun tunTeardown(disconnect: Boolean)

    fun protectSocket(fd: Int, ipv6: Boolean): Boolean
}
