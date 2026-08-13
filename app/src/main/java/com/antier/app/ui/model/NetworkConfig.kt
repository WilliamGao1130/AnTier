package com.antier.app.ui.model

import java.util.UUID

/** 单个初始节点。 */
data class Peer(val uri: String = "", val peerPublicKey: String = "")

/** 子网代理条目。 */
data class ProxyNetwork(
    val cidr: String = "",
    val mappedCidr: String = "",
    val allow: List<String> = listOf("tcp", "udp", "icmp")
)

/** 端口转发条目。 */
data class PortForward(
    val bindAddr: String = "0.0.0.0:0",
    val dstAddr: String = "",
    val proto: String = "tcp"
)

/** VPN 门户（WireGuard 风格）。 */
data class VpnPortal(
    val clientCidr: String = "10.14.14.0/24",
    val wireguardListen: String = "0.0.0.0:11010"
)

/** 安全模式。 */
data class SecureMode(
    val enabled: Boolean = false,
    val localPrivateKey: String = "",
    val localPublicKey: String = ""
)

/**
 * [flags] 表：对应 common.proto FlagsInConfig 的全部字段。
 * 数值/字符串字段用 String 承载，便于表单留空。
 */
data class Flags(
    val defaultProtocol: String = "tcp",
    val devName: String = "",
    val enableEncryption: Boolean = true,
    val enableIpv6: Boolean = true,
    val mtu: String = "1380",
    val latencyFirst: Boolean = false,
    val enableExitNode: Boolean = false,
    val noTun: Boolean = false,
    val useSmoltcp: Boolean = false,
    val relayNetworkWhitelist: String = "*",
    val disableP2p: Boolean = false,
    val p2pOnly: Boolean = false,
    val lazyP2p: Boolean = false,
    val relayAllPeerRpc: Boolean = false,
    val disableTcpHolePunching: Boolean = false,
    val disableUdpHolePunching: Boolean = false,
    val multiThread: Boolean = true,
    val dataCompressAlgo: String = "none",
    val bindDevice: Boolean = true,
    val enableKcpProxy: Boolean = false,
    val disableKcpInput: Boolean = false,
    val disableRelayKcp: Boolean = false,
    val proxyForwardBySystem: Boolean = false,
    val acceptDns: Boolean = false,
    val privateMode: Boolean = false,
    val enableQuicProxy: Boolean = false,
    val disableQuicInput: Boolean = false,
    val disableRelayQuic: Boolean = false,
    val quicListenPort: String = "",
    val foreignRelayBpsLimit: String = "",
    val multiThreadCount: String = "2",
    val enableRelayForeignNetworkKcp: Boolean = false,
    val enableRelayForeignNetworkQuic: Boolean = false,
    val encryptionAlgorithm: String = "aes-gcm",
    val disableSymHolePunching: Boolean = false,
    val tldDnsZone: String = "et.net.",
    val needP2p: Boolean = false,
    val instanceRecvBpsLimit: String = "",
    val disableUpnp: Boolean = false,
    val disableRelayData: Boolean = false,
    val enableUdpBroadcastRelay: Boolean = false,
    val socketMark: String = ""
)

/**
 * 单个网络实例的完整配置模型，覆盖官方 TOML schema（easytier-core v2.6.4）。
 * ACL 等复杂结构以原始 TOML 文本承载，由 TOML 编辑器维护。
 */
