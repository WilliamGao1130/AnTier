package com.antier.core

import android.content.Context

/** VPN 应用可见性模式：哪些应用可以使用 EasyTier VPN。 */
enum class VpnAppMode {
    /** 所有应用都可以使用。 */
    ALL,

    /** 仅白名单中的应用可以使用（Android 14+ 原生支持）。 */
    ALLOW_LIST,

    /** 除黑名单外所有应用都可以使用。 */
    DENY_LIST
}

/** VPN 路由模式：数据面覆盖范围。 */
enum class VpnRoutingMode {
    /** 仅虚拟网络（虚拟 IP 所在子网与代理 CIDR）。 */
    VIRTUAL_ONLY,

    /** 虚拟网络 + 互联网（全流量进入 TUN，互联网需组网内出口节点/代理子网）。 */
    FULL_TUNNEL
}

/** VPN 全局设置。 */
data class VpnSettings(
    val appMode: VpnAppMode = VpnAppMode.ALL,
    val allowedPackages: Set<String> = emptySet(),
    val deniedPackages: Set<String> = emptySet(),
    val routingMode: VpnRoutingMode = VpnRoutingMode.VIRTUAL_ONLY
)

/** VPN 全局设置的本地持久化（SharedPreferences）。 */
object VpnSettingsStore {
    private const val PREFS = "antier_vpn_settings"
    private const val KEY_APP_MODE = "app_mode"
    private const val KEY_ALLOWED = "allowed_packages"
    private const val KEY_DENIED = "denied_packages"
    private const val KEY_ROUTING = "routing_mode"

    fun load(context: Context): VpnSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return VpnSettings(
            appMode = parseEnum(
                prefs.getString(KEY_APP_MODE, null),
                VpnAppMode.ALL
            ),
            allowedPackages = HashSet(prefs.getStringSet(KEY_ALLOWED, emptySet()) ?: emptySet()),
            deniedPackages = HashSet(prefs.getStringSet(KEY_DENIED, emptySet()) ?: emptySet()),
            routingMode = parseEnum(
                prefs.getString(KEY_ROUTING, null),
                VpnRoutingMode.VIRTUAL_ONLY
            )
        )
    }

    fun save(context: Context, settings: VpnSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_APP_MODE, settings.appMode.name)
            .putStringSet(KEY_ALLOWED, settings.allowedPackages)
            .putStringSet(KEY_DENIED, settings.deniedPackages)
            .putString(KEY_ROUTING, settings.routingMode.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?, default: T): T {
        return raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
    }
}
