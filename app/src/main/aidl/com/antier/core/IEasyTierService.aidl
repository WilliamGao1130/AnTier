package com.antier.core;

import android.os.ParcelFileDescriptor;
import com.antier.core.IEasyTierStatusListener;

/**
 * AIDL 暴露的 EasyTier 内核控制接口。
 *
 * 每个方法都对应 easytier-android-jni 中 EasyTierJNI 的一个原生方法。
 * 返回 0 表示成功，-1 表示失败（失败详情通过 getLastError() 获取）。
 */
interface IEasyTierService {

    /** 校验 TOML 配置，不启动实例。 */
    int parseConfig(String config);

    /** 用 TOML 配置启动一个 EasyTier 内核实例。 */
    int runNetworkInstance(String config);

    /**
     * 保留指定的实例并停止其余实例。
     * 传入 null 或空数组表示停止所有实例。
     */
    int retainNetworkInstance(in String[] instanceNames);

    /**
     * 将 Android VpnService 创建的 TUN 文件描述符交给指定实例。
     * 服务端会持有 fd 的副本，并在实例停止时关闭。
     */
    int setTunFd(String instanceName, in ParcelFileDescriptor tun);

    /** 收集运行实例信息，返回 NetworkInstanceRunningInfoMap 的 JSON 字符串。 */
    String collectNetworkInfos(int maxLength);

    /** 获取最后一次错误的详情。 */
    String getLastError();

    /** 注册状态事件监听器。 */
    void registerStatusListener(IEasyTierStatusListener listener);

    /** 注销状态事件监听器。 */
    void unregisterStatusListener(IEasyTierStatusListener listener);
}
