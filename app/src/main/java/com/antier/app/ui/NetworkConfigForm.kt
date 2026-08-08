package com.antier.app.ui

/**
 * 网络实例配置的表单模型。
 *
 * 与当前 EasyTier 内核（v2.6.4 main）的 TOML schema 对应：
 * 顶层键（instance_name/hostname/ipv4/listeners/routes/socks5_proxy）、
 * [[peer]] / [[proxy_network]] 数组、[network_identity] / [flags] / [console_logger] 表。
 * 旧的扁平键（inst_name/network/network_secret 等）会被内核静默忽略，生成时统一使用新 schema。
 */
data class NetworkConfigForm(
    val instanceName: String = "antier_android",
    val hostname: String = "antier-android",
    val ipv4: String = "",
    val dhcp: Boolean = true,
    val networkName: String = "antier_demo_net",
    val networkSecret: String = "antier_demo_secret",
    val listeners: List<String> = listOf("tcp://0.0.0.0:11010", "udp://0.0.0.0:11010"),
    val peers: List<String> = emptyList(),
    val proxyCidrs: List<String> = emptyList(),
    val manualRoutes: List<String> = emptyList(),
    val noTun: Boolean = false,
    val useSmoltcp: Boolean = false,
    val socks5Port: String = "",
    val enableEncryption: Boolean = true,
    val enableIpv6: Boolean = true,
    val mtu: String = "1380",
    val enableExitNode: Boolean = false,
    val latencyFirst: Boolean = false,
    val logLevel: String = "info"
) {

    fun toToml(): String = buildString {
        // 顶层键必须出现在任何表之前
        append("instance_name = \"${esc(instanceName)}\"\n")
        if (hostname.isNotBlank()) {
            append("hostname = \"${esc(hostname)}\"\n")
        }
        if (ipv4.isNotBlank()) {
            append("ipv4 = \"${esc(ipv4)}\"\n")
        }
        // 指定了静态 IP 时关闭 DHCP，避免二者冲突
        append("dhcp = ").append(ipv4.isBlank()).append('\n')
        if (listeners.isNotEmpty()) {
            append("listeners = ").append(tomlStringArray(listeners)).append('\n')
        }
        if (manualRoutes.isNotEmpty()) {
            append("routes = ").append(tomlStringArray(manualRoutes)).append('\n')
        }
        if (socks5Port.isNotBlank()) {
            append("socks5_proxy = \"socks5://127.0.0.1:${esc(socks5Port)}\"\n")
        }

        peers.forEach { peer ->
            append("\n[[peer]]\n")
            append("uri = \"${esc(peer)}\"\n")
        }
        proxyCidrs.forEach { cidr ->
            append("\n[[proxy_network]]\n")
            append("cidr = \"${esc(cidr)}\"\n")
            append("allow = [\"tcp\", \"udp\", \"icmp\"]\n")
        }

        append("\n[network_identity]\n")
        append("network_name = \"${esc(networkName)}\"\n")
        append("network_secret = \"${esc(networkSecret)}\"\n")

        append("\n[flags]\n")
        append("enable_encryption = ").append(enableEncryption).append('\n')
        append("enable_ipv6 = ").append(enableIpv6).append('\n')
        append("mtu = ").append(mtu.toIntOrNull() ?: 1380).append('\n')
        append("no_tun = ").append(noTun).append('\n')
        append("use_smoltcp = ").append(useSmoltcp).append('\n')
        append("enable_exit_node = ").append(enableExitNode).append('\n')
        append("latency_first = ").append(latencyFirst).append('\n')

        if (logLevel.isNotBlank()) {
            append("\n[console_logger]\n")
            append("level = \"${esc(logLevel)}\"\n")
        }
    }

    companion object {

        /** 从（用户编辑的）TOML 文本还原表单，尽力而为，兼容新旧键名。 */
        fun fromToml(toml: String): NetworkConfigForm {
            fun grab(pattern: String): String? =
                Regex(pattern, RegexOption.IGNORE_CASE).find(toml)?.groupValues?.get(1)
                    ?.trim()?.trim('"', ' ')

            val flagsSection = Regex(
                """(?is)\[flags\](.*?)(?=\n\s*\[|\z)"""
            ).find(toml)?.groupValues?.get(1).orEmpty()
            fun flag(name: String): Boolean =
                Regex("""(?i)\b$name\s*=\s*true\b""").containsMatchIn(flagsSection)
            fun flagValue(name: String): String? =
                Regex("""(?i)\b$name\s*=\s*"?([^"\s#]+)""")
                    .find(flagsSection)?.groupValues?.get(1)

            fun stringArray(key: String): List<String> =
                Regex("""(?i)\b$key\s*=\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
                    .find(toml)?.groupValues?.get(1)
                    ?.let { section ->
                        Regex("""\"([^\"]+)\"""").findAll(section).map { it.groupValues[1] }.toList()
                    }
                    .orEmpty()

            fun entries(table: String, field: String): List<String> =
                Regex("""(?is)\[\[$table\]\](.*?)(?=\n\s*\[\[|\n\s*\[|\z)""")
                    .findAll(toml)
                    .mapNotNull { match ->
                        Regex("""(?i)\b$field\s*=\s*"([^"]+)"""")
                            .find(match.groupValues[1])?.groupValues?.get(1)
                    }
                    .toList()

            val identity = Regex(
                """(?is)\[network_identity\](.*?)(?=\n\s*\[|\z)"""
            ).find(toml)?.groupValues?.get(1).orEmpty()
            fun identityValue(name: String): String =
                Regex("""(?i)\b$name\s*=\s*"([^"]*)"""")
                    .find(identity)?.groupValues?.get(1).orEmpty()

            val logger = Regex(
                """(?is)\[console_logger\](.*?)(?=\n\s*\[|\z)"""
            ).find(toml)?.groupValues?.get(1).orEmpty()
            val logLevel = Regex("""(?i)\blevel\s*=\s*"([^"]+)"""")
                .find(logger)?.groupValues?.get(1)
                ?: grab("""(?m)^\s*log_level\s*=\s*"([^"]+)""")
                ?: "info"

            val socks5Port = Regex(
                """(?i)socks5_proxy\s*=\s*"(?:socks5://)?[^":]*:(\d+)"""
            ).find(toml)?.groupValues?.get(1)

            return NetworkConfigForm(
                instanceName = grab("""(?m)^\s*(?:instance_name|inst_name)\s*=\s*"([^"]+)"""")
                    ?: "antier_android",
                hostname = grab("""(?m)^\s*hostname\s*=\s*"([^"]+)"""").orEmpty(),
                ipv4 = grab("""(?m)^\s*ipv4\s*=\s*"([^"]+)"""").orEmpty(),
                dhcp = Regex("""(?i)\bdhcp\s*=\s*true\b""").containsMatchIn(toml),
                networkName = identityValue("network_name"),
                networkSecret = identityValue("network_secret"),
                listeners = stringArray("listeners"),
                peers = entries("peer", "uri"),
                proxyCidrs = entries("proxy_network", "cidr"),
                manualRoutes = stringArray("routes"),
                noTun = flag("no_tun"),
                useSmoltcp = flag("use_smoltcp"),
                socks5Port = socks5Port.orEmpty(),
                enableEncryption = flag("enable_encryption"),
                enableIpv6 = flag("enable_ipv6"),
                mtu = flagValue("mtu") ?: "1380",
                enableExitNode = flag("enable_exit_node"),
                latencyFirst = flag("latency_first"),
                logLevel = logLevel
            )
        }

        /** 把用户输入按行/逗号拆成条目，过滤空行。 */
        fun parseEntries(text: String): List<String> =
            text.lines()
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        private fun esc(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun tomlStringArray(values: List<String>): String =
            values.joinToString(", ", prefix = "[", postfix = "]") { "\"${esc(it)}\"" }
    }
}
