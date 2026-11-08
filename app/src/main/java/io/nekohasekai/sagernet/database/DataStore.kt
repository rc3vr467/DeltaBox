package io.nekohasekai.sagernet.database

import android.os.Binder
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.CONNECTION_TEST_URL
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.ktx.boolean
import io.nekohasekai.sagernet.ktx.int
import io.nekohasekai.sagernet.ktx.long
import io.nekohasekai.sagernet.ktx.parsePort
import io.nekohasekai.sagernet.ktx.string
import io.nekohasekai.sagernet.ktx.stringToInt
import io.nekohasekai.sagernet.ktx.stringToIntIfExists
import moe.matsuri.nb4a.TempDatabase

object DataStore : OnPreferenceDataStoreChangeListener {

    // share service state in main & bg process
    @Volatile
    var serviceState = BaseService.State.Idle

    val configurationStore = RoomPreferenceDataStore(PublicDatabase.kvPairDao)
    val profileCacheStore = RoomPreferenceDataStore(TempDatabase.profileCacheDao)

    // last used, but may not be running
    var currentProfile by configurationStore.long(Key.PROFILE_CURRENT)

    var selectedProxy by configurationStore.long(Key.PROFILE_ID)
    var selectedGroup by configurationStore.long(Key.PROFILE_GROUP) { currentGroupId() } // "ungrouped" group id = 1

    // only in bg process
    var vpnService: VpnService? = null
    var baseService: BaseService.Interface? = null

    // main

    var runningTest = false

