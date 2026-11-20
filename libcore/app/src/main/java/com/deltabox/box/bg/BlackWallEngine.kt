package com.deltabox.box.bg

import com.deltabox.box.database.DataStore
import com.deltabox.box.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.SingBoxOptions.OutboundTLSOptions
import moe.matsuri.nb4a.SingBoxOptions.OutboundUTLSOptions

/**
 * Black Wall — Anti-DPI Stealth Engine
 *
 * Ported from ChimeraWire's BlackWallEngine and adapted to run against
 * DeltaBox's sing-box based config builder instead of Xray JSON.
 *
 * Levels:
 *  Ghost   — SNI camouflage: replaces the outbound's TLS server_name with a
 *            whitelisted, never-blocked CDN domain (sing-box `tls.server_name`).
 *  Phantom — Ghost + randomized uTLS browser fingerprint
 *            (sing-box `tls.utls`, natively supported by the bundled core).
 *  Wraith  — Phantom + periodic noise HTTPS requests to legitimate domains,
 *            run alongside the active tunnel, so the traffic pattern looks
 *            like ordinary browsing.
 *
 * Note on TLS ClientHello fragmentation: ChimeraWire's Xray build dials
 * through a fragmenting "freedom" outbound to split the ClientHello into
 * multiple TCP segments. The sing-box core bundled with this DeltaBox build
 * does not expose an equivalent dialer-level field (no `tls_fragment` in
 * SingBoxOptions), so that specific layer cannot be wired up without
 * rebuilding the native core — Black Wall here applies the two layers that
 * the bundled core actually supports (SNI camouflage + uTLS), plus noise
 * injection, which is pure app-level traffic and needs no core support.
 *
 * Never touches REALITY outbounds: REALITY already performs its own TLS
 * camouflage via a genuine uTLS ClientHello that the server authenticates;
 * overwriting server_name/utls there breaks the handshake.
 */
object BlackWallEngine {

    private const val KEY_ENABLED = "black_wall_enabled"
    private const val KEY_LEVEL = "black_wall_level"

    enum class StealthLevel { OFF, GHOST, PHANTOM, WRAITH }

    private val CAMOUFLAGE_DOMAINS = listOf(
        "www.bing.com",
        "www.microsoft.com",
        "login.microsoftonline.com",
        "discord.com",
        "www.cloudflare.com",
        "cdn.jsdelivr.net",
        "ajax.googleapis.com",
        "fonts.googleapis.com",
    )

    private val UTLS_FINGERPRINTS = listOf("chrome", "firefox", "safari", "randomized")

    private val NOISE_TARGETS = listOf(
        "https://www.bing.com/favicon.ico",
        "https://www.cloudflare.com/favicon.ico",
        "https://fonts.googleapis.com/css?family=Roboto",
        "https://ajax.googleapis.com/ajax/libs/jquery/3.6.0/jquery.min.js",
    )

    private val store get() = DataStore.configurationStore

    var enabled: Boolean
        get() = store.getBoolean(KEY_ENABLED, false)
        set(value) {
            store.putBoolean(KEY_ENABLED, value)
            Logs.d("BlackWall: ${if (value) "ENABLED (${level.name})" else "DISABLED"}")
        }

    var level: StealthLevel
        get() = try {
            StealthLevel.valueOf(store.getString(KEY_LEVEL, StealthLevel.GHOST.name)!!)
        } catch (_: Exception) {
            StealthLevel.GHOST
        }
        set(value) {
            store.putString(KEY_LEVEL, value.name)
            Logs.d("BlackWall: level -> ${value.name}")
        }

    fun randomCamouflageDomain(): String = CAMOUFLAGE_DOMAINS.random()
    fun randomFingerprint(): String = UTLS_FINGERPRINTS.random()

    /**
     * Applies Black Wall to one sing-box outbound, if that outbound exposes a
     * `tls` field (reflection, mirrors the existing udp_over_tcp/mux hooks in
     * ConfigBuilder so this works across every Outbound_XXXOptions subtype
     * without needing a case for each protocol).
     */
    fun applyToOutbound(outbound: Any) {
        if (!enabled) return
        val lvl = level
        if (lvl == StealthLevel.OFF) return

        try {
            val tlsField = outbound.javaClass.getField("tls")
            val tls = tlsField.get(outbound) as? OutboundTLSOptions ?: return
            if (tls.enabled != true) return

            // REALITY already supplies its own camouflage; leave it alone.
            if (tls.reality?.enabled == true) {
                Logs.d("BlackWall: REALITY outbound detected, skipping")
                return
            }

            // Layer 1: SNI camouflage (all levels), only if not already set by the profile.
            if (tls.server_name.isNullOrBlank()) {
                tls.server_name = randomCamouflageDomain()
                Logs.d("BlackWall: SNI -> ${tls.server_name}")
            }

            // Layer 2: uTLS fingerprint rotation (Phantom+), only if not already set.
            if (lvl == StealthLevel.PHANTOM || lvl == StealthLevel.WRAITH) {
                if (tls.utls == null || tls.utls?.enabled != true) {
                    tls.utls = OutboundUTLSOptions().apply {
                        enabled = true
                        fingerprint = randomFingerprint()
                    }
                }
            }
        } catch (_: NoSuchFieldException) {
            // This outbound type has no tls field (e.g. plain socks/direct) - nothing to do.
        } catch (e: Exception) {
            Logs.w("BlackWall: failed to apply to outbound: ${e.message ?: e}")
        }
    }

    private var noiseJob: Job? = null

    /** Layer 3: noise injection, Wraith level only, runs for the lifetime of the tunnel. */
    fun startNoiseInjection(scope: CoroutineScope) {
        if (!enabled || level != StealthLevel.WRAITH) return
        stopNoiseInjection()
        noiseJob = scope.launch(Dispatchers.IO) {
            Logs.d("BlackWall: noise injection started (Wraith)")
            while (isActive) {
                try {
                    val url = NOISE_TARGETS.random()
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 3000
                    conn.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                    conn.connect()
                    conn.disconnect()
                } catch (_: Exception) {
                    // Best-effort; noise failures must never affect the real tunnel.
                }
                kotlinx.coroutines.delay((15_000L..45_000L).random())
            }
        }
    }

    fun stopNoiseInjection() {
        noiseJob?.cancel()
        noiseJob = null
        Logs.d("BlackWall: noise injection stopped")
    }
}
