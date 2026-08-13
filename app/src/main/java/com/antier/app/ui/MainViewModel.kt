package com.antier.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.antier.core.AnTierVpnService
import com.antier.core.EasyTierService
import com.antier.core.IEasyTierService
import com.antier.core.IEasyTierStatusListener
import com.antier.core.VpnConflictPolicy
import com.antier.core.VpnSettingsStore
import com.antier.app.ui.model.NetworkConfig
import com.antier.app.ui.store.NetworkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.LinkedHashSet

/** 单个实例的运行时信息。 */
data class NetworkInfo(
    val running: Boolean,
    val ipv4: String?,
    val proxyCidrs: List<String>,
    val errorMsg: String?,
    val routes: List<RouteEntry> = emptyList(),
    val rxBytes: Long = 0,
    val txBytes: Long = 0
)

/** 一条路由/对端信息（对应 easytier-cli peer/route 输出）。 */
data class RouteEntry(
    val ipv4: String,
    val hostname: String,
    val cost: String,
    val latencyMs: Double,
    val lossRate: Double,
    val tunnelProto: String,
    val proxyCidrs: List<String>,
    val version: String
)

/** 网络运行状态：路由表 + 上下行速率。 */
data class NetworkStatus(
    val routes: List<RouteEntry>,
    val uploadBps: Long,
    val downloadBps: Long
)

/** 从配置中提取的数据面模式提示。 */
data class ConfigHints(
    val noTun: Boolean,
    val useSmoltcp: Boolean,
    val socks5Endpoint: String?
)

enum class CardOrigin { INTERNAL, EXTERNAL }

/** 主界面网络卡片展示数据。 */
data class NetworkCard(
    val id: String?,
    val name: String,
    val running: Boolean,
    val noTun: Boolean,
    val socks5Port: String?,
    val origin: CardOrigin,
    val ipText: String,
    val errorMsg: String?
)

/** 请求建立 VPN：携带启动 AnTierVpnService 所需的 Intent。 */
data class VpnPrepareRequest(val serviceIntent: Intent)

