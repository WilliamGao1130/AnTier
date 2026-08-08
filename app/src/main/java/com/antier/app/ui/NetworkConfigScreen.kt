package com.antier.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class ConfigTab { WIZARD, TOML }

/**
 * 网络实例配置页：
 * - 向导页签：开关 + 输入框生成配置；
 * - TOML 页签：保留直接编辑，可双向同步。
 */
@Composable
fun NetworkConfigScreen(
    initialToml: String,
    initialTab: ConfigTab = ConfigTab.WIZARD,
    onBack: () -> Unit,
    onApply: (String) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var form by remember(initialToml) { mutableStateOf(NetworkConfigForm.fromToml(initialToml)) }
    var tomlText by remember(initialToml) { mutableStateOf(initialToml) }

    var listenersText by remember(initialToml) {
        mutableStateOf(form.listeners.joinToString("\n"))
    }
    var peersText by remember(initialToml) { mutableStateOf(form.peers.joinToString("\n")) }
    var proxyText by remember(initialToml) {
        mutableStateOf(form.proxyCidrs.joinToString("\n"))
    }
    var routesText by remember(initialToml) {
        mutableStateOf(form.manualRoutes.joinToString("\n"))
    }

    fun syncListFields() {
        form = form.copy(
            listeners = NetworkConfigForm.parseEntries(listenersText),
            peers = NetworkConfigForm.parseEntries(peersText),
            proxyCidrs = NetworkConfigForm.parseEntries(proxyText),
            manualRoutes = NetworkConfigForm.parseEntries(routesText)
        )
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
            Text("网络配置", style = MaterialTheme.typography.headlineSmall)
        }

        TabRow(selectedTabIndex = tab.ordinal) {
            Tab(
                selected = tab == ConfigTab.WIZARD,
                onClick = { tab = ConfigTab.WIZARD }
            ) { Text("配置向导", Modifier.padding(12.dp)) }
            Tab(
                selected = tab == ConfigTab.TOML,
                onClick = { tab = ConfigTab.TOML }
            ) { Text("高级 TOML", Modifier.padding(12.dp)) }
        }

        if (tab == ConfigTab.WIZARD) {
            Section("基础") {
                OutlinedTextField(
                    value = form.instanceName,
                    onValueChange = { form = form.copy(instanceName = it) },
                    label = { Text("实例名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.hostname,
                    onValueChange = { form = form.copy(hostname = it) },
                    label = { Text("主机名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.ipv4,
                    onValueChange = { form = form.copy(ipv4 = it) },
                    label = { Text("虚拟 IPv4（留空 = DHCP）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.networkName,
                    onValueChange = { form = form.copy(networkName = it) },
                    label = { Text("网络名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.networkSecret,
                    onValueChange = { form = form.copy(networkSecret = it) },
                    label = { Text("网络密钥") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Section("连接") {
                MultiLineField(
                    value = listenersText,
                    onValueChange = { listenersText = it },
                    label = "监听地址（每行一个，如 tcp://0.0.0.0:11010）"
                )
                MultiLineField(
                    value = peersText,
                    onValueChange = { peersText = it },
                    label = "对端地址（每行一个，如 tcp://public.kkrainbow.top:11010）"
                )
                MultiLineField(
                    value = proxyText,
                    onValueChange = { proxyText = it },
                    label = "代理子网（每行一个 CIDR，如 10.147.223.0/24）"
                )
                MultiLineField(
                    value = routesText,
                    onValueChange = { routesText = it },
                    label = "手动路由（每行一个 CIDR）"
                )
            }

            Section("数据面") {
                SwitchRow(
                    title = "no-tun 模式（不开 VPN）",
                    description = "启用后不创建 TUN/VPN，主动访问走下面的 SOCKS5 服务",
                    checked = form.noTun,
                    onCheckedChange = { form = form.copy(noTun = it) }
                )
                SwitchRow(
                    title = "use_smoltcp（用户态协议栈）",
                    description = "子网代理等场景需要；Android 上内核本来就会用 smoltcp",
                    checked = form.useSmoltcp,
                    onCheckedChange = { form = form.copy(useSmoltcp = it) }
                )
                OutlinedTextField(
                    value = form.socks5Port,
                    onValueChange = { form = form.copy(socks5Port = it) },
                    label = { Text("SOCKS5 端口（no-tun 时启用）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (form.noTun && form.socks5Port.isBlank()) {
                    Text(
                        "no-tun 已开启但未配置 SOCKS5 端口：实例只能被访问，无法主动连接对端",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Section("功能开关") {
                SwitchRow(
                    title = "启用加密",
                    description = "对组网流量启用加密",
                    checked = form.enableEncryption,
                    onCheckedChange = { form = form.copy(enableEncryption = it) }
                )
                SwitchRow(
                    title = "启用 IPv6",
                    checked = form.enableIpv6,
                    onCheckedChange = { form = form.copy(enableIpv6 = it) }
                )
                SwitchRow(
                    title = "出口节点（exit node）",
                    description = "作为组网出口，代理其他节点访问本机网络",
                    checked = form.enableExitNode,
                    onCheckedChange = { form = form.copy(enableExitNode = it) }
                )
                SwitchRow(
                    title = "延迟优先（latency first）",
                    checked = form.latencyFirst,
                    onCheckedChange = { form = form.copy(latencyFirst = it) }
                )
                OutlinedTextField(
                    value = form.mtu,
                    onValueChange = { form = form.copy(mtu = it) },
                    label = { Text("MTU") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.logLevel,
                    onValueChange = { form = form.copy(logLevel = it) },
                    label = { Text("日志级别（debug/info/warn/error）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Section("TOML（高级）") {
                OutlinedTextField(
                    value = tomlText,
                    onValueChange = { tomlText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    minLines = 14
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { tomlText = form.toToml() }) {
                        Text("从向导生成")
                    }
                    OutlinedButton(
                        onClick = {
                            form = NetworkConfigForm.fromToml(tomlText)
                            listenersText = form.listeners.joinToString("\n")
                            peersText = form.peers.joinToString("\n")
                            proxyText = form.proxyCidrs.joinToString("\n")
                            routesText = form.manualRoutes.joinToString("\n")
                        }
                    ) {
                        Text("用此文本更新向导")
                    }
                }
                Text(
                    "手改文本后请点“用此文本更新向导”再切回向导，否则两侧可能不一致。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("取消") }
            Button(
                onClick = {
                    syncListFields()
                    onApply(if (tab == ConfigTab.TOML) tomlText else form.toToml())
                }
            ) {
                Text("应用配置")
            }
        }
        Text(
            "应用后回到主页，点“启动内核”使用此配置。",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MultiLineField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp),
        minLines = 2
    )
}
