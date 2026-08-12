package com.antier.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
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
    val errorMsg: String?
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
        private const val IP_WAIT_TIMEOUT_MS = 90_000L

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
            setError(
                if (hints.noTun && hints.socks5Endpoint == null) {
                    "no-tun 已启用但未配置 socks5_proxy，实例只能被访问"
                } else {
                    null
                }
            )
            refreshStatus()

            // 等待 DHCP 分配虚拟 IPv4：TUN 模式需要 IP 才能建 VPN。
            val deadline = System.currentTimeMillis() + IP_WAIT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(1500)
                refreshStatus()
                val info = runningInfo[name] ?: continue
                if (!info.running) return@launch
                if (hints.noTun) return@launch
                if (!info.ipv4.isNullOrEmpty()) {
                    requestVpn(name, info)
                    return@launch
                }
            }
            if (!hints.noTun) {
                setError("实例 $name 已运行，但等待虚拟 IP 超时，未自动建立 VPN")
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
            hintsByName.clear()
            appendLog("INFO", "关闭所有网络")
            refreshStatus()
        }
    }

    /** 刷新运行实例并重建主界面卡片。 */
    fun refreshStatus() {
        viewModelScope.launch {
            val svc = _service.value ?: return@launch
            val infosJson = withContext(Dispatchers.IO) { svc.collectNetworkInfos(50) }

            runningInfo = infosJson?.let(::parseAllNetworkInfos).orEmpty()
            // v2.6.4 稳定 API 没有 listInstances，用 collectNetworkInfos 的 running 标志推导
            runningNames = runningInfo.filterValues { it.running }.keys.toList()

            if (_activeTunInstance.value != null &&
                _activeTunInstance.value !in runningNames
            ) {
                _activeTunInstance.value = null
                app.stopService(Intent(app, AnTierVpnService::class.java))
            }
            rebuildCards()
        }
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

    private fun requestVpn(name: String, info: NetworkInfo) {
        val intent = Intent(app, AnTierVpnService::class.java).apply {
            putExtra(AnTierVpnService.EXTRA_IPV4, info.ipv4)
            putStringArrayListExtra(
                AnTierVpnService.EXTRA_PROXY_CIDRS,
                ArrayList(info.proxyCidrs)
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
            val ipv4 = v4?.let { intToIpv4(it.optLong("address", -1)) }

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
            NetworkInfo(running, ipv4, proxyCidrs.distinct(), errorMsg)
        } catch (e: Exception) {
            Log.w(TAG, "parse network info failed", e)
            null
        }
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
