package io.nekohasekai.sagernet.fmt.wireguard

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma

fun genReserved(anyStr: String): String {
    try {
        val list = anyStr.listByLineOrComma()
        val ba = ByteArray(3)
        if (list.size == 3) {
            list.forEachIndexed { index, s ->
                val i = s
                    .replace("[", "")
                    .replace("]", "")
                    .replace(" ", "")
                    .toIntOrNull() ?: return anyStr
                ba[index] = i.toByte()
            }
            return Util.b64EncodeOneLine(ba)
        } else {
            return anyStr
        }
    } catch (e: Exception) {
        return anyStr
    }
}

fun buildSingBoxOutboundWireguardBean(bean: WireGuardBean): SingBoxOptions.Outbound_WireGuardOptions {
    return SingBoxOptions.Outbound_WireGuardOptions().apply {
        type = "wireguard"
        server = bean.serverAddress
        server_port = bean.serverPort
        local_address = bean.localAddress.listByLineOrComma()
        private_key = bean.privateKey
        peer_public_key = bean.peerPublicKey
        pre_shared_key = bean.peerPreSharedKey
        mtu = bean.mtu
        if (bean.reserved.isNotBlank()) reserved = genReserved(bean.reserved)

        // AmneziaWG obfuscation. Only emitted when actually set (non-zero /
        // non-blank), so an ordinary WireGuard bean produces the exact same
        // outbound JSON as before this feature existed (sing-box's
        // option.WireGuardEndpointOptions fields are all `omitempty`).
        if (bean.jc != 0) jc = bean.jc
        if (bean.jmin != 0) jmin = bean.jmin
        if (bean.jmax != 0) jmax = bean.jmax
        if (bean.s1 != 0) s1 = bean.s1
        if (bean.s2 != 0) s2 = bean.s2
        if (bean.s3 != 0) s3 = bean.s3
        if (bean.s4 != 0) s4 = bean.s4
        if (bean.h1.isNotBlank()) h1 = bean.h1
        if (bean.h2.isNotBlank()) h2 = bean.h2
        if (bean.h3.isNotBlank()) h3 = bean.h3
        if (bean.h4.isNotBlank()) h4 = bean.h4
        if (bean.headerProtectionKey.isNotBlank()) header_protection_key = bean.headerProtectionKey
    }
}
