package com.antier.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.antier.core.AnTierVpnService
import com.antier.core.EasyTierService
import com.antier.core.IConfigServerEventCallback
import com.antier.core.IEasyTierService
import com.antier.core.IEasyTierStatusListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 单个实例的运行时信息（来自 collectNetworkInfos 的 JSON）。 */
data class NetworkInfo(
    val running: Boolean,
    val ipv4: String?,
    val proxyCidrs: List<String>,
    val errorMsg: String?
)

/** 从用户 TOML 中提取的数据面模式提示。 */
data class ConfigHints(
    val noTun: Boolean,
    val useSmoltcp: Boolean,
    val socks5Endpoint: String?
)

/** 界面展示用的实例状态（运行时信息 + 启动时解析到的模式）。 */
enum class InstanceOrigin { LOCAL, EXTERNAL }

data class InstanceState(
    val name: String,
    val running: Boolean,
    val ipv4: String?,
    val proxyCidrs: List<String>,
    val errorMsg: String?,
    val noTun: Boolean,
    val socks5Endpoint: String?,
    /** 本应用界面启动的还是外部应用通过 AIDL 启动的。 */
    val origin: InstanceOrigin,
    /** 是否已拿到该实例的配置（决定模式是否可信）。 */
    val modeKnown: Boolean
)

