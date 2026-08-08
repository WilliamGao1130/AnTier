package com.antier.app.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.antier.core.VpnAppMode
import com.antier.core.VpnRoutingMode
import com.antier.core.VpnSettings
import com.antier.core.VpnSettingsStore

private data class AppEntry(val packageName: String, val label: String)

/** VPN 全局设置页：应用可见性（白名单/黑名单）与路由模式。 */
@Composable
fun VpnSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(VpnSettingsStore.load(context)) }
    val apps = remember { loadAllApps(context) }

    fun update(transform: (VpnSettings) -> VpnSettings) {
        settings = transform(settings)
        VpnSettingsStore.save(context, settings)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
            Text("VPN 全局设置", style = MaterialTheme.typography.headlineSmall)
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("路由模式", style = MaterialTheme.typography.titleMedium)
                RoutingOption(
                    title = "仅虚拟网络",
                    description = "只有组网虚拟 IP 与代理子网走 VPN，其他流量走原网络",
                    selected = settings.routingMode == VpnRoutingMode.VIRTUAL_ONLY,
                    onClick = { update { it.copy(routingMode = VpnRoutingMode.VIRTUAL_ONLY) } }
                )
                RoutingOption(
                    title = "虚拟网络 + 互联网",
                    description = "全部流量进入 VPN（互联网需要组网内有出口节点/代理子网）",
                    selected = settings.routingMode == VpnRoutingMode.FULL_TUNNEL,
                    onClick = { update { it.copy(routingMode = VpnRoutingMode.FULL_TUNNEL) } }
                )
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("应用可见性", style = MaterialTheme.typography.titleMedium)
                RoutingOption(
                    title = "允许所有应用",
                    description = "所有应用都可以通过 EasyTier VPN 访问组网",
                    selected = settings.appMode == VpnAppMode.ALL,
                    onClick = { update { it.copy(appMode = VpnAppMode.ALL) } }
                )
                RoutingOption(
                    title = "白名单：仅以下应用可用",
                    description = if (Build.VERSION.SDK_INT >= 34) {
                        "只有勾选的应用可以访问虚拟网络，其余应用不受影响"
                    } else {
                        "需要 Android 14+（当前设备不支持，选择后按“所有应用”处理）"
                    },
                    selected = settings.appMode == VpnAppMode.ALLOW_LIST,
                    onClick = { update { it.copy(appMode = VpnAppMode.ALLOW_LIST) } }
                )
                RoutingOption(
                    title = "黑名单：以下应用不可用",
                    description = "勾选的应用绕过 VPN，走原网络；其余应用走 VPN",
                    selected = settings.appMode == VpnAppMode.DENY_LIST,
                    onClick = { update { it.copy(appMode = VpnAppMode.DENY_LIST) } }
                )
            }
        }

        if (settings.appMode != VpnAppMode.ALL) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (settings.appMode == VpnAppMode.ALLOW_LIST) {
                            "白名单应用（共 ${apps.size} 个）"
                        } else {
                            "黑名单应用（共 ${apps.size} 个）"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    HorizontalDivider()
                    if (apps.isEmpty()) {
                        Text("未发现可列表的应用", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        apps.forEach { app ->
                            val selected = if (settings.appMode == VpnAppMode.ALLOW_LIST) {
                                app.packageName in settings.allowedPackages
                            } else {
                                app.packageName in settings.deniedPackages
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        update {
                                            if (settings.appMode == VpnAppMode.ALLOW_LIST) {
                                                it.copy(
                                                    allowedPackages = toggle(
                                                        it.allowedPackages,
                                                        app.packageName
                                                    )
                                                )
                                            } else {
                                                it.copy(
                                                    deniedPackages = toggle(
                                                        it.deniedPackages,
                                                        app.packageName
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = null
                                )
                                Column {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Text(
            "设置自动保存，下次启动 VPN 时生效；no-tun（SOCKS5）模式不适用。",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun RoutingOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun toggle(set: Set<String>, value: String): Set<String> {
    return if (value in set) set - value else set + value
}

/** 枚举全部已安装应用（排除自身），供白名单/黑名单选择。 */
private fun loadAllApps(context: Context): List<AppEntry> {
    return runCatching {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map { app ->
                val label = runCatching { app.loadLabel(pm).toString() }
                    .getOrDefault(app.packageName)
                AppEntry(app.packageName, label.ifBlank { app.packageName })
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
}
