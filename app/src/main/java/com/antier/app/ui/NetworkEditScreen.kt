package com.antier.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antier.app.ui.model.NetworkConfig
import com.antier.app.ui.model.Peer
import com.antier.app.ui.model.PortForward
import com.antier.app.ui.model.ProxyNetwork
import com.antier.app.ui.model.TomlDiff
import com.antier.app.ui.model.diffToml
import com.antier.app.ui.store.NetworkStore

/**
 * 网络编辑页。
 *
 * - 标题栏：返回按钮 + 网络名称（随编辑实时更新）。
 * - 顶部提供连接/断开当前网络按钮。
 * - 全部选项垂直排列，无水平并排；点选项名称切换其描述。
 * - 布尔选项为勾选框；文本选项为输入框并带示例提示。
 * - 底部“编辑 TOML”打开全屏编辑器，保存后计算行级差异并显示在按钮下方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkEditScreen(
    networkId: String,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var config by remember(networkId) {
        mutableStateOf(
            NetworkStore.get(context, networkId)?.config ?: NetworkConfig.default()
        )
    }
    var expandedDesc by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tomlDiff by remember(networkId) { mutableStateOf<TomlDiff?>(null) }
    var editorVisible by remember { mutableStateOf(false) }
    var editorText by remember { mutableStateOf("") }
    var editorBefore by remember { mutableStateOf("") }
    var editorError by remember { mutableStateOf<String?>(null) }

    val networks by viewModel.networks.collectAsState()
    val running = networks.any { it.id == networkId && it.running }

    fun toggleDescription(key: String) {
        expandedDesc = if (key in expandedDesc) expandedDesc - key else expandedDesc + key
    }

    fun update(newConfig: NetworkConfig) {
        config = newConfig
        viewModel.saveNetwork(networkId, newConfig.toToml())
        tomlDiff = null
    }

    fun openEditor() {
        editorBefore = config.toToml()
        editorText = editorBefore
        editorError = null
        editorVisible = true
    }

    fun saveEditor() {
        val parsed = runCatching { NetworkConfig.fromToml(editorText) }.getOrNull()
        if (parsed == null) {
            editorError = "TOML 解析失败，请检查语法后重试"
            return
        }
        tomlDiff = diffToml(editorBefore, editorText)
        config = parsed
        viewModel.saveNetwork(networkId, editorText)
        editorVisible = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(config.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Button(
                    onClick = {
                        if (running) viewModel.stopNetwork(networkId)
                        else viewModel.startNetwork(networkId)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (running) "断开该网络" else "连接该网络",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            item {
                SectionCard("基础设置") {
                    TextOption(
                        title = "实例名称",
                        description = "同一台机器上标识此 VPN 节点的实例名，也是运行实例的键。",
                        expanded = "instanceName" in expandedDesc,
                        onToggle = { toggleDescription("instanceName") },
                        value = config.instanceName,
                        onValueChange = { update(config.copy(instanceName = it)) }
                    )
                    TextOption(
                        title = "主机名",
                        description = "用于标识此设备的主机名，留空使用系统主机名。",
                        expanded = "hostname" in expandedDesc,
                        onToggle = { toggleDescription("hostname") },
                        value = config.hostname,
                        onValueChange = { update(config.copy(hostname = it)) },
                        hint = "留空 = 系统主机名"
                    )
                    TextOption(
                        title = "实例 ID",
                        description = "实例唯一标识，同一实例只能运行一个。",
                        expanded = "instanceId" in expandedDesc,
                        onToggle = { toggleDescription("instanceId") },
                        value = config.instanceId,
                        onValueChange = { update(config.copy(instanceId = it)) }
                    )
                    TextOption(
                        title = "网络命名空间 (netns)",
                        description = "Linux 网络命名空间，Android 上通常留空。",
                        expanded = "netns" in expandedDesc,
                        onToggle = { toggleDescription("netns") },
                        value = config.netns,
                        onValueChange = { update(config.copy(netns = it)) }
                    )
                    BoolOption(
                        title = "DHCP 自动分配 IP",
                        description = "由 EasyTier 自动确定并设置 IP，默认从 10.0.0.1 开始；冲突时自动更换。",
                        expanded = "dhcp" in expandedDesc,
                        onToggle = { toggleDescription("dhcp") },
                        checked = config.dhcp,
                        onCheckedChange = { update(config.copy(dhcp = it)) }
                    )
                    TextOption(
                        title = "虚拟 IPv4",
                        description = "本节点在虚拟网络中的 IPv4 地址，可带 /前缀。留空且未开 DHCP 时仅转发数据包。",
                        expanded = "ipv4" in expandedDesc,
                        onToggle = { toggleDescription("ipv4") },
                        value = config.ipv4,
                        onValueChange = { update(config.copy(ipv4 = it)) },
                        hint = "e.g. 10.126.126.1/24"
                    )
                    TextOption(
                        title = "虚拟 IPv6",
                        description = "本节点 IPv6 地址，可与 IPv4 一起使用实现双栈。",
                        expanded = "ipv6" in expandedDesc,
                        onToggle = { toggleDescription("ipv6") },
                        value = config.ipv6,
                        onValueChange = { update(config.copy(ipv6 = it)) },
                        hint = "e.g. fd00::1"
                    )
                    BoolOption(
                        title = "共享公网 IPv6 子网",
                        description = "把本机公网 IPv6 子网共享给其他节点（仅 Linux 支持）。",
                        expanded = "ipv6PublicAddrProvider" in expandedDesc,
                        onToggle = { toggleDescription("ipv6PublicAddrProvider") },
                        checked = config.ipv6PublicAddrProvider,
                        onCheckedChange = {
                            update(config.copy(ipv6PublicAddrProvider = it))
                        }
                    )
                    BoolOption(
                        title = "自动获取公网 IPv6",
                        description = "自动从共享了 IPv6 子网的对等节点获取公网 IPv6 地址。",
                        expanded = "ipv6PublicAddrAuto" in expandedDesc,
                        onToggle = { toggleDescription("ipv6PublicAddrAuto") },
                        checked = config.ipv6PublicAddrAuto,
                        onCheckedChange = { update(config.copy(ipv6PublicAddrAuto = it)) }
                    )
                    TextOption(
                        title = "公网 IPv6 前缀",
                        description = "手动指定要共享的公网 IPv6 子网，不自动从系统路由检测。",
                        expanded = "ipv6PublicAddrPrefix" in expandedDesc,
                        onToggle = { toggleDescription("ipv6PublicAddrPrefix") },
                        value = config.ipv6PublicAddrPrefix,
                        onValueChange = { update(config.copy(ipv6PublicAddrPrefix = it)) },
                        hint = "e.g. 2001:db8:100::/64"
                    )
                    TextOption(
                        title = "网络名称",
                        description = "网络唯一标识，与网络密码一起决定加入哪个网络。",
                        expanded = "networkName" in expandedDesc,
                        onToggle = { toggleDescription("networkName") },
                        value = config.networkName,
                        onValueChange = { update(config.copy(networkName = it)) }
                    )
                    TextOption(
                        title = "网络密码",
                        description = "加入网络的凭证；配合安全模式可省略（凭据模式）。",
                        expanded = "networkSecret" in expandedDesc,
                        onToggle = { toggleDescription("networkSecret") },
                        value = config.networkSecret,
                        onValueChange = { update(config.copy(networkSecret = it)) }
                    )
                }
            }

            item {
                SectionCard("连接") {
                    ListOption(
                        title = "初始节点",
                        description = "EasyTier 不分服务端/客户端。填写=加入已有网络；留空=独立启动等别人来连。",
                        expanded = "peers" in expandedDesc,
                        onToggle = { toggleDescription("peers") },
                        value = config.peers.joinToString("\n") { it.uri },
                        onValueChange = { text ->
                            update(
                                config.copy(
                                    peers = parseLines(text).map { Peer(uri = it) }
                                )
                            )
                        },
                        hint = "每行一个，如 tcp://public.easytier.top:11010"
                    )
                    ListOption(
                        title = "监听地址",
                        description = "本节点对外监听的地址，供其他节点连接。",
                        expanded = "listeners" in expandedDesc,
                        onToggle = { toggleDescription("listeners") },
                        value = config.listeners.joinToString("\n"),
                        onValueChange = { update(config.copy(listeners = parseLines(it))) },
                        hint = "每行一个，如 tcp://0.0.0.0:11010"
                    )
                    ListOption(
                        title = "监听映射",
                        description = "手动指定监听器的公网地址，其他节点用该地址连接本节点。",
                        expanded = "mappedListeners" in expandedDesc,
                        onToggle = { toggleDescription("mappedListeners") },
                        value = config.mappedListeners.joinToString("\n"),
                        onValueChange = {
                            update(config.copy(mappedListeners = parseLines(it)))
                        },
                        hint = "每行一个，如 tcp://123.123.123.123:11223"
                    )
                    ListOption(
                        title = "STUN 服务器 (IPv4)",
                        description = "覆盖内置默认 STUN 服务器；留空使用默认，空数组可禁用（此处留空=默认）。",
                        expanded = "stunServers" in expandedDesc,
                        onToggle = { toggleDescription("stunServers") },
                        value = config.stunServers.joinToString("\n"),
                        onValueChange = { update(config.copy(stunServers = parseLines(it))) },
                        hint = "每行一个"
                    )
                    ListOption(
                        title = "STUN 服务器 (IPv6)",
                        description = "覆盖内置默认 IPv6 STUN 服务器。",
                        expanded = "stunServersV6" in expandedDesc,
                        onToggle = { toggleDescription("stunServersV6") },
                        value = config.stunServersV6.joinToString("\n"),
                        onValueChange = { update(config.copy(stunServersV6 = parseLines(it))) },
                        hint = "每行一个"
                    )
                }
            }

            item {
                SectionCard("子网代理与路由") {
                    Text("子网代理", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "把本地子网导出到虚拟网络；可重映射到另一 CIDR，并限制协议。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    config.proxyNetworks.forEachIndexed { index, proxy ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = proxy.cidr,
                                    onValueChange = { value ->
                                        update(
                                            config.copy(
                                                proxyNetworks = config.proxyNetworks.toMutableList()
                                                    .apply {
                                                        this[index] = proxy.copy(cidr = value)
                                                    }
                                            )
                                        )
                                    },
                                    label = { Text("CIDR") },
                                    placeholder = { Text("e.g. 192.168.1.0/24") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = proxy.mappedCidr,
                                    onValueChange = { value ->
                                        update(
                                            config.copy(
                                                proxyNetworks = config.proxyNetworks.toMutableList()
                                                    .apply {
                                                        this[index] = proxy.copy(mappedCidr = value)
                                                    }
                                            )
                                        )
                                    },
                                    label = { Text("映射 CIDR（可选）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = proxy.allow.joinToString(","),
                                    onValueChange = { value ->
                                        update(
                                            config.copy(
                                                proxyNetworks = config.proxyNetworks.toMutableList()
                                                    .apply {
                                                        this[index] = proxy.copy(
                                                            allow = parseLines(value)
                                                        )
                                                    }
                                            )
                                        )
                                    },
                                    label = { Text("允许协议") },
                                    placeholder = { Text("tcp,udp,icmp") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    IconButton(
                                        onClick = {
                                            update(
                                                config.copy(
                                                    proxyNetworks = config.proxyNetworks
                                                        .toMutableList()
                                                        .apply { removeAt(index) }
                                                )
                                            )
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除")
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            update(
                                config.copy(
                                    proxyNetworks = config.proxyNetworks + ProxyNetwork()
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("添加子网代理")
                    }

                    ListOption(
                        title = "手动路由",
                        description = "手动分配路由 CIDR，将禁用子网代理和对等节点传播的 WireGuard 路由。",
                        expanded = "routes" in expandedDesc,
                        onToggle = { toggleDescription("routes") },
                        value = config.routes.joinToString("\n"),
                        onValueChange = { update(config.copy(routes = parseLines(it))) },
                        hint = "每行一个，如 192.168.0.0/16"
                    )
                    ListOption(
                        title = "出口节点列表",
                        description = "转发所有流量的出口节点（虚拟 IPv4），优先级由列表顺序决定。",
                        expanded = "exitNodes" in expandedDesc,
                        onToggle = { toggleDescription("exitNodes") },
                        value = config.exitNodes.joinToString("\n"),
                        onValueChange = { update(config.copy(exitNodes = parseLines(it))) },
                        hint = "每行一个，如 192.168.8.8"
                    )
                }
            }

            item {
                SectionCard("服务") {
                    TextOption(
                        title = "SOCKS5 服务",
                        description = "启用 SOCKS5 服务器，允许客户端访问虚拟网络。no-tun 模式下必须配置才能主动出网。",
                        expanded = "socks5" in expandedDesc,
                        onToggle = { toggleDescription("socks5") },
                        value = config.socks5Proxy,
                        onValueChange = { update(config.copy(socks5Proxy = it)) },
                        hint = "e.g. socks5://0.0.0.0:1080"
                    )
                    BoolOption(
                        title = "启用 VPN 门户",
                        description = "开启 WireGuard 风格的 VPN 门户，允许外部 VPN 客户端接入。",
                        expanded = "vpnPortal" in expandedDesc,
                        onToggle = { toggleDescription("vpnPortal") },
                        checked = config.vpnPortal != null,
                        onCheckedChange = { enabled ->
                            update(
                                config.copy(
                                    vpnPortal = if (enabled) {
                                        config.vpnPortal ?: com.antier.app.ui.model.VpnPortal()
                                    } else {
                                        null
                                    }
                                )
                            )
                        }
                    )
                    config.vpnPortal?.let { portal ->
                        TextOption(
                            title = "门户客户端子网",
                            description = "分配给门户客户端的子网。",
                            expanded = "vpnPortalCidr" in expandedDesc,
                            onToggle = { toggleDescription("vpnPortalCidr") },
                            value = portal.clientCidr,
                            onValueChange = { value ->
                                update(config.copy(vpnPortal = portal.copy(clientCidr = value)))
                            },
                            hint = "e.g. 10.14.14.0/24"
                        )
                        TextOption(
                            title = "门户监听地址",
                            description = "WireGuard 门户监听地址（IP:端口）。",
                            expanded = "vpnPortalListen" in expandedDesc,
                            onToggle = { toggleDescription("vpnPortalListen") },
                            value = portal.wireguardListen,
                            onValueChange = { value ->
                                update(config.copy(vpnPortal = portal.copy(wireguardListen = value)))
                            },
                            hint = "e.g. 0.0.0.0:11010"
                        )
                    }

                    Text("端口转发", style = MaterialTheme.typography.titleSmall)
                    config.portForwards.forEachIndexed { index, pf ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = pf.proto,
                                    onValueChange = { value ->
                                        update(
                                            config.copy(
                                                portForwards = config.portForwards.toMutableList()
                                                    .apply { this[index] = pf.copy(proto = value) }
                                            )
                                        )
                                    },
                                    label = { Text("协议") },
                                    placeholder = { Text("tcp / udp") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = pf.bindAddr,
                                    onValueChange = { value ->
                                        update(
                                            config.copy(
                                                portForwards = config.portForwards.toMutableList()
                                                    .apply { this[index] = pf.copy(bindAddr = value) }
                                            )
                                        )
                                    },
                                    label = { Text("本地绑定地址") },
                                    placeholder = { Text("0.0.0.0:12345") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = pf.dstAddr,
                                    onValueChange = { value ->
                                        update(
                                            config.copy(
                                                portForwards = config.portForwards.toMutableList()
                                                    .apply { this[index] = pf.copy(dstAddr = value) }
                                            )
                                        )
                                    },
                                    label = { Text("目标地址") },
                                    placeholder = { Text("10.126.126.1:23456") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    IconButton(
                                        onClick = {
                                            update(
                                                config.copy(
                                                    portForwards = config.portForwards
                                                        .toMutableList()
                                                        .apply { removeAt(index) }
                                                )
                                            )
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除")
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            update(
                                config.copy(
                                    portForwards = config.portForwards + PortForward()
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("添加端口转发")
                    }
                }
            }

            item {
                SectionCard("功能开关 (flags)") {
                    @Composable
                    fun boolFlag(
                        key: String,
                        title: String,
                        description: String,
                        checked: Boolean,
                        onChange: (Boolean) -> Unit
                    ) {
                        BoolOption(
                            title = title,
                            description = description,
                            expanded = key in expandedDesc,
                            onToggle = { toggleDescription(key) },
                            checked = checked,
                            onCheckedChange = onChange
                        )
                    }

                    @Composable
                    fun textFlag(
                        key: String,
                        title: String,
                        description: String,
                        value: String,
                        onChange: (String) -> Unit,
                        hint: String = ""
                    ) {
                        TextOption(
                            title = title,
                            description = description,
                            expanded = key in expandedDesc,
                            onToggle = { toggleDescription(key) },
                            value = value,
                            onValueChange = onChange,
                            hint = hint
                        )
                    }

                    @Composable
                    fun numberFlag(
                        key: String,
                        title: String,
                        description: String,
                        value: String,
                        onChange: (String) -> Unit,
                        hint: String = ""
                    ) {
                        TextOption(
                            title = title,
                            description = description,
                            expanded = key in expandedDesc,
                            onToggle = { toggleDescription(key) },
                            value = value,
                            onValueChange = onChange,
                            hint = hint,
                            number = true
                        )
                    }

                    boolFlag("latency_first", "延迟优先",
                        "忽略中转跳数，选择总延迟最低的路径。", config.flags.latencyFirst) {
                        update(config.copy(flags = config.flags.copy(latencyFirst = it)))
                    }
                    boolFlag("use_smoltcp", "使用用户态协议栈",
                        "为子网代理和 KCP 代理启用 smoltcp 堆栈。", config.flags.useSmoltcp) {
                        update(config.copy(flags = config.flags.copy(useSmoltcp = it)))
                    }
                    boolFlag("enable_ipv6", "启用 IPv6",
                        "本节点 IPv6 通信开关。", config.flags.enableIpv6) {
                        update(config.copy(flags = config.flags.copy(enableIpv6 = it)))
                    }
                    boolFlag("enable_encryption", "启用加密",
                        "对等通信加密，须与其他节点一致。", config.flags.enableEncryption) {
                        update(config.copy(flags = config.flags.copy(enableEncryption = it)))
                    }
                    boolFlag("enable_exit_node", "启用出口节点",
                        "允许本节点成为出口节点。", config.flags.enableExitNode) {
                        update(config.copy(flags = config.flags.copy(enableExitNode = it)))
                    }
                    boolFlag("no_tun", "无 TUN 模式",
                        "不创建 TUN 设备；本节点只能被子网代理访问，主动出网需 SOCKS5。",
                        config.flags.noTun) {
                        update(config.copy(flags = config.flags.copy(noTun = it)))
                    }
                    boolFlag("bind_device", "仅使用物理网卡",
                        "连接器套接字绑定物理设备，避免子网代理网段冲突导致的路由问题。",
                        config.flags.bindDevice) {
                        update(config.copy(flags = config.flags.copy(bindDevice = it)))
                    }
                    boolFlag("multi_thread", "启用多线程",
                        "使用多线程运行时。", config.flags.multiThread) {
                        update(config.copy(flags = config.flags.copy(multiThread = it)))
                    }
                    boolFlag("disable_p2p", "禁用 P2P",
                        "关闭普通自动 P2P；need-p2p 节点仍可建立 P2P。", config.flags.disableP2p) {
                        update(config.copy(flags = config.flags.copy(disableP2p = it)))
                    }
                    boolFlag("p2p_only", "仅 P2P",
                        "只与已建立 P2P 连接的节点通信。", config.flags.p2pOnly) {
                        update(config.copy(flags = config.flags.copy(p2pOnly = it)))
                    }
                    boolFlag("lazy_p2p", "延迟 P2P",
                        "有实际流量时才尝试建立 P2P。", config.flags.lazyP2p) {
                        update(config.copy(flags = config.flags.copy(lazyP2p = it)))
                    }
                    boolFlag("need_p2p", "需要 P2P",
                        "声明其他节点应主动与本节点建立 P2P。", config.flags.needP2p) {
                        update(config.copy(flags = config.flags.copy(needP2p = it)))
                    }
                    boolFlag("relay_all_peer_rpc", "转发 RPC 包",
                        "转发白名单外网络节点的 RPC，帮助其建立 P2P。", config.flags.relayAllPeerRpc) {
                        update(config.copy(flags = config.flags.copy(relayAllPeerRpc = it)))
                    }
                    boolFlag("disable_tcp_hole_punching", "禁用 TCP 打洞", "关闭 TCP 打洞。",
                        config.flags.disableTcpHolePunching) {
                        update(config.copy(flags = config.flags.copy(disableTcpHolePunching = it)))
                    }
                    boolFlag("disable_udp_hole_punching", "禁用 UDP 打洞", "关闭 UDP 打洞。",
                        config.flags.disableUdpHolePunching) {
                        update(config.copy(flags = config.flags.copy(disableUdpHolePunching = it)))
                    }
                    boolFlag("disable_sym_hole_punching", "禁用对称 NAT 打洞",
                        "关闭基于生日攻击的对称 NAT (NAT4) UDP 打洞。",
                        config.flags.disableSymHolePunching) {
                        update(config.copy(flags = config.flags.copy(disableSymHolePunching = it)))
                    }
                    boolFlag("disable_upnp", "禁用 UPnP",
                        "关闭监听器的运行时 UPnP/NAT-PMP 自动端口映射。", config.flags.disableUpnp) {
                        update(config.copy(flags = config.flags.copy(disableUpnp = it)))
                    }
                    boolFlag("proxy_forward_by_system", "系统转发",
                        "子网代理数据包交系统内核转发，禁用内置 NAT。", config.flags.proxyForwardBySystem) {
                        update(config.copy(flags = config.flags.copy(proxyForwardBySystem = it)))
                    }
                    boolFlag("accept_dns", "启用魔法 DNS",
                        "可用 <主机名>.et.net 访问其他节点；会修改系统 DNS 设置。",
                        config.flags.acceptDns) {
                        update(config.copy(flags = config.flags.copy(acceptDns = it)))
                    }
                    boolFlag("private_mode", "启用私有模式",
                        "只允许同网络名/密钥或受信凭据节点接入。", config.flags.privateMode) {
                        update(config.copy(flags = config.flags.copy(privateMode = it)))
                    }
                    boolFlag("enable_kcp_proxy", "启用 KCP 代理",
                        "把 TCP 流量转为 KCP，降低延迟提升速度。", config.flags.enableKcpProxy) {
                        update(config.copy(flags = config.flags.copy(enableKcpProxy = it)))
                    }
                    boolFlag("disable_kcp_input", "禁用 KCP 输入",
                        "拒绝 KCP 入站，其他节点用 TCP 连接本节点。", config.flags.disableKcpInput) {
                        update(config.copy(flags = config.flags.copy(disableKcpInput = it)))
                    }
                    boolFlag("disable_relay_kcp", "禁用 KCP 转发",
                        "禁止本节点转发 KCP 数据包，防止过度消耗流量。", config.flags.disableRelayKcp) {
                        update(config.copy(flags = config.flags.copy(disableRelayKcp = it)))
                    }
                    boolFlag("enable_quic_proxy", "启用 QUIC 代理",
                        "把 TCP 流量转为 QUIC，降低延迟提升速度。", config.flags.enableQuicProxy) {
                        update(config.copy(flags = config.flags.copy(enableQuicProxy = it)))
                    }
                    boolFlag("disable_quic_input", "禁用 QUIC 输入",
                        "拒绝 QUIC 入站，其他节点用 TCP 连接本节点。", config.flags.disableQuicInput) {
                        update(config.copy(flags = config.flags.copy(disableQuicInput = it)))
                    }
                    boolFlag("disable_relay_quic", "禁用 QUIC 转发",
                        "禁止本节点转发 QUIC 数据包。", config.flags.disableRelayQuic) {
                        update(config.copy(flags = config.flags.copy(disableRelayQuic = it)))
                    }
                    boolFlag("enable_relay_foreign_network_kcp", "转发外部网络 KCP",
                        "作为共享节点时也转发其他网络的 KCP 数据包。",
                        config.flags.enableRelayForeignNetworkKcp) {
                        update(config.copy(flags = config.flags.copy(enableRelayForeignNetworkKcp = it)))
                    }
                    boolFlag("enable_relay_foreign_network_quic", "转发外部网络 QUIC",
                        "作为共享节点时也转发其他网络的 QUIC 数据包。",
                        config.flags.enableRelayForeignNetworkQuic) {
                        update(config.copy(flags = config.flags.copy(enableRelayForeignNetworkQuic = it)))
                    }
                    boolFlag("disable_relay_data", "禁用数据转发",
                        "禁止本节点转发数据包（仅转发 RPC）。", config.flags.disableRelayData) {
                        update(config.copy(flags = config.flags.copy(disableRelayData = it)))
                    }
                    boolFlag("enable_udp_broadcast_relay", "UDP 广播中继",
                        "仅 Windows：捕获本机 UDP 广播转发给对等节点。",
                        config.flags.enableUdpBroadcastRelay) {
                        update(config.copy(flags = config.flags.copy(enableUdpBroadcastRelay = it)))
                    }

                    textFlag("default_protocol", "默认协议",
                        "连接到对等节点时使用的默认协议。", config.flags.defaultProtocol,
                        { update(config.copy(flags = config.flags.copy(defaultProtocol = it))) },
                        hint = "tcp")
                    textFlag("dev_name", "TUN 接口名称",
                        "可选 TUN 接口名称，留空自动生成。", config.flags.devName,
                        { update(config.copy(flags = config.flags.copy(devName = it))) })
                    numberFlag("mtu", "MTU",
                        "TUN 设备 MTU，非加密默认 1380，加密默认 1360。", config.flags.mtu,
                        { update(config.copy(flags = config.flags.copy(mtu = it))) },
                        hint = "400-1380")
                    textFlag("relay_network_whitelist", "网络白名单",
                        "只转发白名单网络的流量，空格分隔，支持通配符；空=禁转发。",
                        config.flags.relayNetworkWhitelist,
                        { update(config.copy(flags = config.flags.copy(relayNetworkWhitelist = it))) },
                        hint = "如 * 或 def* 或 net1 net2")
                    textFlag("data_compress_algo", "压缩算法",
                        "数据压缩算法：none / zstd。", config.flags.dataCompressAlgo,
                        { update(config.copy(flags = config.flags.copy(dataCompressAlgo = it))) },
                        hint = "none / zstd")
                    numberFlag("multi_thread_count", "线程数",
                        "多线程运行时使用的线程数（须大于 2）。", config.flags.multiThreadCount,
                        { update(config.copy(flags = config.flags.copy(multiThreadCount = it))) },
                        hint = "2")
                    textFlag("encryption_algorithm", "加密算法",
                        "aes-gcm / aes-256-gcm / chacha20 / xor。", config.flags.encryptionAlgorithm,
                        { update(config.copy(flags = config.flags.copy(encryptionAlgorithm = it))) },
                        hint = "aes-gcm")
                    textFlag("tld_dns_zone", "魔法 DNS 顶级域",
                        "魔法 DNS 的顶级域名区域。", config.flags.tldDnsZone,
                        { update(config.copy(flags = config.flags.copy(tldDnsZone = it))) },
                        hint = "et.net.")
                    numberFlag("quic_listen_port", "QUIC 监听端口（已废弃）",
                        "已废弃，通常留空。", config.flags.quicListenPort,
                        { update(config.copy(flags = config.flags.copy(quicListenPort = it))) })
                    numberFlag("foreign_relay_bps_limit", "异网中继限速",
                        "作为共享节点时限制非本地网络流量转发速率（字节/秒）。",
                        config.flags.foreignRelayBpsLimit,
                        { update(config.copy(flags = config.flags.copy(foreignRelayBpsLimit = it))) })
                    numberFlag("instance_recv_bps_limit", "实例接收限速",
                        "本实例整体入站限速（字节/秒），留空不限。",
                        config.flags.instanceRecvBpsLimit,
                        { update(config.copy(flags = config.flags.copy(instanceRecvBpsLimit = it))) })
                    numberFlag("socket_mark", "SO_MARK (Linux)",
                        "仅 Linux：底层套接字 fwmark，需 CAP_NET_ADMIN。",
                        config.flags.socketMark,
                        { update(config.copy(flags = config.flags.copy(socketMark = it))) })
                }
            }

            item {
                SectionCard("安全") {
                    BoolOption(
                        title = "启用安全模式",
                        description = "凭据互信模式，开启后可省略网络密码。",
                        expanded = "secureMode" in expandedDesc,
                        onToggle = { toggleDescription("secureMode") },
                        checked = config.secureMode.enabled,
                        onCheckedChange = {
                            update(
                                config.copy(
                                    secureMode = config.secureMode.copy(enabled = it)
                                )
                            )
                        }
                    )
                    TextOption(
                        title = "本地私钥",
                        description = "base64(X25519 私钥)，共享节点用于呈现稳定身份。",
                        expanded = "secureKey" in expandedDesc,
                        onToggle = { toggleDescription("secureKey") },
                        value = config.secureMode.localPrivateKey,
                        onValueChange = {
                            update(
                                config.copy(
                                    secureMode = config.secureMode.copy(localPrivateKey = it)
                                )
                            )
                        }
                    )
                    TextOption(
                        title = "本地公钥",
                        description = "与本地私钥对应的 X25519 公钥。",
                        expanded = "securePub" in expandedDesc,
                        onToggle = { toggleDescription("securePub") },
                        value = config.secureMode.localPublicKey,
                        onValueChange = {
                            update(
                                config.copy(
                                    secureMode = config.secureMode.copy(localPublicKey = it)
                                )
                            )
                        }
                    )
                    TextOption(
                        title = "凭据文件路径",
                        description = "凭据存储文件路径，管理节点重启后保留已生成凭据。",
                        expanded = "credentialFile" in expandedDesc,
                        onToggle = { toggleDescription("credentialFile") },
                        value = config.credentialFile,
                        onValueChange = { update(config.copy(credentialFile = it)) }
                    )
                    ListOption(
                        title = "TCP 端口白名单",
                        description = "允许外部访问本节点的 TCP 端口白名单，支持单个端口与范围。",
                        expanded = "tcpWhitelist" in expandedDesc,
                        onToggle = { toggleDescription("tcpWhitelist") },
                        value = config.tcpWhitelist.joinToString("\n"),
                        onValueChange = { update(config.copy(tcpWhitelist = parseLines(it))) },
                        hint = "每行一个，如 80 或 8000-9000"
                    )
                    ListOption(
                        title = "UDP 端口白名单",
                        description = "允许外部访问本节点的 UDP 端口白名单。",
                        expanded = "udpWhitelist" in expandedDesc,
                        onToggle = { toggleDescription("udpWhitelist") },
                        value = config.udpWhitelist.joinToString("\n"),
                        onValueChange = { update(config.copy(udpWhitelist = parseLines(it))) },
                        hint = "每行一个，如 53 或 5000-6000"
                    )
                }
            }

            item {
                SectionCard("其他") {
                    TextOption(
                        title = "ACL（访问控制）",
                        description = "限制节点间通信的复杂结构（组/链/规则），推荐在下方 TOML 编辑器中编辑。",
                        expanded = "acl" in expandedDesc,
                        onToggle = { toggleDescription("acl") },
                        value = config.aclToml,
                        onValueChange = { update(config.copy(aclToml = it)) },
                        hint = "[acl] 原始 TOML",
                        singleLine = false
                    )
                    TextOption(
                        title = "配置来源 (source)",
                        description = "内部元数据：user / web。",
                        expanded = "source" in expandedDesc,
                        onToggle = { toggleDescription("source") },
                        value = config.source,
                        onValueChange = { update(config.copy(source = it)) }
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = ::openEditor,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("编辑 TOML")
                    }
                    tomlDiff?.let { diff ->
                        if (diff.isEmpty) {
                            Text("TOML 无差异", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("TOML 修改记录", style = MaterialTheme.typography.titleSmall)
                            diff.added.forEach {
                                Text("+ $it", color = Color(0xFF2E7D32), fontFamily = FontFamily.Monospace)
                            }
                            diff.removed.forEach {
                                Text("- $it", color = Color(0xFFC62828), fontFamily = FontFamily.Monospace)
                            }
                            diff.changed.forEach { (old, new) ->
                                Text("~ $old", color = Color(0xFFC62828), fontFamily = FontFamily.Monospace)
                                Text("→ $new", color = Color(0xFF2E7D32), fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    Text(
                        "表单修改会基于当前模型重新生成 TOML；TOML 编辑器中保存的原始文本为权威配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (editorVisible) {
        TomlEditorDialog(
            text = editorText,
            onTextChange = { editorText = it },
            error = editorError,
            onSave = ::saveEditor,
            onClose = {
                editorVisible = false
                editorError = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TomlEditorDialog(
    text: String,
    onTextChange: (String) -> Unit,
    error: String?,
    onSave: () -> Unit,
    onClose: () -> Unit
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
        ) {
            TopAppBar(
                title = { Text("编辑 TOML") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                    }
                },
                actions = {
                    TextButton(onClick = onSave) { Text("保存") }
                }
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(12.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    description: String,
    expanded: Boolean,
    onToggleDescription: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleDescription)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null
            )
        }
        if (expanded) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        content()
    }
}

@Composable
private fun BoolOption(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onToggle)
                    .padding(vertical = 8.dp)
            )
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        }
        if (expanded) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun TextOption(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    singleLine: Boolean = true,
    number: Boolean = false
) {
    OptionRow(title, description, expanded, onToggle) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint) },
            singleLine = singleLine,
            keyboardOptions = if (number) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ListOption(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String
) {
    OptionRow(title, description, expanded, onToggle) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun parseLines(text: String): List<String> =
    text.lines()
        .flatMap { it.split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
