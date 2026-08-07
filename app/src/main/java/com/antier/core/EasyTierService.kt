package com.antier.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.easytier.jni.EasyTierJNI
import com.easytier.jni.ConfigServerEventCallback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AIDL 控制服务。
 *
 * 在进程内持有 EasyTier 内核（EasyTierJNI），并通过 [IEasyTierService]
 * 暴露给原生控件界面以及任何绑定本服务的第三方应用。
 *
 * TUN fd 的所有权：内核配置了 close_fd_on_drop(false)，不会替我们关闭 fd，
 * 因此服务端保存传入的 ParcelFileDescriptor 副本并在实例停止时负责关闭。
 */
class EasyTierService : Service() {

    companion object {
        private const val TAG = "EasyTierService"
        private const val ACTION = "com.antier.core.EasyTierService"
        private const val CHANNEL_ID = "antier_kernel"
        private const val NOTIFICATION_ID = 1357

        /** 供第三方应用构造绑定 Intent 使用。 */
        fun bindIntent(): Intent = Intent(ACTION).setPackage("com.antier")
    }

    /** 已附加到内核的 TUN fd，key 为实例名。 */
    private val tunFds = ConcurrentHashMap<String, ParcelFileDescriptor>()

    /** 状态事件监听器（跨进程 binder 对象）。 */
    private val statusListeners = CopyOnWriteArrayList<IEasyTierStatusListener>()

    private fun notifyEvent(event: String) {
        for (listener in statusListeners) {
            try {
                listener.onEvent(event)
            } catch (t: Throwable) {
                Log.w(TAG, "notify event failed", t)
            }
        }
    }

    /** 捕获 JNI 抛出的 RuntimeException（失败详情已写入内核错误缓存）。 */
    private inline fun jniOrMinusOne(block: () -> Int): Int {
        return try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "JNI call failed", t)
            -1
        }
    }

    private fun parseInstanceName(config: String): String? {
        return Regex("""inst_name\s*=\s*"([^"]+)"""").find(config)?.groupValues?.get(1)
    }

    private fun closeTunFds(keep: Set<String>?) {
        for (name in tunFds.keys) {
            if (keep == null || name !in keep) {
                tunFds.remove(name)?.close()
            }
        }
    }

    private val binder = object : IEasyTierService.Stub() {

        override fun parseConfig(config: String?): Int {
            if (config == null) return -1
            return jniOrMinusOne { EasyTierJNI.parseConfig(config) }
        }

        override fun runNetworkInstance(config: String?): Int {
            if (config == null) return -1
            val rc = jniOrMinusOne { EasyTierJNI.runNetworkInstance(config) }
            if (rc == 0) {
                val name = parseInstanceName(config)
                notifyEvent("""{"event":"instance_started","instance":"${name ?: ""}"}""")
            }
            return rc
        }

        override fun retainNetworkInstance(instanceNames: Array<out String>?): Int {
            val names = instanceNames?.let { Array(it.size) { i -> it[i] } }
            val rc = jniOrMinusOne { EasyTierJNI.retainNetworkInstance(names) }
            if (rc == 0) {
                closeTunFds(instanceNames?.toSet())
                notifyEvent("""{"event":"instances_retained","instances":${instanceNames?.contentToString() ?: "[]"}}""")
            }
            return rc
        }

        override fun setTunFd(instanceName: String?, tun: ParcelFileDescriptor?): Int {
            if (instanceName.isNullOrEmpty() || tun == null) return -1
            return try {
                val owned = tun.dup()
                val rc = EasyTierJNI.setTunFd(instanceName, owned.fd)
                if (rc == 0) {
                    tunFds[instanceName] = owned
                } else {
                    owned.close()
                }
                rc
            } catch (t: Throwable) {
                Log.e(TAG, "setTunFd failed", t)
                -1
            }
        }

        override fun listInstances(maxLength: Int): String? {
            return try {
                EasyTierJNI.listInstances(maxLength)
            } catch (t: Throwable) {
                Log.e(TAG, "listInstances failed", t)
                null
            }
        }

        override fun collectNetworkInfos(maxLength: Int): String? {
            return try {
                EasyTierJNI.collectNetworkInfos(maxLength)
            } catch (t: Throwable) {
                Log.e(TAG, "collectNetworkInfos failed", t)
                null
            }
        }

        override fun callJsonRpc(
            serviceName: String?,
            methodName: String?,
            domainName: String?,
            payloadJson: String?
        ): String? {
            if (serviceName == null || methodName == null || payloadJson == null) return null
            return try {
                EasyTierJNI.callJsonRpc(serviceName, methodName, domainName, payloadJson)
            } catch (t: Throwable) {
                Log.e(TAG, "callJsonRpc failed", t)
                null
            }
        }

        override fun getLastError(): String? {
            return try {
                EasyTierJNI.getLastError()
            } catch (t: Throwable) {
                t.message
            }
        }

        override fun startConfigServerClient(
            url: String?,
            hostname: String?,
            machineId: String?,
            secureMode: Boolean,
            callback: IConfigServerEventCallback?
        ): Int {
            if (url == null || machineId == null) return -1
            val jniCallback: ConfigServerEventCallback? = callback?.let { c ->
                ConfigServerEventCallback { event -> c.onEvent(event) }
            }
            val rc = jniOrMinusOne {
                EasyTierJNI.startConfigServerClient(
                    url,
                    hostname,
                    machineId,
                    secureMode,
                    jniCallback
                )
            }
            if (rc == 0) notifyEvent("""{"event":"config_server_started","url":"$url"}""")
            return rc
        }

        override fun stopConfigServerClient(): Int {
            val rc = jniOrMinusOne { EasyTierJNI.stopConfigServerClient() }
            if (rc == 0) notifyEvent("""{"event":"config_server_stopped"}""")
            return rc
        }

        override fun isConfigServerClientConnected(): Boolean {
            return try {
                EasyTierJNI.isConfigServerClientConnected()
            } catch (t: Throwable) {
                Log.e(TAG, "isConfigServerClientConnected failed", t)
                false
            }
        }

        override fun registerStatusListener(listener: IEasyTierStatusListener?) {
            if (listener != null) statusListeners.addIfAbsent(listener)
        }

        override fun unregisterStatusListener(listener: IEasyTierStatusListener?) {
            if (listener != null) statusListeners.remove(listener)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AnTier Kernel", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AnTier")
            .setContentText("EasyTier kernel running")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        closeTunFds(null)
        super.onDestroy()
    }
}
