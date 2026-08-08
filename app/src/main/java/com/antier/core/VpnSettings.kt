package com.antier.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 包级连接逻辑。
 *
 * - USE_VNET_CLOSE_OTHERS：使用虚拟网段，关闭其他网络（该包流量进入 VPN，
 *   只访问虚拟网段；启动对应 VPN 网络时按冲突策略关闭其它 VPN 网络）。
 * - USE_VNET_PROVIDE_OTHERS：使用虚拟网段，提供其他网络（进入 VPN，并可经
 *   出口节点访问其他网络/互联网）。
 * - NO_VNET_PROVIDE_OTHERS：关闭虚拟网段，提供其他网络（绕过 VPN，走原网络）。
 *
 * 注：Android VpnService 只能按包允许/绕过；A/B 的路由差异（仅虚拟网段 vs
 * 含其他网络）由会话级路由决定，无法按包分开。
 */
enum class PackageConnectionMode {
    USE_VNET_CLOSE_OTHERS,
    USE_VNET_PROVIDE_OTHERS,
    NO_VNET_PROVIDE_OTHERS
}

/** VPN 冲突策略：多个需要 VPN 的网络同时启动时的处理方式。 */
enum class VpnConflictPolicy {
    /** 先行者优先：先启动的网络获胜，新网络谦让（关闭自己）。 */
    FIRST_COMES_FIRST,

    /** 后来者优先：新网络挤占（关闭先启动的 VPN 网络）。 */
    LAST_COMES_FIRST
}

/** 一条包级配置记录：某个应用使用哪种连接逻辑。 */
data class PackageRecord(
    val packageName: String,
    val label: String,
    val mode: PackageConnectionMode
)

/** VPN 全局设置。 */
data class VpnSettings(
    val conflictPolicy: VpnConflictPolicy = VpnConflictPolicy.FIRST_COMES_FIRST,
    val defaultPackageMode: PackageConnectionMode = PackageConnectionMode.USE_VNET_PROVIDE_OTHERS,
    val packageRecords: List<PackageRecord> = emptyList()
)

/** VPN 全局设置的本地持久化（SharedPreferences，记录用 JSON 序列化）。 */
object VpnSettingsStore {
    private const val PREFS = "antier_vpn_settings"
    private const val KEY_CONFLICT = "conflict_policy"
    private const val KEY_DEFAULT_MODE = "default_package_mode"
    private const val KEY_RECORDS = "package_records"

    fun load(context: Context): VpnSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return VpnSettings(
            conflictPolicy = parseEnum(
                prefs.getString(KEY_CONFLICT, null),
                VpnConflictPolicy.FIRST_COMES_FIRST
            ),
            defaultPackageMode = parseEnum(
                prefs.getString(KEY_DEFAULT_MODE, null),
                PackageConnectionMode.USE_VNET_PROVIDE_OTHERS
            ),
            packageRecords = parseRecords(prefs.getString(KEY_RECORDS, null))
        )
    }

    fun save(context: Context, settings: VpnSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONFLICT, settings.conflictPolicy.name)
            .putString(KEY_DEFAULT_MODE, settings.defaultPackageMode.name)
            .putString(KEY_RECORDS, recordsToJson(settings.packageRecords))
            .apply()
    }

    private fun recordsToJson(records: List<PackageRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("package", record.packageName)
                    .put("label", record.label)
                    .put("mode", record.mode.name)
            )
        }
        return array.toString()
    }

    private fun parseRecords(raw: String?): List<PackageRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val pkg = obj.optString("package").takeIf { it.isNotBlank() } ?: continue
                    val mode = runCatching {
                        PackageConnectionMode.valueOf(obj.optString("mode"))
                    }.getOrNull() ?: PackageConnectionMode.USE_VNET_PROVIDE_OTHERS
                    add(
                        PackageRecord(
                            packageName = pkg,
                            label = obj.optString("label", pkg),
                            mode = mode
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?, default: T): T {
        return raw?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
    }
}
