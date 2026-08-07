# AnTier

用**原生控件**（Jetpack Compose）控制 EasyTier 内核的 Android 示例工程，并把内核控制接口通过 **AIDL** 暴露给本应用和其他应用。

## 架构

```text
┌─────────────────────────────────────────────────────────────┐
│  AnTier App (com.antier)                                     │
│                                                              │
│  Compose 原生界面 (MainActivity / HomeScreen / MainViewModel) │
│        │ 绑定 (bindService)                                  │
│        ▼                                                     │
│  EasyTierService (前台服务, exported)                         │
│        │ IEasyTierService (AIDL)                             │
│        ▼                                                     │
│  EasyTierJNI (com.easytier.jni)                              │
│        │ JNI                                                  │
│        ▼                                                     │
│  libeasytier_android_jni.so ──► easytier-ffi ──► easytier 内核 │
│                                                              │
│  AnTierVpnService (VpnService) ──建立 TUN──► setTunFd(fd)    │
└─────────────────────────────────────────────────────────────┘
              ▲ 外部应用也可通过 AIDL 绑定 EasyTierService
```

与官方 GUI（Tauri + WebView）不同，这里 UI 是原生 Compose，内核仍以库的形式进程内运行；`EasyTierService` 用 AIDL 把 `EasyTierJNI` 的每个方法都暴露出去，`setTunFd` 通过 `ParcelFileDescriptor` 跨进程传递 TUN fd。

## 目录结构

```text
AnTier/
├── app/src/main/
│   ├── aidl/com/antier/core/    # IEasyTierService / IEasyTierStatusListener / IConfigServerEventCallback
│   ├── java/com/antier/
│   │   ├── app/                 # MainActivity + Compose 界面
│   │   └── core/                # EasyTierService (AIDL 实现) / AnTierVpnService
│   ├── java/com/easytier/jni/   # EasyTierJNI.kt（官方原样拷贝，勿改包名）
│   └── jniLibs/<abi>/           # 由 build-jni.sh 生成的 .so
├── build-jni.sh                 # 从 EasyTier main 分支构建 JNI 库
└── gradle/ ...
```

## 构建步骤

### 1. 构建 JNI 库（必须，官方不提供下载）

官方 Release 只发布 GUI APK，没有独立的 Android 内核库；`easytier-android-jni` 只在 EasyTier `main` 分支（v2.6.4 之后，尚未发版）。因此需要从源码构建：

前置：Rust 1.95（rustup）、protoc、Android NDK（cargo-ndk 自动检测 `ANDROID_NDK_ROOT` / `ANDROID_HOME`）。

```bash
./build-jni.sh                                      # 默认 arm64-v8a
ABIS="arm64-v8a x86_64" ./build-jni.sh              # 多 ABI
```

脚本会克隆 EasyTier main 到 `.third_party/easytier`，构建 `easytier-ffi` 和 `easytier-android-jni`，把 `libeasytier_android_jni.so` 拷贝到 `app/src/main/jniLibs/<abi>/`。

### 2. 构建 APK

```bash
./gradlew assembleDebug
```

如未设置 Android SDK 路径，先建 `local.properties`：

```properties
sdk.dir=/path/to/Android/sdk
```

> 注意：若工程位于 **exFAT/FAT 卷**（例如本工作区所在的 `/Volumes/SSDDATA`），Gradle 的文件锁会超时失败，需要把缓存目录挪到 APFS 卷：
>
> ```bash
> ./gradlew --project-cache-dir /private/tmp/antier-gradle-cache :app:assembleDebug
> ```

### 3. 运行

APK 未包含内核库时应用会在 `System.loadLibrary("easytier_android_jni")` 处崩溃，必须先执行 `./build-jni.sh` 生成 `.so`。

## AIDL 接口（IEasyTierService）

| 方法 | 说明 |
|---|---|
| `runNetworkInstance(config)` | 用 TOML 启动内核实例 |
| `retainNetworkInstance(names)` | 保留指定实例，`null`/空数组 = 停止全部 |
| `setTunFd(name, ParcelFileDescriptor)` | 把 VpnService 的 TUN fd 交给实例 |
| `parseConfig(config)` | 校验 TOML |
| `listInstances(max)` / `collectNetworkInfos(max)` | 实例列表 / 状态（JSON） |
| `callJsonRpc(service, method, domain, payload)` | 调用任意暴露的 RPC（peer/route/logger 等） |
| `getLastError()` | 最后一次失败详情 |
| `start/stopConfigServerClient(...)` / `isConfigServerClientConnected()` | 远程配置托管 |
| `register/unregisterStatusListener(listener)` | 状态事件（oneway） |

返回值约定：`int` 方法 `0` 成功、`-1` 失败；失败后用 `getLastError()` 取详情。

## 外部应用通过 AIDL 控制内核