    fun currentGroupId(): Long {
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) return currentSelected
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isNotEmpty()) {
            val groupId = groups[0].id
            selectedGroup = groupId
            return groupId
        }
        val groupId = SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
        selectedGroup = groupId
        return groupId
    }

    fun currentGroup(): ProxyGroup {
        var group: ProxyGroup? = null
        val currentSelected = configurationStore.getLong(Key.PROFILE_GROUP, -1)
        if (currentSelected > 0L) {
            group = SagerDatabase.groupDao.getById(currentSelected)
        }
        if (group != null) return group
        val groups = SagerDatabase.groupDao.allGroups()
        if (groups.isEmpty()) {
            group = ProxyGroup(ungrouped = true).apply {
                id = SagerDatabase.groupDao.createGroup(this)
            }
        } else {
            group = groups[0]
        }
        selectedGroup = group.id
        return group
    }

    fun selectedGroupForImport(): Long {
        val current = currentGroup()
        if (current.type == GroupType.BASIC) return current.id
        val groups = SagerDatabase.groupDao.allGroups()
        return groups.find { it.type == GroupType.BASIC }!!.id
    }

    var appTLSVersion by configurationStore.string(Key.APP_TLS_VERSION) { "1.2" }
    var enableClashAPI by configurationStore.boolean(Key.ENABLE_CLASH_API)
    var showBottomBar by configurationStore.boolean(Key.SHOW_BOTTOM_BAR)

    var allowInsecureOnRequest by configurationStore.boolean(Key.ALLOW_INSECURE_ON_REQUEST) { true }
    var networkChangeResetConnections by configurationStore.boolean(Key.NETWORK_CHANGE_RESET_CONNECTIONS) { true }
    var wakeResetConnections by configurationStore.boolean(Key.WAKE_RESET_CONNECTIONS) { true }

    //

    var isExpert by configurationStore.boolean(Key.APP_EXPERT)
    var appTheme by configurationStore.int(Key.APP_THEME)
    var nightTheme by configurationStore.stringToInt(Key.NIGHT_THEME) { 1 }
    var appLanguage by configurationStore.string(Key.APP_LANGUAGE) { "en" }
    var serviceMode by configurationStore.string(Key.SERVICE_MODE) { Key.MODE_VPN }

    var trafficSniffing by configurationStore.stringToInt(Key.TRAFFIC_SNIFFING) { 1 }
    var resolveDestination by configurationStore.boolean(Key.RESOLVE_DESTINATION)

    var mtu by configurationStore.stringToInt(Key.MTU) { 1500 }

    var bypassLan by configurationStore.boolean(Key.BYPASS_LAN)
    var bypassLanInCore by configurationStore.boolean(Key.BYPASS_LAN_IN_CORE)

    var allowAccess by configurationStore.boolean(Key.ALLOW_ACCESS)
    var speedInterval by configurationStore.stringToInt(Key.SPEED_INTERVAL) { 1000 }
    var showGroupInNotification by configurationStore.boolean("showGroupInNotification")

    var globalCustomConfig by configurationStore.string(Key.GLOBAL_CUSTOM_CONFIG) { "" }

    var remoteDns by configurationStore.string(Key.REMOTE_DNS) { "8.8.8.8" }
    var directDns by configurationStore.string(Key.DIRECT_DNS) { "223.5.5.5" }
    var enableDnsRouting by configurationStore.boolean(Key.ENABLE_DNS_ROUTING) { true }
    var enableFakeDns by configurationStore.boolean(Key.ENABLE_FAKEDNS) { false }

    var rulesProvider by configurationStore.stringToInt(Key.RULES_PROVIDER) { 2 }
    var logLevel by configurationStore.stringToInt(Key.LOG_LEVEL) { 2 }
    var logBufSize by configurationStore.int(Key.LOG_BUF_SIZE) { 0 }
    var acquireWakeLock by configurationStore.boolean(Key.ACQUIRE_WAKE_LOCK)

    // hopefully hashCode = mHandle doesn't change, currently this is true from KitKat to Nougat
    private val userIndex by lazy { Binder.getCallingUserHandle().hashCode() }
    var mixedPort: Int
        get() = getLocalPort(Key.MIXED_PORT, 2080)
        set(value) = saveLocalPort(Key.MIXED_PORT, value)

    /**
     * Factory defaults (taken from the author's own backup). Written only for keys that have
     * never been stored, so existing user choices are never overwritten.
     */
    fun applyFactoryDefaults() {
        val dao = PublicDatabase.kvPairDao
        fun missing(key: String) = dao[key] == null
        val strings = mapOf(
            "speedInterval" to "1000", "appTLSVersion" to "1.2", "logLevel" to "2",
            "trafficSniffing" to "1", "domain_strategy_for_direct" to "ipv4_only",
            "domain_strategy_for_remote" to "ipv4_only", "domain_strategy_for_server" to "ipv4_only",
            "ipv6Mode" to "0", "mtu" to "1500", "tunImplementation" to "0", "rulesProvider" to "2",
            "directDns" to "223.5.5.5", "remoteDns" to "8.8.8.8", "serviceMode" to "vpn",
            "black_wall_level" to "PHANTOM", "appLanguage" to "en", "nightTheme" to "1",
            "connectionTestURL" to CONNECTION_TEST_URL,
        )
        val booleans = mapOf(
            "profileTrafficStatistics" to true, "showDirectSpeed" to true,
            "showGroupInNotification" to false, "meteredNetwork" to false,
            "networkChangeResetConnections" to true, "showBottomBar" to false,
            "enableFakeDns" to false, "wakeResetConnections" to true,
            "allowInsecureOnRequest" to true, "alwaysShowAddress" to true,
            "acquireWakeLock" to false, "bypassLan" to false, "resolveDestination" to false,
            "bypassLanInCore" to false, "enableDnsRouting" to true, "globalAllowInsecure" to true,
            "enableClashAPI" to false, "allowAccess" to false, "appendHttpProxy" to true,
            "proxyApps" to true, "black_wall_enabled" to false,
        )
        for ((k, v) in strings) if (missing(k)) configurationStore.putString(k, v)
        for ((k, v) in booleans) if (missing(k)) configurationStore.putBoolean(k, v)
        if (missing("connectionTestConcurrent")) configurationStore.putInt("connectionTestConcurrent", 5)
    }

    fun initGlobal() {
        if (configurationStore.getString(Key.MIXED_PORT) == null) {
            mixedPort = mixedPort
        }
    }


    private fun getLocalPort(key: String, default: Int): Int {
        return parsePort(configurationStore.getString(key), default + userIndex)
    }

    private fun saveLocalPort(key: String, value: Int) {
        configurationStore.putString(key, "$value")
    }

    var ipv6Mode by configurationStore.stringToInt(Key.IPV6_MODE) { IPv6Mode.DISABLE }

    var meteredNetwork by configurationStore.boolean(Key.METERED_NETWORK)
    var proxyApps by configurationStore.boolean(Key.PROXY_APPS) { true }
    var bypass by configurationStore.boolean(Key.BYPASS_MODE) { true }
    var individual by configurationStore.string(Key.INDIVIDUAL)
    var showDirectSpeed by configurationStore.boolean(Key.SHOW_DIRECT_SPEED) { true }

    // Auto Connect was removed: never restart the service on boot / app update.
    val persistAcrossReboot: Boolean get() = false

    var appendHttpProxy by configurationStore.boolean(Key.APPEND_HTTP_PROXY) { true }
    var connectionTestURL by configurationStore.string(Key.CONNECTION_TEST_URL) { CONNECTION_TEST_URL }
    var connectionTestConcurrent by configurationStore.int("connectionTestConcurrent") { 5 }
    var alwaysShowAddress by configurationStore.boolean(Key.ALWAYS_SHOW_ADDRESS) { true }

    var tunImplementation by configurationStore.stringToInt(Key.TUN_IMPLEMENTATION) { TunImplementation.GVISOR }
    var profileTrafficStatistics by configurationStore.boolean(Key.PROFILE_TRAFFIC_STATISTICS) { true }

    // protocol

    var globalAllowInsecure by configurationStore.boolean(Key.GLOBAL_ALLOW_INSECURE) { true }

    // old cache, DO NOT ADD

    var dirty by profileCacheStore.boolean(Key.PROFILE_DIRTY)
    var editingId by profileCacheStore.long(Key.PROFILE_ID)
    var editingGroup by profileCacheStore.long(Key.PROFILE_GROUP)
    var profileName by profileCacheStore.string(Key.PROFILE_NAME)
    var serverAddress by profileCacheStore.string(Key.SERVER_ADDRESS)
    var serverPort by profileCacheStore.stringToInt(Key.SERVER_PORT)
    var serverPorts by profileCacheStore.string("serverPorts")
    var serverUsername by profileCacheStore.string(Key.SERVER_USERNAME)
    var serverPassword by profileCacheStore.string(Key.SERVER_PASSWORD)
    var serverPassword1 by profileCacheStore.string(Key.SERVER_PASSWORD1)
    var serverMethod by profileCacheStore.string(Key.SERVER_METHOD)

    var sharedStorage by profileCacheStore.string("sharedStorage")

    var serverProtocol by profileCacheStore.string(Key.SERVER_PROTOCOL)
    var serverObfs by profileCacheStore.string(Key.SERVER_OBFS)

    var serverNetwork by profileCacheStore.string(Key.SERVER_NETWORK)
    var serverHost by profileCacheStore.string(Key.SERVER_HOST)
    var serverPath by profileCacheStore.string(Key.SERVER_PATH)
    var serverSNI by profileCacheStore.string(Key.SERVER_SNI)
    var serverEncryption by profileCacheStore.string(Key.SERVER_ENCRYPTION)
    var serverALPN by profileCacheStore.string(Key.SERVER_ALPN)
    var serverCertificates by profileCacheStore.string(Key.SERVER_CERTIFICATES)
    var serverMTU by profileCacheStore.stringToInt(Key.SERVER_MTU)
    var serverHeaders by profileCacheStore.string(Key.SERVER_HEADERS)
    var serverAllowInsecure by profileCacheStore.boolean(Key.SERVER_ALLOW_INSECURE)

    var serverAuthType by profileCacheStore.stringToInt(Key.SERVER_AUTH_TYPE)
    var serverUploadSpeed by profileCacheStore.stringToInt(Key.SERVER_UPLOAD_SPEED)
    var serverDownloadSpeed by profileCacheStore.stringToInt(Key.SERVER_DOWNLOAD_SPEED)
    var serverStreamReceiveWindow by profileCacheStore.stringToIntIfExists(Key.SERVER_STREAM_RECEIVE_WINDOW)
    var serverConnectionReceiveWindow by profileCacheStore.stringToIntIfExists(Key.SERVER_CONNECTION_RECEIVE_WINDOW)
    var serverDisableMtuDiscovery by profileCacheStore.boolean(Key.SERVER_DISABLE_MTU_DISCOVERY)
    var serverHopInterval by profileCacheStore.stringToInt(Key.SERVER_HOP_INTERVAL) { 10 }

    var protocolVersion by profileCacheStore.stringToInt(Key.PROTOCOL_VERSION) { 2 } // default is SOCKS5

    var serverProtocolInt by profileCacheStore.stringToInt(Key.SERVER_PROTOCOL)
    var serverPrivateKey by profileCacheStore.string(Key.SERVER_PRIVATE_KEY)
    var serverInsecureConcurrency by profileCacheStore.stringToInt(Key.SERVER_INSECURE_CONCURRENCY)

    var serverUDPRelayMode by profileCacheStore.string(Key.SERVER_UDP_RELAY_MODE)
    var serverCongestionController by profileCacheStore.string(Key.SERVER_CONGESTION_CONTROLLER)
    var serverDisableSNI by profileCacheStore.boolean(Key.SERVER_DISABLE_SNI)
    var serverReduceRTT by profileCacheStore.boolean(Key.SERVER_REDUCE_RTT)

    var routeName by profileCacheStore.string(Key.ROUTE_NAME)
    var routeDomain by profileCacheStore.string(Key.ROUTE_DOMAIN)
    var routeIP by profileCacheStore.string(Key.ROUTE_IP)
    var routePort by profileCacheStore.string(Key.ROUTE_PORT)
    var routeSourcePort by profileCacheStore.string(Key.ROUTE_SOURCE_PORT)
    var routeNetwork by profileCacheStore.string(Key.ROUTE_NETWORK)
    var routeSource by profileCacheStore.string(Key.ROUTE_SOURCE)
    var routeProtocol by profileCacheStore.string(Key.ROUTE_PROTOCOL)
    var routeOutbound by profileCacheStore.stringToInt(Key.ROUTE_OUTBOUND)
    var routeOutboundRule by profileCacheStore.long(Key.ROUTE_OUTBOUND + "Long")
    var routePackages by profileCacheStore.string(Key.ROUTE_PACKAGES)

    var frontProxy by profileCacheStore.long(Key.GROUP_FRONT_PROXY + "Long")
    var landingProxy by profileCacheStore.long(Key.GROUP_LANDING_PROXY + "Long")
    var frontProxyTmp by profileCacheStore.stringToInt(Key.GROUP_FRONT_PROXY)
    var landingProxyTmp by profileCacheStore.stringToInt(Key.GROUP_LANDING_PROXY)

    var serverConfig by profileCacheStore.string(Key.SERVER_CONFIG)
    var serverCustom by profileCacheStore.string(Key.SERVER_CUSTOM)
    var serverCustomOutbound by profileCacheStore.string(Key.SERVER_CUSTOM_OUTBOUND)

    var groupName by profileCacheStore.string(Key.GROUP_NAME)
    var groupType by profileCacheStore.stringToInt(Key.GROUP_TYPE)
    var groupOrder by profileCacheStore.stringToInt(Key.GROUP_ORDER)
    var groupIsSelector by profileCacheStore.boolean(Key.GROUP_IS_SELECTOR)

    var subscriptionLink by profileCacheStore.string(Key.SUBSCRIPTION_LINK)
    var subscriptionForceResolve by profileCacheStore.boolean(Key.SUBSCRIPTION_FORCE_RESOLVE)
    var subscriptionDeduplication by profileCacheStore.boolean(Key.SUBSCRIPTION_DEDUPLICATION)
    var subscriptionUpdateWhenConnectedOnly by profileCacheStore.boolean(Key.SUBSCRIPTION_UPDATE_WHEN_CONNECTED_ONLY)
    var subscriptionUserAgent by profileCacheStore.string(Key.SUBSCRIPTION_USER_AGENT)
    var subscriptionAutoUpdate by profileCacheStore.boolean(Key.SUBSCRIPTION_AUTO_UPDATE)
    var subscriptionAutoUpdateDelay by profileCacheStore.stringToInt(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY) { 360 }

    var rulesFirstCreate by profileCacheStore.boolean("rulesFirstCreate")

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
    }
}