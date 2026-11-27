package io.nekohasekai.sagernet.ui.web

/**
 * Whitelist of settings the HTML UI may read/write through [UiBridge].
 * Keys are identical to the native preference keys (res/xml + DataStore), verified when generated.
 * Anything not listed here is rejected by the bridge.
 */
object UiSettingsSchema {
    val booleans: Set<String> = setOf(
        "ad_block_enabled",
        "profileTrafficStatistics",
        "showDirectSpeed",
        "showGroupInNotification",
        "alwaysShowAddress",
        "meteredNetwork",
        "acquireWakeLock",
        "bypassLan",
        "bypassLanInCore",
        "resolveDestination",
        "enableDnsRouting",
        "enableFakeDns",
        "appendHttpProxy",
        "allowAccess",
        "enableClashAPI",
        "networkChangeResetConnections",
        "wakeResetConnections",
        "globalAllowInsecure",
        "allowInsecureOnRequest",
        "showBottomBar"
    )

    val strings: Set<String> = setOf(
        "connectionTestConcurrent",
        "appLanguage",
        "nightTheme",
        "serviceMode",
        "tunImplementation",
        "mtu",
        "speedInterval",
        "logLevel",
        "globalCustomConfig",
        "trafficSniffing",
        "ipv6Mode",
        "remoteDns",
        "domain_strategy_for_remote",
        "directDns",
        "domain_strategy_for_direct",
        "domain_strategy_for_server",
        "mixedPort",
        "connectionTestURL",
        "appTLSVersion"
    )

    fun isAllowed(key: String) = key in booleans || key in strings
}