/** 请求建立 VPN：携带启动 AnTierVpnService 所需的 Intent。 */
data class VpnPrepareRequest(val serviceIntent: Intent)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val DEFAULT_INSTANCE_NAME = "antier_android"
        private const val IP_WAIT_TIMEOUT_MS = 90_000L

        /** 从用户 TOML 配置中提取与数据面模式相关的设置。 */
        fun parseConfigHints(config: String): ConfigHints {
            val noTun = Regex("""no_tun\s*=\s*true""", RegexOption.IGNORE_CASE)
                .containsMatchIn(config)
            val useSmoltcp = Regex("""use_smoltcp\s*=\s*true""", RegexOption.IGNORE_CASE)
                .containsMatchIn(config)
            val socks5Raw = Regex("""socks5_proxy\s*=\s*"([^"]+)"""")
                .find(config)?.groupValues?.get(1)
            return ConfigHints(noTun, useSmoltcp, socks5Raw?.let(::normalizeSocks5Endpoint))
        }

        /** 把 socks5_proxy 里的绑定地址规范化为客户端可用的 127.0.0.1:<port>。 */
        private fun normalizeSocks5Endpoint(raw: String): String {
            val noScheme = raw.substringAfter("://", raw)
            val port = noScheme.substringAfterLast(':', "").toIntOrNull()
            return if (port != null) "127.0.0.1:$port" else noScheme
        }
    }

    private val _service = MutableStateFlow<IEasyTierService?>(null)
    val service: StateFlow<IEasyTierService?> = _service.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _lastEvent = MutableStateFlow("")
    val lastEvent: StateFlow<String> = _lastEvent.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _vpnPrepareRequest = MutableStateFlow<VpnPrepareRequest?>(null)
    val vpnPrepareRequest: StateFlow<VpnPrepareRequest?> = _vpnPrepareRequest.asStateFlow()

    /** 当前所有运行实例的展示状态。 */
    private val _instances = MutableStateFlow<List<InstanceState>>(emptyList())
    val instances: StateFlow<List<InstanceState>> = _instances.asStateFlow()

    /** 当前持有 VpnService TUN 的实例名（同一时间最多一个）。 */
    private val _activeTunInstance = MutableStateFlow<String?>(null)
    val activeTunInstance: StateFlow<String?> = _activeTunInstance.asStateFlow()

    private val _configServerConnected = MutableStateFlow(false)
    val configServerConnected: StateFlow<Boolean> = _configServerConnected.asStateFlow()

    /** 本界面启动过的实例的配置提示（refresh 时用于还原模式展示）。 */
    private val hintsByName = mutableMapOf<String, ConfigHints>()

    /** 外部实例通过 JSON-RPC 查到的配置提示缓存（key 为实例名）。 */
    private val rpcHintsByName = mutableMapOf<String, ConfigHints>()

    private var configServerCallback: IConfigServerEventCallback? = null

    private val statusListener = object : IEasyTierStatusListener.Stub() {
        override fun onEvent(eventJson: String?) {
            _lastEvent.value = eventJson ?: ""
        }
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

    /**
     * 启动一个内核实例。
     *
     * - no-tun 实例：不请求 VPN，主动访问走配置里的 SOCKS5；
     * - TUN 实例：等 DHCP 分配虚拟 IP 后请求 VPN 授权并拉起 VpnService；
     * - 同时最多允许一个 TUN 实例。
     */
    fun startNetwork(config: String) {
        viewModelScope.launch {
            val svc = _service.value
            if (svc == null) {
                _lastError.value = "AIDL service not connected"
                return@launch
            }
            val hints = parseConfigHints(config)
            val name = parseInstanceName(config) ?: DEFAULT_INSTANCE_NAME

            val current = _instances.value
            if (current.any { it.name == name }) {
                _lastError.value = "实例 $name 已在运行，请先停止"
                return@launch
            }
            if (!hints.noTun) {
                val tunOwner = current.firstOrNull { !it.noTun }
                if (tunOwner != null) {
                    _lastError.value = "同时只能有一个 TUN 实例，请先停止 ${tunOwner.name}"
                    return@launch
                }
            }

            val rc = withContext(Dispatchers.IO) { svc.runNetworkInstance(config) }
            if (rc != 0) {
                _lastError.value = withContext(Dispatchers.IO) { svc.getLastError() }
                    ?: "runNetworkInstance failed"
                return@launch
            }

            _lastError.value = if (hints.noTun && hints.socks5Endpoint == null) {
                "no_tun 已启用但未配置 socks5_proxy，实例只能被访问，无法主动连接对端"
            } else {
                null
            }
            hintsByName[name] = hints
            _instances.value = _instances.value + InstanceState(
                name = name,
                running = true,
                ipv4 = null,
                proxyCidrs = emptyList(),
                errorMsg = null,
                noTun = hints.noTun,
                socks5Endpoint = hints.socks5Endpoint,
                origin = InstanceOrigin.LOCAL,
                modeKnown = true
            )

            // 等待 DHCP 分配虚拟 IPv4：TUN 模式需要 IP 才能建 VPN；no-tun 模式仅用于展示。
            val deadline = System.currentTimeMillis() + IP_WAIT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(1500)
                val info = withContext(Dispatchers.IO) { queryInstanceInfo(svc, name) } ?: continue
                if (!info.running) continue
                updateInstance(name) {
                    it.copy(ipv4 = info.ipv4, proxyCidrs = info.proxyCidrs, errorMsg = info.errorMsg)
                }
                if (hints.noTun) return@launch
                if (!info.ipv4.isNullOrEmpty()) {
                    requestVpn(name, info)
                    return@launch
                }
            }
            if (!hints.noTun) {
                _lastError.value = "实例 $name 已运行，但等待虚拟 IP 超时，未自动建立 VPN"
            }
        }
    }

    /** 停止指定实例（保留其余实例），若该实例持有 TUN 则同时关闭 VpnService。 */
    fun stopInstance(name: String) {
        viewModelScope.launch {
            val svc = _service.value
            if (svc == null) {
                _lastError.value = "AIDL service not connected"
                return@launch
            }
            val running = withContext(Dispatchers.IO) { svc.listInstances(50) }
                ?.let(::parseListInstances).orEmpty()
            val keep = running.filter { it != name }
            val rc = withContext(Dispatchers.IO) {
                svc.retainNetworkInstance(keep.toTypedArray())
            }
            if (rc != 0) {
                _lastError.value = withContext(Dispatchers.IO) { svc.getLastError() }
                    ?: "retainNetworkInstance failed"
                return@launch
            }
            hintsByName.remove(name)
            rpcHintsByName.remove(name)
            if (_activeTunInstance.value == name) {
                getApplication<Application>().stopService(
                    Intent(getApplication(), AnTierVpnService::class.java)
                )
                _activeTunInstance.value = null
            }
            refreshStatus()
        }
    }

    /** 停止全部实例并关闭 VpnService。 */
    fun stopAll() {
        viewModelScope.launch {
            getApplication<Application>().stopService(
                Intent(getApplication(), AnTierVpnService::class.java)
            )
            _activeTunInstance.value = null
            val svc = _service.value
            if (svc != null) {
                withContext(Dispatchers.IO) { svc.retainNetworkInstance(null) }
            }
            hintsByName.clear()
            rpcHintsByName.clear()
            _instances.value = emptyList()
            refreshStatus()
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val svc = _service.value ?: run {
                _statusText.value = "service not connected"
                return@launch
            }
            val instancesJson = withContext(Dispatchers.IO) { svc.listInstances(50) }
            val infosJson = withContext(Dispatchers.IO) { svc.collectNetworkInfos(50) }
            _statusText.value = buildString {
                append("instances: ").append(instancesJson ?: "null").append('\n')
                append("infos: ").append(infosJson ?: "null")
            }

            val runningNames = instancesJson?.let(::parseListInstances).orEmpty()
            val infos = infosJson?.let(::parseAllNetworkInfos).orEmpty()

            // 外部实例没有本地 hints，通过 JSON-RPC 拉取配置，确认其 no-tun/SOCKS5 模式。
            rpcHintsByName.keys.retainAll(runningNames)
            val externalNames = runningNames.filter { it !in hintsByName && it !in rpcHintsByName }
            for (name in externalNames) {
                val hints = withContext(Dispatchers.IO) { fetchConfigHints(svc, name) }
                if (hints != null) rpcHintsByName[name] = hints
            }

            _instances.value = runningNames.map { name ->
                val local = hintsByName.containsKey(name)
                val hints = if (local) hintsByName[name] else rpcHintsByName[name]
                val info = infos[name]
                InstanceState(
                    name = name,
                    running = true,
                    ipv4 = info?.ipv4,
                    proxyCidrs = info?.proxyCidrs ?: emptyList(),
                    errorMsg = info?.errorMsg,
                    noTun = hints?.noTun ?: false,
                    socks5Endpoint = hints?.socks5Endpoint,
                    origin = if (local) InstanceOrigin.LOCAL else InstanceOrigin.EXTERNAL,
                    modeKnown = hints != null
                )
            }

            val active = _activeTunInstance.value
            if (active != null && active !in runningNames) {
                _activeTunInstance.value = null
                getApplication<Application>().stopService(
                    Intent(getApplication(), AnTierVpnService::class.java)
                )
            }
        }
    }

    fun startConfigServer(url: String, machineId: String) {
        viewModelScope.launch {
            val svc = _service.value ?: return@launch
            val callback = object : IConfigServerEventCallback.Stub() {
                override fun onEvent(eventJson: String?) {
                    _lastEvent.value = "cfg: ${eventJson ?: ""}"
                }
            }
            configServerCallback = callback
            val rc = withContext(Dispatchers.IO) {
                svc.startConfigServerClient(url, null, machineId, false, callback)
            }
            if (rc == 0) {
                _configServerConnected.value =
                    withContext(Dispatchers.IO) { svc.isConfigServerClientConnected() }
                _lastError.value = null
            } else {
                _lastError.value =
                    withContext(Dispatchers.IO) { svc.getLastError() } ?: "config server start failed"
            }
        }
    }

    fun stopConfigServer() {
        viewModelScope.launch {
            val svc = _service.value ?: return@launch
            withContext(Dispatchers.IO) { svc.stopConfigServerClient() }
            _configServerConnected.value =
                withContext(Dispatchers.IO) { svc.isConfigServerClientConnected() }
        }
    }

    /** 用户授权 VPN 后启动 VpnService，并记录它绑定到了哪个实例。 */
    fun onVpnGranted() {
        val request = _vpnPrepareRequest.value ?: return
        val instanceName = request.serviceIntent.getStringExtra(AnTierVpnService.EXTRA_INSTANCE_NAME)
        try {
            getApplication<Application>().startService(request.serviceIntent)
            if (instanceName != null) _activeTunInstance.value = instanceName
        } catch (e: Exception) {
            _lastError.value = "start vpn service failed: ${e.message}"
        }
    }

    fun onVpnDenied() {
        _lastError.value = "vpn permission denied，实例仍在内核中运行（无 TUN 数据面）"
    }

    fun consumeVpnPrepareRequest() {
        _vpnPrepareRequest.value = null
    }

    private fun requestVpn(name: String, info: NetworkInfo) {
        val intent = Intent(getApplication(), AnTierVpnService::class.java).apply {
            putExtra(AnTierVpnService.EXTRA_IPV4, info.ipv4)
            putStringArrayListExtra(
                AnTierVpnService.EXTRA_PROXY_CIDRS,
                ArrayList(info.proxyCidrs)
            )
            putExtra(AnTierVpnService.EXTRA_INSTANCE_NAME, name)
        }
        _vpnPrepareRequest.value = VpnPrepareRequest(intent)
    }

    private fun updateInstance(name: String, transform: (InstanceState) -> InstanceState) {
        _instances.value = _instances.value.map { if (it.name == name) transform(it) else it }
    }

    private suspend fun queryInstanceInfo(svc: IEasyTierService, name: String): NetworkInfo? {
        val json = svc.collectNetworkInfos(50) ?: return null
        return parseNetworkInfo(json, name)
    }

    private fun parseInstanceName(config: String): String? {
        return Regex("""inst_name\s*=\s*"([^"]+)"""").find(config)?.groupValues?.get(1)
    }

    /** listInstances 返回 {实例名: 实例ID}，取所有键。 */
    private fun parseListInstances(json: String): List<String> {
        return try {
            val root = JSONObject(json)
            val names = mutableListOf<String>()
            val keys = root.keys()
            while (keys.hasNext()) names.add(keys.next())
            names
        } catch (e: Exception) {
            Log.w(TAG, "parse list instances failed", e)
            emptyList()
        }
    }

    /** collectNetworkInfos 返回 map，解析所有实例的运行时信息。 */
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

    /**
     * 通过 JSON-RPC 拉取实例配置，提取 no-tun/SOCKS5 模式。
     * 对 FFI 暴露的 api.config.ConfigRpcService.get_config 有效，
     * 外部 AIDL 启动的实例同样适用。
     */
    private suspend fun fetchConfigHints(svc: IEasyTierService, name: String): ConfigHints? {
        val payload = JSONObject()
            .put("instance", JSONObject().put("instance_selector", JSONObject().put("name", name)))
            .toString()
        val response = svc.callJsonRpc(
            "api.config.ConfigRpcService",
            "get_config",
            null,
            payload
        ) ?: return null
        return try {
            val config = JSONObject(response).optJSONObject("config") ?: return null
            val noTun = config.optBoolean("no_tun", false)
            val useSmoltcp = config.optBoolean("use_smoltcp", false)
            val socks5Endpoint = if (config.optBoolean("enable_socks5", false)) {
                val port = config.optInt("socks5_port", -1)
                if (port > 0) "127.0.0.1:$port" else null
            } else {
                null
            }
            ConfigHints(noTun, useSmoltcp, socks5Endpoint)
        } catch (e: Exception) {
            Log.w(TAG, "parse config rpc response failed", e)
            null
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
