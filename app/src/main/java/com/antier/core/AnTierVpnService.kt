package com.antier.core

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.easytier.jni.EasyTierJNI
import kotlin.concurrent.thread

/**
 * 本应用的 VpnService：创建 TUN 接口，把 fd 交给已运行的 EasyTier 实例。
 *
 * 由界面在拿到 DHCP 分配的虚拟 IP 与代理路由后启动；
 * 第三方应用则通过 AIDL [IEasyTierService.setTunFd] 传入自己的 TUN fd。
 */
class AnTierVpnService : VpnService() {

    companion object {
        private const val TAG = "AnTierVpnService"
        const val EXTRA_IPV4 = "ipv4_address"
        const val EXTRA_PROXY_CIDRS = "proxy_cidrs"
        const val EXTRA_INSTANCE_NAME = "instance_name"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ipv4 = intent?.getStringExtra(EXTRA_IPV4)
        val proxyCidrs = intent?.getStringArrayListExtra(EXTRA_PROXY_CIDRS) ?: arrayListOf()
        val instanceName = intent?.getStringExtra(EXTRA_INSTANCE_NAME)

        if (ipv4 == null || instanceName == null) {
            Log.e(TAG, "missing extras: ipv4=$ipv4, instance=$instanceName")
            stopSelf()
            return START_NOT_STICKY
        }

        thread {
            try {
                setupVpnInterface(ipv4, proxyCidrs, instanceName)
            } catch (t: Throwable) {
                Log.e(TAG, "VPN setup failed", t)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun setupVpnInterface(ipv4: String, proxyCidrs: List<String>, instanceName: String) {
        val (ip, networkLength) = parseAddress(ipv4, 24)
        val settings = VpnSettingsStore.load(this)
        val builder = Builder()
            .setSession("AnTier VPN")
            .setMtu(1380)
            .addAddress(ip, networkLength)
            .addDnsServer("223.5.5.5")
            .addDnsServer("114.114.114.114")

        applyAppVisibility(builder, settings)
        applyRouting(builder, settings, proxyCidrs)

        val pfd = builder.establish()
        if (pfd == null) {
            Log.e(TAG, "failed to establish vpn interface")
            return
        }
        vpnInterface = pfd

        val rc = EasyTierJNI.setTunFd(instanceName, pfd.fd)
        if (rc == 0) {
            Log.i(TAG, "tun fd attached: ${pfd.fd}")
        } else {
            Log.e(TAG, "setTunFd failed: $rc, ${EasyTierJNI.getLastError()}")
            stopSelf()
            return
        }

        isRunning = true
        while (isRunning && vpnInterface != null) {
            Thread.sleep(1000)
        }
        cleanup()
    }

    /**
     * 应用可见性：决定哪些应用走 VPN。
     *
     * AnTier 自身始终绕过 VPN（不进入 TUN），否则 AIDL 与内核的
     * peer socket 会被路由进虚拟网，造成环路。
     */
    private fun applyAppVisibility(builder: Builder, settings: VpnSettings) {
        when (settings.appMode) {
            VpnAppMode.ALL -> {
                builder.addDisallowedApplication(packageName)
            }
            VpnAppMode.DENY_LIST -> {
                builder.addDisallowedApplication(packageName)
                settings.deniedPackages.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                        .onFailure { Log.w(TAG, "deny $pkg failed", it) }
                }
            }
            VpnAppMode.ALLOW_LIST -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // 白名单：只允许列出的应用走 VPN；AnTier 自身不在列表里，自动走原网络。
                    settings.allowedPackages
                        .filter { it != packageName }
                        .forEach { pkg ->
                            runCatching { builder.addAllowedApplication(pkg) }
                                .onFailure { Log.w(TAG, "allow $pkg failed", it) }
                        }
                } else {
                    // Android 14 以下没有白名单 API，退回"所有应用可用"。
                    Log.w(TAG, "allow-list mode requires API 34+, falling back to ALL")
                    builder.addDisallowedApplication(packageName)
                }
            }
        }
    }

    /** 路由模式：仅虚拟网络，或全流量（虚拟网络 + 互联网）。 */
    private fun applyRouting(
        builder: Builder,
        settings: VpnSettings,
        proxyCidrs: List<String>
    ) {
        when (settings.routingMode) {
            VpnRoutingMode.VIRTUAL_ONLY -> {
                for (cidr in proxyCidrs) {
                    try {
                        val (routeIp, routeLength) = parseAddress(cidr, -1)
                        builder.addRoute(routeIp, routeLength)
                        Log.d(TAG, "add route: $routeIp/$routeLength")
                    } catch (e: Exception) {
                        Log.w(TAG, "invalid cidr: $cidr", e)
                    }
                }
            }
            VpnRoutingMode.FULL_TUNNEL -> {
                builder.addRoute("0.0.0.0", 0)
                Log.d(TAG, "full tunnel: 0.0.0.0/0")
            }
        }
    }

    private fun parseAddress(value: String, defaultPrefix: Int): Pair<String, Int> {
        val parts = value.split("/")
        return when {
            parts.size == 2 -> Pair(parts[0], parts[1].toInt())
            defaultPrefix >= 0 -> Pair(parts[0], defaultPrefix)
            else -> throw IllegalArgumentException("invalid cidr: $value")
        }
    }

    private fun cleanup() {
        isRunning = false
        vpnInterface?.close()
        vpnInterface = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
