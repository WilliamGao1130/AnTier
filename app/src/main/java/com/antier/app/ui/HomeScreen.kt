package com.antier.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.antier.app.ui.MainViewModel

private val DEFAULT_CONFIG = """
    inst_name = "antier_android"
    network = "antier_demo_net"
    network_secret = "antier_demo_secret"
    hostname = "antier-android"
    listeners = ["tcp://0.0.0.0:11010", "udp://0.0.0.0:11010"]
    enable_encryption = true
    mtu = 1380
    log_level = "info"

    # no-tun 模式（不开 VPN，走 SOCKS5）：取消下面三行注释即可
    # socks5_proxy = "socks5://127.0.0.1:12333"
    # [flags]
    # no_tun = true
    # use_smoltcp = true
""".trimIndent()

@Composable
fun HomeScreen(viewModel: MainViewModel = viewModel()) {
    var toml by rememberSaveable { mutableStateOf(DEFAULT_CONFIG) }
    var cfgUrl by rememberSaveable { mutableStateOf("") }
    var machineId by rememberSaveable { mutableStateOf("android-device") }

    val service by viewModel.service.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val instances by viewModel.instances.collectAsState()
    val activeTunInstance by viewModel.activeTunInstance.collectAsState()
    val lastEvent by viewModel.lastEvent.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val cfgConnected by viewModel.configServerConnected.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AnTier · 原生控件控制 EasyTier 内核", style = MaterialTheme.typography.headlineSmall)
        Text(
            "AIDL 服务: ${if (service != null) "已连接" else "未连接"}",
            style = MaterialTheme.typography.bodyMedium
        )

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TOML 配置", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = toml,
                    onValueChange = { toml = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    minLines = 10
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.startNetwork(toml) }) {
                        Text("启动内核")
                    }
                    OutlinedButton(onClick = { viewModel.stopAll() }) {
                        Text("停止全部")
                    }
                    OutlinedButton(onClick = { viewModel.refreshStatus() }) {
                        Text("刷新状态")
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("实例列表", style = MaterialTheme.typography.titleMedium)
                Text(
                    "图例: ● 本应用启动　○ 外部 AIDL 启动",
                    style = MaterialTheme.typography.bodySmall
                )
                if (activeTunInstance != null) {
                    Text(
                        "VPN (TUN) 已绑定到实例: $activeTunInstance",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (instances.isEmpty()) {
                    Text("暂无运行中的实例", style = MaterialTheme.typography.bodyMedium)
                } else {
                    instances.forEach { inst ->
                        Card {
                            Column(
                                Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        inst.name,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        buildString {
                                            append(if (inst.origin == InstanceOrigin.LOCAL) "●" else "○")
                                            append(' ')
                                            append(
                                                when {
                                                    !inst.modeKnown -> "外部实例，模式未知"
                                                    inst.noTun -> "no-tun (SOCKS5)"
                                                    else -> "TUN"
                                                }
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(
                                    "虚拟 IPv4: ${inst.ipv4 ?: "未分配"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (inst.noTun) {
                                    Text(
                                        if (inst.socks5Endpoint != null) {
                                            "SOCKS5: ${inst.socks5Endpoint}"
                                        } else {
                                            "未配置 socks5_proxy，实例只能被访问"
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                } else {
                                    Text(
                                        "代理 CIDR: ${inst.proxyCidrs}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (!inst.errorMsg.isNullOrEmpty()) {
                                    Text(
                                        "error: ${inst.errorMsg}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                OutlinedButton(
                                    onClick = { viewModel.stopInstance(inst.name) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("停止 ${inst.name}")
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("最近事件: $lastEvent", style = MaterialTheme.typography.bodySmall)
                lastError?.let {
                    Text("错误: $it", color = MaterialTheme.colorScheme.error)
                }
                HorizontalDivider()
                Text("原始状态:", style = MaterialTheme.typography.titleSmall)
                Text(statusText, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("配置服务器（远程托管）", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = cfgUrl,
                    onValueChange = { cfgUrl = it },
                    label = { Text("服务器 URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = machineId,
                    onValueChange = { machineId = it },
                    label = { Text("machine id") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = cfgUrl.isNotBlank() && !cfgConnected,
                        onClick = { viewModel.startConfigServer(cfgUrl, machineId) }
                    ) {
                        Text("连接")
                    }
                    OutlinedButton(
                        enabled = cfgConnected,
                        onClick = { viewModel.stopConfigServer() }
                    ) {
                        Text("断开")
                    }
                }
                Text("连接状态: ${if (cfgConnected) "已连接" else "未连接"}")
            }
        }
    }
}