```kotlin
val intent = Intent("com.antier.core.EasyTierService").setPackage("com.antier")
bindService(intent, object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        val svc = IEasyTierService.Stub.asInterface(binder)
        if (svc.runNetworkInstance(toml) == 0) {
            // 获取状态
            val info = svc.collectNetworkInfos(10)
            // 把自己的 VpnService 的 TUN fd 交给内核（需先建立自己的 VpnService）
            svc.setTunFd("my_instance", vpnParcelFileDescriptor)
        } else {
            Log.e("X", svc.getLastError())
        }
    }
    override fun onServiceDisconnected(name: ComponentName?) {}
}, Context.BIND_AUTO_CREATE)
```

需要把 `IEasyTierService.aidl`、`IEasyTierStatusListener.aidl`、`IConfigServerEventCallback.aidl` 复制到外部工程的 `src/main/aidl/com/antier/core/` 下。

## 无 TUN 模式（SOCKS5，不开 VPN）

EasyTier 内核支持 `no_tun` 模式：不创建 TUN 虚拟网卡，用用户态 smoltcp 协议栈承载数据面；主动访问对端时通过内核内置的 SOCKS5 服务。Android 目标下内核始终使用 smoltcp 栈，因此该模式在 Android 上可以完全绕开 `VpnService`（无授权弹窗、无 VPN 图标）。

在 AnTier 的 TOML 配置里按下面写法启用（界面会自动识别 `no_tun`，跳过 VPN 流程并显示 SOCKS5 端点）：

```toml
socks5_proxy = "socks5://127.0.0.1:12333"

[flags]
no_tun = true
use_smoltcp = true
```

启用后：

- 应用**不会**启动 `AnTierVpnService`，不再请求 VPN 授权；
- 应用内网络层用 SOCKS5 客户端指向 `127.0.0.1:12333`，即可通过组网访问对端虚拟 IP；
- 其他应用不受影响（只有主动使用该 SOCKS5 端点的应用才能访问虚拟网络）。

注意：

- `socks5_proxy` 是顶层键，必须放在 `[flags]` 表之前；
- 若只配 `no_tun = true` 而未配置 `socks5_proxy`，实例仍会入网、可被其他节点访问，但本机无法主动发起连接；
- 内核能力与 JNI 构建的 feature 相关：`easytier` 默认 feature 已包含 `socks5`/`smoltcp`，`build-jni.sh` 使用默认 feature 编译即可；
- 若需要"应用直接调用内核收发字节"的原生 API（不走 SOCKS5），需要在 `easytier-ffi` 的 `ffi-dataplane` 之上扩展，属于另一项工作。

## 多实例管理

界面支持在一个内核进程里同时运行多个网络实例：每次用不同的 `inst_name` 和配置点"启动内核"即可加入实例列表。列表展示每个实例的模式（TUN / no-tun）、虚拟 IP、SOCKS5 端点，并支持按实例停止或一键停止全部。

规则与限制：

- **同一时间最多一个 TUN 实例**（Android 同时只允许一个活跃 VPN 接口）；启动第二个 TUN 实例会被拒绝并提示先停止现有 TUN 实例。
- 多个 no-tun 实例可以共存，但各自的 `socks5_proxy` 端口必须不同；TUN 实例与 no-tun 实例可以混合运行。
- 按实例停止使用 `retainNetworkInstance(其余实例名)`，只停止目标实例；若它持有 VpnService 的 TUN，会同时关闭 VPN。
- 停止全部 = `retainNetworkInstance(null)` + 关闭 VpnService。
- 外部应用通过 AIDL 启动的实例也会出现在列表里，并用 `○ 外部 AIDL` 与界面启动的 `● 本应用` 区分；
- 外部实例的模式（TUN / no-tun / SOCKS5）通过 JSON-RPC `api.config.ConfigRpcService.get_config` 拉取并展示；RPC 不可用时显示"模式未知"。

## 关键注意事项

- **TUN fd 所有权**：内核配置了 `close_fd_on_drop(false)`，不会关闭传入的 fd。服务端保存 `ParcelFileDescriptor.dup()` 的副本，在实例停止/服务销毁时关闭；客户端自己的 VpnService 也要在销毁时关闭自己的 fd。
- **VPN 授权**：`VpnService.prepare()` 的授权按应用隔离。AnTier 界面启动 `AnTierVpnService` 前会先请求授权；外部应用要自己先 `VpnService.prepare()` 并建立自己的 TUN，再把 fd 通过 AIDL 传入。
- **实例名**：`setTunFd` 的实例名必须与 TOML 中 `inst_name` 一致，且实例已运行。
- **版本**：JNI 模块仅存在于 EasyTier `main` 分支（≥2.6.4 未发布代码），不要用 v2.6.4 tag 源码构建。
- **通知权限**：Android 13+ 需授予通知权限才能显示前台服务通知，但服务仍可运行。
- **AIDL 配置**：`app/build.gradle.kts` 中必须显式开启 `buildFeatures { aidl = true }`（新版本 AGP 默认不生成 AIDL 任务）；同包引用的 AIDL 接口也需要显式 `import`。

## 相关链接

- EasyTier: https://github.com/EasyTier/EasyTier
- `easytier-contrib/easytier-android-jni`（JNI 层）
- `easytier-contrib/easytier-ffi`（C ABI 层，含可选数据面）
