package com.antier.core

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.easytier.jni.EasyTierJNI
import kotlin.concurrent.thread

/**
 * 本应用的 VpnService：创建 TUN 接口，把 fd 交给已运行的 EasyTier 实例。
 *
 * 包级连接逻辑（VpnSettings）：
 * - 本应用自身始终绕过 VPN（避免 AIDL/内核 socket 环路）。
 * - 连接逻辑为“关闭虚拟网段，提供其他网络”的包：绕过 VPN。
 * - 其余包（使用虚拟网段）：进入 VPN。
 * - 只要存在“使用虚拟网段，提供其他网络”的包或默认配置，就附加 0.0.0.0/0
 *   路由（其它网络经出口节点访问）；否则仅路由虚拟网段与代理子网。
 */
class AnTierVpnService : VpnService() {

    companion object {
        private const val TAG = "AnTierVpnService"
        const val EXTRA_IPV4 = "ipv4_address"
        const val EXTRA_IPV6 = "ipv6_address"
        const val EXTRA_PROXY_CIDRS = "proxy_cidrs"
        const val EXTRA_INSTANCE_NAME = "instance_name"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ipv4 = intent?.getStringExtra(EXTRA_IPV4)
        val ipv6 = intent?.getStringExtra(EXTRA_IPV6)
        val proxyCidrs = intent?.getStringArrayListExtra(EXTRA_PROXY_CIDRS) ?: arrayListOf()
        val instanceName = intent?.getStringExtra(EXTRA_INSTANCE_NAME)

        if ((ipv4 == null && ipv6 == null) || instanceName == null) {
            Log.e(TAG, "missing extras: ipv4=$ipv4, ipv6=$ipv6, instance=$instanceName")
            stopSelf()
            return START_NOT_STICKY
        }

        thread {
            try {
                setupVpnInterface(ipv4, ipv6, proxyCidrs, instanceName)
            } catch (t: Throwable) {
                Log.e(TAG, "VPN setup failed", t)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun setupVpnInterface(
        ipv4: String?,
        ipv6: String?,
        proxyCidrs: List<String>,
        instanceName: String
    ) {
        val settings = VpnSettingsStore.load(this)
        val builder = Builder()
            .setSession("AnTier VPN")
            .setMtu(1380)
            .addDnsServer("223.5.5.5")
            .addDnsServer("114.114.114.114")

        if (!ipv4.isNullOrBlank()) {
            val (ip, networkLength) = parseAddress(ipv4, 24)
            builder.addAddress(ip, networkLength)
        }
        if (!ipv6.isNullOrBlank()) {
            val (ip, networkLength) = parseAddress(ipv6, 64)
            builder.addAddress(ip, networkLength)
        }

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

    private fun applyAppVisibility(builder: Builder, settings: VpnSettings) {
        // 本应用自身始终绕过 VPN。
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { Log.w(TAG, "disallow self failed", it) }

        val records = settings.packageRecords.associateBy { it.packageName }
        fun modeOf(pkg: String) = records[pkg]?.mode ?: settings.defaultPackageMode

        val allPackages = runCatching {
            packageManager.getInstalledApplications(0).map { it.packageName }
        }.getOrDefault(emptyList())

        allPackages
            .filter { it != packageName && modeOf(it) == PackageConnectionMode.NO_VNET_PROVIDE_OTHERS }
            .forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onFailure { Log.w(TAG, "disallow $pkg failed", it) }
            }
    }

    private fun applyRouting(
        builder: Builder,
        settings: VpnSettings,
        proxyCidrs: List<String>
    ) {
        val providesOthers =
            settings.packageRecords.any { it.mode == PackageConnectionMode.USE_VNET_PROVIDE_OTHERS } ||
                settings.defaultPackageMode == PackageConnectionMode.USE_VNET_PROVIDE_OTHERS

        if (providesOthers) {
            builder.addRoute("0.0.0.0", 0)
            Log.d(TAG, "provide other networks: route 0.0.0.0/0")
        } else {
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