/** 一条运行日志（CLI 风格文本，含时间戳与级别）。 */
data class LogEntry(val text: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "MainViewModel"
        /** DHCP 未分配地址前用于先拉起 VPN 的占位地址（与 EasyTier DHCP 默认网段一致）。 */
        private const val DHCP_PLACEHOLDER_IPV4 = "10.126.126.1/24"

        fun parseConfigHints(config: String): ConfigHints {
            val noTun = Regex("""no_tun\s*=\s*true""", RegexOption.IGNORE_CASE)
                .containsMatchIn(config)
            val useSmoltcp = Regex("""use_smoltcp\s*=\s*true""", RegexOption.IGNORE_CASE)
                .containsMatchIn(config)
            val socks5Raw = Regex("""socks5_proxy\s*=\s*"([^"]+)"""")
                .find(config)?.groupValues?.get(1)
            return ConfigHints(noTun, useSmoltcp, socks5Raw?.let(::normalizeSocks5Endpoint))
        }

        private fun normalizeSocks5Endpoint(raw: String): String {
            val noScheme = raw.substringAfter("://", raw)
            val port = noScheme.substringAfterLast(':', "").toIntOrNull()
            return if (port != null) "127.0.0.1:$port" else noScheme
        }
    }

    private val app = getApplication<Application>()

    private val _service = MutableStateFlow<IEasyTierService?>(null)
    val service: StateFlow<IEasyTierService?> = _service.asStateFlow()

    private val _networks = MutableStateFlow<List<NetworkCard>>(emptyList())
    val networks: StateFlow<List<NetworkCard>> = _networks.asStateFlow()

    private val _networkStatus = MutableStateFlow<Map<String, NetworkStatus>>(emptyMap())
    val networkStatus: StateFlow<Map<String, NetworkStatus>> = _networkStatus.asStateFlow()

    private val _activeTunInstance = MutableStateFlow<String?>(null)
    val activeTunInstance: StateFlow<String?> = _activeTunInstance.asStateFlow()

    private val _vpnPrepareRequest = MutableStateFlow<VpnPrepareRequest?>(null)
    val vpnPrepareRequest: StateFlow<VpnPrepareRequest?> = _vpnPrepareRequest.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastEvent = MutableStateFlow("")
    val lastEvent: StateFlow<String> = _lastEvent.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    /** 已展示过的 logcat 行（去重）。 */
    private val seenLogcatLines = LinkedHashSet<String>()

    /** 运行实例的当前名称列表（refresh 时更新）。 */
    private var runningNames: List<String> = emptyList()

    /** 运行实例的运行时信息缓存（refresh 时更新）。 */
    private var runningInfo: Map<String, NetworkInfo> = emptyMap()

    /** 本应用已成功启动、但 collectNetworkInfos 尚未回报的实例名。 */
    private val pendingRunningNames = LinkedHashSet<String>()

    /** 当前传给 VpnService 的 IPv4/IPv6（用于 DHCP 换地址后重建 VPN）。 */
    private val vpnIpv4ByName = mutableMapOf<String, String>()
    private val vpnIpv6ByName = mutableMapOf<String, String>()

    /** 上一次字节采样（用于计算上下行速率）。 */
    private class ByteSample(val timeMs: Long, val rxBytes: Long, val txBytes: Long)
    private val byteSamples = mutableMapOf<String, ByteSample>()

    /** 本界面启动过的实例的配置提示。 */
    private val hintsByName = mutableMapOf<String, ConfigHints>()

    private val statusListener = object : IEasyTierStatusListener.Stub() {
        override fun onEvent(eventJson: String?) {
            _lastEvent.value = eventJson ?: ""
            if (!eventJson.isNullOrBlank()) {
                appendLog("INFO", formatEvent(eventJson))
            }
            refreshStatus()
        }
    }

    init {
        startLogcatPoller()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IEasyTierService.Stub.asInterface(binder)
            _service.value = svc
            try {
                svc.registerStatusListener(statusListener)
            } catch (e: Exception) {
                Log.w(TAG, "register listener failed", e)
            }
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _service.value?.let {
                try {
                    it.unregisterStatusListener(statusListener)
                } catch (_: Exception) {
                }
            }
            _service.value = null
        }
    }

    fun bind(context: Context) {
        val intent = EasyTierService.bindIntent()
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService failed", e)
        }
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind(context: Context) {
        _service.value?.let {
            try {
                it.unregisterStatusListener(statusListener)
            } catch (_: Exception) {
            }
        }
        try {
            context.unbindService(connection)
        } catch (_: Exception) {
        }
    }

    fun consumeLastError() {
        _lastError.value = null
    }

    fun clearLogs() {
        _logs.value = emptyList()
        seenLogcatLines.clear()
    }

    fun consumeVpnPrepareRequest() {
        _vpnPrepareRequest.value = null
    }

    /** 新建网络配置，返回实例 ID 供导航进入编辑页。 */
    fun createNetwork(): String {
        val config = NetworkConfig.default()
        NetworkStore.save(app, config.instanceId, config.toToml())
        rebuildCards()
        appendLog("INFO", "新建网络配置：${config.displayName}")
        return config.instanceId
    }

    fun saveNetwork(id: String, toml: String) {
        NetworkStore.save(app, id, toml)
        rebuildCards()
    }

    fun deleteNetwork(id: String) {
        val stored = NetworkStore.get(app, id) ?: return
        if (runningNames.any { it == stored.config.instanceName }) {
            stopNetworkByName(stored.config.instanceName)
        }
        NetworkStore.delete(app, id)
        rebuildCards()
    }

    /** 启动指定网络。no-tun 实例可并发；VPN 实例按冲突策略挤占/谦让。 */
    fun startNetwork(id: String) {
        val stored = NetworkStore.get(app, id) ?: run {
            setError("网络配置不存在")
            return
        }
        val config = stored.config
        val name = config.instanceName.ifBlank { "default" }
        val hints = ConfigHints(
            noTun = config.flags.noTun,
            useSmoltcp = config.flags.useSmoltcp,
            socks5Endpoint = config.socks5Proxy.takeIf { it.isNotBlank() }
                ?.let(::normalizeSocks5Endpoint)
        )

        viewModelScope.launch {
            val svc = _service.value
            if (svc == null) {
                setError("内核服务未连接")
                return@launch
            }
            if (runningNames.contains(name)) {
                setError("实例 $name 已在运行，请先断开")
                return@launch
            }

            // VPN 冲突处理：同时只允许一个 TUN 实例。
            if (!hints.noTun) {
                val existingTun = runningNames.firstOrNull { !isNoTunInstance(it) }
                if (existingTun != null) {
                    val policy = VpnSettingsStore.load(app).conflictPolicy
                    when (policy) {
                        VpnConflictPolicy.FIRST_COMES_FIRST -> {
                            setError(
                                "先行者优先：已有 VPN 网络 $existingTun 在运行，本次连接谦让（未启动）"
                            )
                            return@launch
                        }
                        VpnConflictPolicy.LAST_COMES_FIRST -> {
                            // 后来者优先：挤占，先关闭先启动的 VPN 网络。
                            stopNetworkInternal(svc, existingTun)
                        }
                    }
                }
            }

            val rc = withContext(Dispatchers.IO) { svc.runNetworkInstance(stored.toml) }
            if (rc != 0) {
                setError(withContext(Dispatchers.IO) { svc.getLastError() } ?: "启动失败")
                return@launch
            }
            appendLog("INFO", "启动网络：$name (${if (hints.noTun) "no-tun" else "VPN"})")
            hintsByName[name] = hints
            // 内核启动成功后立即标记为运行，避免 collect 暂时返回空导致 UI 仍显示“连接”。
            pendingRunningNames.add(name)
            runningInfo = runningInfo + (name to NetworkInfo(true, null, emptyList(), null))
            runningNames = (runningNames + name).distinct()
            rebuildCards()
            setError(
                if (hints.noTun && hints.socks5Endpoint == null) {
                    "no-tun 已启用但未配置 socks5_proxy，实例只能被访问"
                } else {
                    null
                }
            )
            refreshStatus()

            // VPN 模式：立即用配置的虚拟 IP（或 DHCP 占位地址）建立 TUN。
            // 不能等 collect 返回 IP 再建 VPN——内核要拿到 TUN fd 后 DHCP 才会分配 IP。
            if (!hints.noTun) {
                val ipv4 = config.ipv4WithPrefix.ifBlank { DHCP_PLACEHOLDER_IPV4 }
                requestVpn(name, ipv4, config.ipv6WithPrefix, emptyList())
            }
        }
    }

    /** 断开指定网络（保留其余网络）。 */
    fun stopNetwork(id: String) {
        val stored = NetworkStore.get(app, id) ?: return
        stopNetworkByName(stored.config.instanceName.ifBlank { "default" })
    }

    fun stopNetworkByName(name: String) {
        viewModelScope.launch {
            val svc = _service.value ?: return@launch
            stopNetworkInternal(svc, name)
            refreshStatus()
        }
    }

    private suspend fun stopNetworkInternal(svc: IEasyTierService, name: String) {
        val keep = runningNames.filter { it != name }
        val rc = withContext(Dispatchers.IO) {
            svc.retainNetworkInstance(keep.toTypedArray())
        }
        if (rc != 0) {
            setError(withContext(Dispatchers.IO) { svc.getLastError() } ?: "断开失败")
            return
        }
        appendLog("INFO", "断开网络：$name")
        pendingRunningNames.remove(name)
        vpnIpv4ByName.remove(name)
        vpnIpv6ByName.remove(name)
        byteSamples.remove(name)
        hintsByName.remove(name)
        if (_activeTunInstance.value == name) {
            app.stopService(Intent(app, AnTierVpnService::class.java))
            _activeTunInstance.value = null
        }
    }

    /** 关闭所有网络并关闭 VPN。 */
    fun stopAllNetworks() {
        viewModelScope.launch {
            app.stopService(Intent(app, AnTierVpnService::class.java))
            _activeTunInstance.value = null
            val svc = _service.value
            if (svc != null) {
                withContext(Dispatchers.IO) { svc.retainNetworkInstance(null) }
            }
            pendingRunningNames.clear()
            vpnIpv4ByName.clear()
            vpnIpv6ByName.clear()
            byteSamples.clear()
            hintsByName.clear()
            appendLog("INFO", "关闭所有网络")
            refreshStatus()
        }
    }

    /** 刷新运行实例并重建主界面卡片。 */
    fun refreshStatus() {
        viewModelScope.launch { refreshStatusInternal() }
    }

    private suspend fun refreshStatusInternal() {
        val svc = _service.value ?: return
        val infosJson = withContext(Dispatchers.IO) { svc.collectNetworkInfos(50) }

        val parsed = infosJson?.let(::parseAllNetworkInfos).orEmpty()
        if (infosJson == null) {
            Log.w(TAG, "collectNetworkInfos returned null")
        } else if (parsed.isEmpty()) {
            Log.w(TAG, "collectNetworkInfos parsed empty: $infosJson")
        }

        // 合并已由本应用启动、但收集接口暂时还没回报的实例，避免 UI 状态回退。
        val merged = parsed.toMutableMap()
        for (name in pendingRunningNames) {
            if (name !in merged) {
                merged[name] = runningInfo[name] ?: NetworkInfo(true, null, emptyList(), null)
            }
        }
        pendingRunningNames.removeAll(parsed.keys)

        runningInfo = merged
        // v2.6.4 稳定 API 没有 listInstances，用 collectNetworkInfos 的 running 标志推导
        runningNames = merged.filterValues { it.running }.keys.toList()

        if (_activeTunInstance.value != null &&
            _activeTunInstance.value !in runningNames
        ) {
            _activeTunInstance.value = null
            app.stopService(Intent(app, AnTierVpnService::class.java))
        }

        // DHCP 分配到真实虚拟 IPv4 后，用真实地址重建 VPN。
        val active = _activeTunInstance.value
        if (active != null) {
            val info = runningInfo[active]
            val actualIpv4 = info?.ipv4?.let { "$it/24" }
            if (actualIpv4 != null && actualIpv4 != vpnIpv4ByName[active]) {
                requestVpn(active, actualIpv4, vpnIpv6ByName[active] ?: "", info.proxyCidrs)
            }
        }
        updateNetworkStatus()
        rebuildCards()
    }

    private fun updateNetworkStatus() {
        val now = SystemClock.elapsedRealtime()
        val statuses = mutableMapOf<String, NetworkStatus>()
        for ((name, info) in runningInfo) {
            val prev = byteSamples[name]
            val dtMs = now - (prev?.timeMs ?: now)
            val uploadBps = if (prev != null && dtMs > 0) {
                ((info.txBytes - prev.txBytes).coerceAtLeast(0) * 1000 / dtMs)
            } else {
                0L
            }
            val downloadBps = if (prev != null && dtMs > 0) {
                ((info.rxBytes - prev.rxBytes).coerceAtLeast(0) * 1000 / dtMs)
            } else {
                0L
            }
            byteSamples[name] = ByteSample(now, info.rxBytes, info.txBytes)
            statuses[name] = NetworkStatus(info.routes, uploadBps, downloadBps)
        }
        byteSamples.keys.retainAll(runningInfo.keys)
        _networkStatus.value = statuses
    }

    fun onVpnGranted() {
        val request = _vpnPrepareRequest.value ?: return
        val instanceName = request.serviceIntent.getStringExtra(AnTierVpnService.EXTRA_INSTANCE_NAME)
        try {
            app.startService(request.serviceIntent)
            if (instanceName != null) _activeTunInstance.value = instanceName
            appendLog("INFO", "VPN 已授权，TUN 绑定到实例：$instanceName")
        } catch (e: Exception) {
            setError("启动 VPN 服务失败：${e.message}")
        }
    }

    fun onVpnDenied() {
        setError("VPN 授权被拒绝，实例仍在内核中运行（无 TUN 数据面）")
    }

    private fun startLogcatPoller() {
        viewModelScope.launch {
            while (true) {
                pullLogcat()
                delay(3000)
            }
        }
    }

    /**
     * 拉取本进程（应用与内核同进程）的 logcat 日志。
     * Android 允许应用读取自身 UID 的日志，无需 READ_LOGS 权限；
     * 内核日志 tag 为 EasyTier-JNI，过滤后即为 CLI 风格的运行日志。
     */
    private fun pullLogcat() {
        viewModelScope.launch {
            val lines = withContext(Dispatchers.IO) {
                runCatching {
                    val pid = Process.myPid()
                    val proc = ProcessBuilder(
                        "logcat", "-d", "-v", "time", "--pid=$pid", "-t", "1000"
                    )
                        .redirectErrorStream(true)
                        .start()
                    val text = proc.inputStream.bufferedReader().use { it.readText() }
                    proc.waitFor()
                    text.lines().filter { it.contains("EasyTier", ignoreCase = true) }
                }.getOrDefault(emptyList())
            }
            if (lines.isEmpty()) return@launch
            val unseen = lines.filter { seenLogcatLines.add(it) }
            if (unseen.isEmpty()) return@launch
            _logs.value = (_logs.value + unseen.map { LogEntry(it) }).takeLast(800)
        }
    }

    private fun appendLog(level: String, message: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _logs.value = (_logs.value + LogEntry("[$time] [$level] $message")).takeLast(800)
    }

    private fun setError(message: String?) {
        _lastError.value = message
        if (message != null) appendLog("ERROR", message)
    }

    /** 把内核事件 JSON 格式化成 CLI 风格的一行。 */
    private fun formatEvent(eventJson: String): String {
        return try {
            val obj = JSONObject(eventJson)
            val event = obj.optString("event", "unknown")
            val detail = buildString {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key == "event") continue
                    append(" $key=").append(obj.optString(key))
                }
            }
            "event=$event$detail"
        } catch (e: Exception) {
            "event=$eventJson"
        }
    }

    private fun requestVpn(
        name: String,
        ipv4: String,
        ipv6: String,
        proxyCidrs: List<String>
    ) {
        if (ipv4.isBlank() && ipv6.isBlank()) return
        if (_activeTunInstance.value == name &&
            vpnIpv4ByName[name] == ipv4 &&
            vpnIpv6ByName[name] == ipv6
        ) {
            return
        }

        // 重建 VPN（例如 DHCP 换了 IP）：先停旧服务，再请求新的。
        if (_activeTunInstance.value != null) {
            app.stopService(Intent(app, AnTierVpnService::class.java))
            _activeTunInstance.value = null
        }
        vpnIpv4ByName[name] = ipv4
        vpnIpv6ByName[name] = ipv6

        val intent = Intent(app, AnTierVpnService::class.java).apply {
            if (ipv4.isNotBlank()) putExtra(AnTierVpnService.EXTRA_IPV4, ipv4)
            if (ipv6.isNotBlank()) putExtra(AnTierVpnService.EXTRA_IPV6, ipv6)
            putStringArrayListExtra(
                AnTierVpnService.EXTRA_PROXY_CIDRS,
                ArrayList(proxyCidrs)
            )
            putExtra(AnTierVpnService.EXTRA_INSTANCE_NAME, name)
        }
        _vpnPrepareRequest.value = VpnPrepareRequest(intent)
    }

    private fun rebuildCards() {
        val saved = NetworkStore.loadAll(app)
        val savedNames = saved.map { it.config.instanceName }.toSet()
        val cards = mutableListOf<NetworkCard>()

        saved.forEach { stored ->
            val config = stored.config
            val name = config.instanceName.ifBlank { "default" }
            val info = runningInfo[name]
            val running = runningNames.contains(name)
            cards.add(
                NetworkCard(
                    id = stored.id,
                    name = config.displayName,
                    running = running,
                    noTun = config.flags.noTun,
                    socks5Port = socks5Port(config.socks5Proxy),
                    origin = CardOrigin.INTERNAL,
                    ipText = when {
                        running && !info?.ipv4.isNullOrEmpty() ->
                            "${info?.ipv4}/${ipv4PrefixLen(config.ipv4)}"
                        running && config.ipv4WithPrefix.isNotBlank() -> config.ipv4WithPrefix
                        !running && config.ipv4WithPrefix.isNotBlank() -> config.ipv4WithPrefix
                        else -> "未连接"
                    },
                    errorMsg = if (running) info?.errorMsg else null
                )
            )
        }

        runningNames.filterNot { it in savedNames }.forEach { name ->
            val hints = hintsByName[name]
            val info = runningInfo[name]
            cards.add(
                NetworkCard(
                    id = null,
                    name = name,
                    running = true,
                    noTun = hints?.noTun ?: false,
                    socks5Port = hints?.socks5Endpoint?.substringAfterLast(':'),
                    origin = CardOrigin.EXTERNAL,
                    ipText = info?.ipv4?.let { "$it/24" } ?: "未连接",
                    errorMsg = info?.errorMsg
                )
            )
        }

        _networks.value = cards
    }

    private fun isNoTunInstance(name: String): Boolean =
        hintsByName[name]?.noTun ?: false

    private fun socks5Port(socks5Proxy: String): String? {
        if (socks5Proxy.isBlank()) return null
        return Regex("""(?::)(\d+)\s*$""").find(socks5Proxy)?.groupValues?.get(1)
    }

    private fun ipv4PrefixLen(ipv4: String): String {
        val idx = ipv4.indexOf('/')
        return if (idx >= 0) ipv4.substring(idx + 1) else "24"
    }

    private fun parseAllNetworkInfos(json: String): Map<String, NetworkInfo> {
        return try {
            val root = JSONObject(json)
            val map = root.optJSONObject("map") ?: return emptyMap()
            val result = mutableMapOf<String, NetworkInfo>()
            val keys = map.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                parseNetworkInfo(json, name)?.let { result[name] = it }
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "parse all network infos failed", e)
            emptyMap()
        }
    }

    private fun parseNetworkInfo(json: String, name: String): NetworkInfo? {
        return try {
            val root = JSONObject(json)
            val map = root.optJSONObject("map") ?: return null
            val inst = map.optJSONObject(name) ?: return null

            val running = inst.optBoolean("running", false)
            val errorRaw = inst.optString("error_msg")
            val errorMsg = errorRaw.takeIf { it.isNotBlank() && it != "null" }

            val myNode = inst.optJSONObject("my_node_info")
            val v4 = myNode?.optJSONObject("virtual_ipv4")
            val ipv4 = ipv4FromInet(v4)

            val proxyCidrs = mutableListOf<String>()
            val routes = inst.optJSONArray("routes")
            if (routes != null) {
                for (i in 0 until routes.length()) {
                    val route = routes.optJSONObject(i) ?: continue
                    val cidrs = route.optJSONArray("proxy_cidrs") ?: continue
                    for (j in 0 until cidrs.length()) {
                        cidrs.optString(j)?.takeIf { it.isNotEmpty() }?.let { proxyCidrs.add(it) }
                    }
                }
            }

            val routeEntries = mutableListOf<RouteEntry>()
            var rxBytes = 0L
            var txBytes = 0L
            val pairs = inst.optJSONArray("peer_route_pairs")
            if (pairs != null) {
                for (i in 0 until pairs.length()) {
                    val pair = pairs.optJSONObject(i) ?: continue
                    val routeObj = pair.optJSONObject("route") ?: continue
                    val peerObj = pair.optJSONObject("peer")

                    val routeIpv4 = ipv4FromInet(routeObj.optJSONObject("ipv4_addr"))
                    val hostname = routeObj.optString("hostname")
                    val costInt = routeObj.optInt("cost", 0)
                    val cost = when (costInt) {
                        0 -> "Local"
                        1 -> "DIRECT"
                        else -> costInt.toString()
                    }
                    val pathLatency = routeObj.optInt("path_latency", 0).toDouble()
                    var latencyMs = pathLatency
                    var lossRate = 0.0
                    val tunnelProtos = LinkedHashSet<String>()

                    val conns = peerObj?.optJSONArray("conns")
                    if (conns != null) {
                        var latencySumUs = 0L
                        var latencyCount = 0
                        var lossSum = 0.0
                        var lossCount = 0
                        for (j in 0 until conns.length()) {
                            val conn = conns.optJSONObject(j) ?: continue
                            val stats = conn.optJSONObject("stats")
                            rxBytes += stats?.optLong("rx_bytes", 0L) ?: 0L
                            txBytes += stats?.optLong("tx_bytes", 0L) ?: 0L
                            val latencyUs = stats?.optLong("latency_us", 0L) ?: 0L
                            if (latencyUs > 0) {
                                latencySumUs += latencyUs
                                latencyCount++
                            }
                            if (conn.has("loss_rate")) {
                                lossSum += conn.optDouble("loss_rate", 0.0)
                                lossCount++
                            }
                            val tunnel = conn.optJSONObject("tunnel")
                            val ttype = tunnel?.optString("tunnel_type")?.takeIf { it.isNotBlank() }
                            if (ttype != null) tunnelProtos.add(ttype)
                        }
                        if (latencyCount > 0) {
                            latencyMs = latencySumUs / 1000.0 / latencyCount
                        }
                        if (lossCount > 0) {
                            lossRate = lossSum / lossCount * 100.0
                        }
                    }

                    val routeCidrs = mutableListOf<String>()
                    val routeCidrArr = routeObj.optJSONArray("proxy_cidrs")
                    if (routeCidrArr != null) {
                        for (j in 0 until routeCidrArr.length()) {
                            routeCidrArr.optString(j)?.takeIf { it.isNotEmpty() }
                                ?.let { routeCidrs.add(it) }
                        }
                    }

                    routeEntries.add(
                        RouteEntry(
                            ipv4 = routeIpv4 ?: "-",
                            hostname = hostname.ifBlank { "?" },
                            cost = cost,
                            latencyMs = latencyMs,
                            lossRate = lossRate,
                            tunnelProto = tunnelProtos.joinToString(",").ifBlank { "-" },
                            proxyCidrs = routeCidrs,
                            version = routeObj.optString("version")
                        )
                    )
                }
            }

            NetworkInfo(
                running = running,
                ipv4 = ipv4,
                proxyCidrs = proxyCidrs.distinct(),
                errorMsg = errorMsg,
                routes = routeEntries,
                rxBytes = rxBytes,
                txBytes = txBytes
            )
        } catch (e: Exception) {
            Log.w(TAG, "parse network info failed", e)
            null
        }
    }

    private fun ipv4FromInet(inet: JSONObject?): String? {
        if (inet == null) return null
        val addressObj = inet.optJSONObject("address")
        val addr = if (addressObj != null) {
            addressObj.optLong("addr", -1)
        } else {
            inet.optLong("addr", -1)
        }
        val prefix = if (inet.has("network_length")) inet.optInt("network_length", 24) else 24
        return intToIpv4(addr)?.let { "$it/$prefix" }
    }

    private fun intToIpv4(value: Long): String? {
        if (value < 0 || value > 0xFFFFFFFFL) return null
        return buildString {
            append((value shr 24) and 0xFF).append('.')
            append((value shr 16) and 0xFF).append('.')
            append((value shr 8) and 0xFF).append('.')
            append(value and 0xFF)
        }
    }
}