data class NetworkConfig(
    val instanceId: String = UUID.randomUUID().toString(),
    val instanceName: String = "default",
    val hostname: String = "",
    val netns: String = "",
    val ipv4: String = "",
    val ipv6: String = "",
    val ipv6PublicAddrProvider: Boolean = false,
    val ipv6PublicAddrAuto: Boolean = false,
    val ipv6PublicAddrPrefix: String = "",
    val dhcp: Boolean = true,
    val networkName: String = "easytier",
    val networkSecret: String = "",
    val peers: List<Peer> = emptyList(),
    val listeners: List<String> = listOf("tcp://0.0.0.0:11010", "udp://0.0.0.0:11010"),
    val mappedListeners: List<String> = emptyList(),
    val proxyNetworks: List<ProxyNetwork> = emptyList(),
    val vpnPortal: VpnPortal? = null,
    val exitNodes: List<String> = emptyList(),
    val routes: List<String> = emptyList(),
    val socks5Proxy: String = "",
    val portForwards: List<PortForward> = emptyList(),
    val secureMode: SecureMode = SecureMode(),
    val flags: Flags = Flags(),
    val aclToml: String = "",
    val tcpWhitelist: List<String> = emptyList(),
    val udpWhitelist: List<String> = emptyList(),
    val stunServers: List<String> = emptyList(),
    val stunServersV6: List<String> = emptyList(),
    val credentialFile: String = "",
    val source: String = ""
) {
    /** 卡片与标题栏显示用的网络名称。 */
    val displayName: String
        get() = networkName.ifBlank { instanceName.ifBlank { "未命名网络" } }

    /** 带前缀的虚拟 IPv4（无前缀时补 /24）。 */
    val ipv4WithPrefix: String
        get() = when {
            ipv4.isBlank() -> ""
            "/" in ipv4 -> ipv4
            else -> "$ipv4/24"
        }

    /** 带前缀的虚拟 IPv6（无前缀时补 /64）。 */
    val ipv6WithPrefix: String
        get() = when {
            ipv6.isBlank() -> ""
            "/" in ipv6 -> ipv6
            else -> "$ipv6/64"
        }

    fun toToml(): String = buildString {
        // 顶层键必须出现在任何表之前
        append("instance_id = \"${esc(instanceId)}\"\n")
        append("instance_name = \"${esc(instanceName)}\"\n")
        if (hostname.isNotBlank()) append("hostname = \"${esc(hostname)}\"\n")
        if (netns.isNotBlank()) append("netns = \"${esc(netns)}\"\n")
        if (ipv4.isNotBlank()) append("ipv4 = \"${esc(ipv4)}\"\n")
        if (ipv6.isNotBlank()) append("ipv6 = \"${esc(ipv6)}\"\n")
        append("ipv6_public_addr_provider = $ipv6PublicAddrProvider\n")
        append("ipv6_public_addr_auto = $ipv6PublicAddrAuto\n")
        if (ipv6PublicAddrPrefix.isNotBlank()) {
            append("ipv6_public_addr_prefix = \"${esc(ipv6PublicAddrPrefix)}\"\n")
        }
        append("dhcp = $dhcp\n")
        if (listeners.isNotEmpty()) append("listeners = ${tomlStringArray(listeners)}\n")
        if (mappedListeners.isNotEmpty()) {
            append("mapped_listeners = ${tomlStringArray(mappedListeners)}\n")
        }
        if (exitNodes.isNotEmpty()) append("exit_nodes = ${tomlStringArray(exitNodes)}\n")
        if (routes.isNotEmpty()) append("routes = ${tomlStringArray(routes)}\n")
        if (socks5Proxy.isNotBlank()) append("socks5_proxy = \"${esc(socks5Proxy)}\"\n")
        if (tcpWhitelist.isNotEmpty()) append("tcp_whitelist = ${tomlStringArray(tcpWhitelist)}\n")
        if (udpWhitelist.isNotEmpty()) append("udp_whitelist = ${tomlStringArray(udpWhitelist)}\n")
        if (stunServers.isNotEmpty()) append("stun_servers = ${tomlStringArray(stunServers)}\n")
        if (stunServersV6.isNotEmpty()) {
            append("stun_servers_v6 = ${tomlStringArray(stunServersV6)}\n")
        }
        if (credentialFile.isNotBlank()) {
            append("credential_file = \"${esc(credentialFile)}\"\n")
        }

        append("\n[network_identity]\n")
        append("network_name = \"${esc(networkName)}\"\n")
        if (networkSecret.isNotBlank()) {
            append("network_secret = \"${esc(networkSecret)}\"\n")
        }

        peers.filter { it.uri.isNotBlank() }.forEach { peer ->
            append("\n[[peer]]\n")
            append("uri = \"${esc(peer.uri)}\"\n")
            if (peer.peerPublicKey.isNotBlank()) {
                append("peer_public_key = \"${esc(peer.peerPublicKey)}\"\n")
            }
        }

        proxyNetworks.filter { it.cidr.isNotBlank() }.forEach { proxy ->
            append("\n[[proxy_network]]\n")
            append("cidr = \"${esc(proxy.cidr)}\"\n")
            if (proxy.mappedCidr.isNotBlank()) {
                append("mapped_cidr = \"${esc(proxy.mappedCidr)}\"\n")
            }
            if (proxy.allow.isNotEmpty()) {
                append("allow = ${tomlStringArray(proxy.allow)}\n")
            }
        }

        vpnPortal?.let { portal ->
            append("\n[vpn_portal_config]\n")
            append("client_cidr = \"${esc(portal.clientCidr)}\"\n")
            append("wireguard_listen = \"${esc(portal.wireguardListen)}\"\n")
        }

        portForwards.filter { it.bindAddr.isNotBlank() }.forEach { pf ->
            append("\n[[port_forward]]\n")
            append("bind_addr = \"${esc(pf.bindAddr)}\"\n")
            if (pf.dstAddr.isNotBlank()) append("dst_addr = \"${esc(pf.dstAddr)}\"\n")
            append("proto = \"${esc(pf.proto)}\"\n")
        }

        if (secureMode.enabled || secureMode.localPrivateKey.isNotBlank() ||
            secureMode.localPublicKey.isNotBlank()
        ) {
            append("\n[secure_mode]\n")
            append("enabled = ${secureMode.enabled}\n")
            if (secureMode.localPrivateKey.isNotBlank()) {
                append("local_private_key = \"${esc(secureMode.localPrivateKey)}\"\n")
            }
            if (secureMode.localPublicKey.isNotBlank()) {
                append("local_public_key = \"${esc(secureMode.localPublicKey)}\"\n")
            }
        }

        // 与官方 dump 行为一致：只写出与内核默认值不同的 flags。
        // 枚举（data_compress_algo）必须使用 proto 原始名（None/Zstd），
        // 小写 "none" 会导致内核 serde 解析失败。
        append("\n[flags]\n")
        val f = flags

        fun bool(name: String, value: Boolean, def: Boolean) {
            if (value != def) append("$name = $value\n")
        }

        fun str(name: String, value: String, def: String) {
            if (value != def && value.isNotBlank()) append("$name = \"${esc(value)}\"\n")
        }

        fun num(name: String, value: String, def: String) {
            if (value.isNotBlank() && value != def) append("$name = $value\n")
        }

        str("default_protocol", f.defaultProtocol, "tcp")
        str("dev_name", f.devName, "")
        bool("enable_encryption", f.enableEncryption, true)
        bool("enable_ipv6", f.enableIpv6, true)
        num("mtu", f.mtu, "1380")
        bool("latency_first", f.latencyFirst, false)
        bool("enable_exit_node", f.enableExitNode, false)
        bool("no_tun", f.noTun, false)
        bool("use_smoltcp", f.useSmoltcp, false)
        str("relay_network_whitelist", f.relayNetworkWhitelist, "*")
        bool("disable_p2p", f.disableP2p, false)
        bool("p2p_only", f.p2pOnly, false)
        bool("lazy_p2p", f.lazyP2p, false)
        bool("relay_all_peer_rpc", f.relayAllPeerRpc, false)
        bool("disable_tcp_hole_punching", f.disableTcpHolePunching, false)
        bool("disable_udp_hole_punching", f.disableUdpHolePunching, false)
        bool("multi_thread", f.multiThread, true)
        if (f.dataCompressAlgo != "none") {
            append("data_compress_algo = \"${esc(compressAlgo(f.dataCompressAlgo))}\"\n")
        }
        bool("bind_device", f.bindDevice, true)
        bool("enable_kcp_proxy", f.enableKcpProxy, false)
        bool("disable_kcp_input", f.disableKcpInput, false)
        bool("disable_relay_kcp", f.disableRelayKcp, false)
        bool("proxy_forward_by_system", f.proxyForwardBySystem, false)
        bool("accept_dns", f.acceptDns, false)
        bool("private_mode", f.privateMode, false)
        bool("enable_quic_proxy", f.enableQuicProxy, false)
        bool("disable_quic_input", f.disableQuicInput, false)
        bool("disable_relay_quic", f.disableRelayQuic, false)
        num("quic_listen_port", f.quicListenPort, "")
        num("foreign_relay_bps_limit", f.foreignRelayBpsLimit, "")
        num("multi_thread_count", f.multiThreadCount, "2")
        bool("enable_relay_foreign_network_kcp", f.enableRelayForeignNetworkKcp, false)
        bool("enable_relay_foreign_network_quic", f.enableRelayForeignNetworkQuic, false)
        if (f.encryptionAlgorithm != "aes-gcm") {
            append("encryption_algorithm = \"${esc(f.encryptionAlgorithm)}\"\n")
        }
        bool("disable_sym_hole_punching", f.disableSymHolePunching, false)
        str("tld_dns_zone", f.tldDnsZone, "et.net.")
        bool("need_p2p", f.needP2p, false)
        num("instance_recv_bps_limit", f.instanceRecvBpsLimit, "")
        bool("disable_upnp", f.disableUpnp, false)
        bool("disable_relay_data", f.disableRelayData, false)
        bool("enable_udp_broadcast_relay", f.enableUdpBroadcastRelay, false)
        num("socket_mark", f.socketMark, "")

        if (aclToml.isNotBlank()) {
            append("\n").append(aclToml.trim()).append('\n')
        }
        if (source.isNotBlank()) {
            append("\n[source]\n")
            append("source = \"${esc(source)}\"\n")
        }
    }

    companion object {
        /** 新网络默认配置。 */
        fun default(): NetworkConfig = NetworkConfig(
            instanceId = UUID.randomUUID().toString(),
            instanceName = "default",
            networkName = "新网络"
        )

        fun fromToml(toml: String): NetworkConfig {
            fun grab(key: String): String? =
                Regex("""(?m)^\s*$key\s*=\s*"([^"]*)"""").find(toml)?.groupValues?.get(1)
            fun bool(key: String, default: Boolean): Boolean =
                Regex("""(?m)^\s*$key\s*=\s*(true|false)\b""").find(toml)
                    ?.groupValues?.get(1)?.let { it == "true" } ?: default
            fun stringArray(key: String): List<String> =
                Regex("""(?m)^\s*$key\s*=\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                    .find(toml)?.groupValues?.get(1)
                    ?.let { section ->
                        Regex("""\"([^\"]+)\"""").findAll(section).map { it.groupValues[1] }.toList()
                    }
                    .orEmpty()

            fun table(name: String): String =
                Regex("""(?is)^\s*\[$name\]\s*(.*?)(?=^\s*\[|\z)""", RegexOption.MULTILINE)
                    .find(toml)?.groupValues?.get(1).orEmpty()

            fun arrayTable(name: String): List<String> =
                Regex("""(?is)^\s*\[\[$name\]\]\s*(.*?)(?=^\s*\[\[|\z)""", RegexOption.MULTILINE)
                    .findAll(toml).map { it.groupValues[1] }.toList()

            fun tableGrab(block: String, key: String): String =
                Regex("""(?im)^\s*$key\s*=\s*"([^"]*)"""").find(block)
                    ?.groupValues?.get(1).orEmpty()
            fun tableBool(block: String, key: String, default: Boolean): Boolean =
                Regex("""(?im)^\s*$key\s*=\s*(true|false)\b""").find(block)
                    ?.groupValues?.get(1)?.let { it == "true" } ?: default

            val identity = table("network_identity")
            val flagsBlock = table("flags")
            val portalBlock = table("vpn_portal_config")
            val secureBlock = table("secure_mode")
            val sourceBlock = table("source")

            fun flagBool(key: String, default: Boolean): Boolean =
                Regex("""(?im)^\s*$key\s*=\s*(true|false)\b""").find(flagsBlock)
                    ?.groupValues?.get(1)?.let { it == "true" } ?: default
            fun flagStr(key: String, default: String): String =
                Regex("""(?im)^\s*$key\s*=\s*"([^"]*)"""").find(flagsBlock)
                    ?.groupValues?.get(1).orEmpty().ifBlank { default }
            fun flagNum(key: String, default: String): String =
                Regex("""(?im)^\s*$key\s*=\s*(\d+)""").find(flagsBlock)
                    ?.groupValues?.get(1) ?: default

            val peers = arrayTable("peer").mapNotNull { block ->
                val uri = tableGrab(block, "uri")
                if (uri.isBlank()) null
                else Peer(uri, tableGrab(block, "peer_public_key"))
            }
            val proxyNetworks = arrayTable("proxy_network").mapNotNull { block ->
                val cidr = tableGrab(block, "cidr")
                if (cidr.isBlank()) null
                else ProxyNetwork(
                    cidr = cidr,
                    mappedCidr = tableGrab(block, "mapped_cidr"),
                    allow = Regex("""(?i)allow\s*=\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                        .find(block)?.groupValues?.get(1)
                        ?.let { section ->
                            Regex("""\"([^\"]+)\"""").findAll(section)
                                .map { it.groupValues[1] }.toList()
                        }
                        ?: listOf("tcp", "udp", "icmp")
                )
            }
            val portForwards = arrayTable("port_forward").mapNotNull { block ->
                val bind = tableGrab(block, "bind_addr")
                if (bind.isBlank()) null
                else PortForward(
                    bindAddr = bind,
                    dstAddr = tableGrab(block, "dst_addr"),
                    proto = tableGrab(block, "proto").ifBlank { "tcp" }
                )
            }

            val aclRaw = Regex(
                """(?is)^\s*\[acl\]\s*(.*?)(?=^\s*\[|\z)""",
                RegexOption.MULTILINE
            ).find(toml)?.groupValues?.get(1)?.trim()

            return NetworkConfig(
                instanceId = grab("instance_id") ?: UUID.randomUUID().toString(),
                instanceName = grab("instance_name") ?: "default",
                hostname = grab("hostname").orEmpty(),
                netns = grab("netns").orEmpty(),
                ipv4 = grab("ipv4").orEmpty(),
                ipv6 = grab("ipv6").orEmpty(),
                ipv6PublicAddrProvider = bool("ipv6_public_addr_provider", false),
                ipv6PublicAddrAuto = bool("ipv6_public_addr_auto", false),
                ipv6PublicAddrPrefix = grab("ipv6_public_addr_prefix").orEmpty(),
                dhcp = bool("dhcp", true),
                networkName = tableGrab(identity, "network_name").ifBlank { "easytier" },
                networkSecret = tableGrab(identity, "network_secret"),
                peers = peers,
                listeners = stringArray("listeners").ifEmpty {
                    listOf("tcp://0.0.0.0:11010", "udp://0.0.0.0:11010")
                },
                mappedListeners = stringArray("mapped_listeners"),
                proxyNetworks = proxyNetworks,
                vpnPortal = if (portalBlock.isNotBlank()) {
                    VpnPortal(
                        clientCidr = tableGrab(portalBlock, "client_cidr")
                            .ifBlank { "10.14.14.0/24" },
                        wireguardListen = tableGrab(portalBlock, "wireguard_listen")
                            .ifBlank { "0.0.0.0:11010" }
                    )
                } else {
                    null
                },
                exitNodes = stringArray("exit_nodes"),
                routes = stringArray("routes"),
                socks5Proxy = grab("socks5_proxy").orEmpty(),
                portForwards = portForwards,
                secureMode = SecureMode(
                    enabled = tableBool(secureBlock, "enabled", false),
                    localPrivateKey = tableGrab(secureBlock, "local_private_key"),
                    localPublicKey = tableGrab(secureBlock, "local_public_key")
                ),
                flags = Flags(
                    defaultProtocol = flagStr("default_protocol", "tcp"),
                    devName = flagStr("dev_name", ""),
                    enableEncryption = flagBool("enable_encryption", true),
                    enableIpv6 = flagBool("enable_ipv6", true),
                    mtu = flagNum("mtu", "1380"),
                    latencyFirst = flagBool("latency_first", false),
                    enableExitNode = flagBool("enable_exit_node", false),
                    noTun = flagBool("no_tun", false),
                    useSmoltcp = flagBool("use_smoltcp", false),
                    relayNetworkWhitelist = flagStr("relay_network_whitelist", "*"),
                    disableP2p = flagBool("disable_p2p", false),
                    p2pOnly = flagBool("p2p_only", false),
                    lazyP2p = flagBool("lazy_p2p", false),
                    relayAllPeerRpc = flagBool("relay_all_peer_rpc", false),
                    disableTcpHolePunching = flagBool("disable_tcp_hole_punching", false),
                    disableUdpHolePunching = flagBool("disable_udp_hole_punching", false),
                    multiThread = flagBool("multi_thread", true),
                    dataCompressAlgo = normalizeCompressAlgo(
                        flagStr("data_compress_algo", "none")
                    ),
                    bindDevice = flagBool("bind_device", true),
                    enableKcpProxy = flagBool("enable_kcp_proxy", false),
                    disableKcpInput = flagBool("disable_kcp_input", false),
                    disableRelayKcp = flagBool("disable_relay_kcp", false),
                    proxyForwardBySystem = flagBool("proxy_forward_by_system", false),
                    acceptDns = flagBool("accept_dns", false),
                    privateMode = flagBool("private_mode", false),
                    enableQuicProxy = flagBool("enable_quic_proxy", false),
                    disableQuicInput = flagBool("disable_quic_input", false),
                    disableRelayQuic = flagBool("disable_relay_quic", false),
                    quicListenPort = flagNum("quic_listen_port", ""),
                    foreignRelayBpsLimit = flagNum("foreign_relay_bps_limit", ""),
                    multiThreadCount = flagNum("multi_thread_count", "2"),
                    enableRelayForeignNetworkKcp =
                        flagBool("enable_relay_foreign_network_kcp", false),
                    enableRelayForeignNetworkQuic =
                        flagBool("enable_relay_foreign_network_quic", false),
                    encryptionAlgorithm = flagStr("encryption_algorithm", "aes-gcm"),
                    disableSymHolePunching = flagBool("disable_sym_hole_punching", false),
                    tldDnsZone = flagStr("tld_dns_zone", "et.net."),
                    needP2p = flagBool("need_p2p", false),
                    instanceRecvBpsLimit = flagNum("instance_recv_bps_limit", ""),
                    disableUpnp = flagBool("disable_upnp", false),
                    disableRelayData = flagBool("disable_relay_data", false),
                    enableUdpBroadcastRelay = flagBool("enable_udp_broadcast_relay", false),
                    socketMark = flagNum("socket_mark", "")
                ),
                aclToml = aclRaw?.let { "[acl]\n$it" }.orEmpty(),
                tcpWhitelist = stringArray("tcp_whitelist"),
                udpWhitelist = stringArray("udp_whitelist"),
                stunServers = stringArray("stun_servers"),
                stunServersV6 = stringArray("stun_servers_v6"),
                credentialFile = grab("credential_file").orEmpty(),
                source = tableGrab(sourceBlock, "source")
            )
        }

        private fun esc(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun tomlStringArray(values: List<String>): String =
            values.joinToString(", ", prefix = "[", postfix = "]") { "\"${esc(it)}\"" }

        /** 表单里用的小写值 -> pbjson 要求的 proto 原始名。 */
        private fun compressAlgo(value: String): String = when (value.trim().lowercase()) {
            "none" -> "None"
            "zstd" -> "Zstd"
            "invalid" -> "Invalid"
            else -> value
        }

        /** proto 原始名 -> 表单里的小写值。 */
        private fun normalizeCompressAlgo(value: String): String = when (value.trim().lowercase()) {
            "none", "invalid" -> value.trim().lowercase()
            "zstd" -> "zstd"
            else -> value
        }
    }
}
