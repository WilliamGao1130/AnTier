# AnTier

用**原生控件**（Jetpack Compose Material 3）控制 EasyTier 内核的 Android 工程，并把内核控制接口通过 **AIDL** 暴露给本应用和其他应用。

界面为多网络设计：

- **主目录页**：标题栏（AnTier / 设置）、垂直网络卡片列表（名称、VPN/NO-TUN 与端口、内部/外部、本机 IP/网段）、右下角悬浮 + 新建，有网络运行时其上方出现 X 关闭全部；列表底部留白保证最后一个卡片可滚到悬浮按钮上方。
- **网络编辑页**：返回 + 实时网络名，顶部连接/断开，全部选项垂直排列（点选项名切换描述；布尔=勾选框，文本=带提示输入框），底部“编辑 TOML”保存后展示行级差异（新增/删除/修改）。
- **设置目录**：连接设置（冲突策略：先行者优先/后来者优先；三种连接逻辑：使用虚拟网段关闭其他网络 / 使用虚拟网段提供其他网络 / 关闭虚拟网段提供其他网络；默认配置下拉；包配置记录与包选择）、显示设置（系统/浅色/深色主题）、关于（名称/版本/许可）。
- 系统返回（虚拟按键/手势）由页面栈统一弹栈；全页基于 Scaffold + WindowInsets，安全区（刘海/手势条）与键盘变化自动重绘。

连接逻辑说明：Android VpnService 只能按包允许/绕过，三种连接逻辑中“关闭虚拟网段，提供其他网络”的包绕过 VPN，其余进入 VPN；“提供其他网络”时会话附加 0.0.0.0/0 路由，否则仅路由虚拟网段与代理子网。

## 发布签名（保持包签名不变）

Android 覆盖安装要求新旧 APK 使用**同一把签名密钥**。CI 的 debug 包用的是 runner 临时生成的 debug 密钥（每次构建都不同），release 包默认不签名，因此直接分发时无法覆盖安装，只能卸载重装。

要固定签名，请一次性生成 keystore 并在 GitHub 配置 4 个 Actions Secrets：

```bash
# 本地生成（保存好此文件与口令，丢失后无法再发布同签名的更新）
keytool -genkeypair -v -keystore antier-release.jks \
  -alias antier -keyalg RSA -keysize 2048 -validity 10000
# macOS 取 base64；Linux 用 base64 -w0 antier-release.jks
base64 -i antier-release.jks
```

在仓库 Settings → Secrets and variables → Actions 中新增：

| Secret 名称 | 值 |
|---|---|
| `ANTHER_KEYSTORE_BASE64` | keystore 文件的 base64 文本 |
| `ANTHER_KEYSTORE_PASSWORD` | keystore 口令 |
| `ANTHER_KEY_ALIAS` | 别名（如上例 `antier`） |
| `ANTHER_KEY_PASSWORD` | 密钥口令 |

CI 检测到这些 Secret 后会用同一把密钥签名 debug 与 release APK（产物 `app-release.apk`）；未配置时保持原行为（debug 临时签名 / release 不签名）。

注意：如果设备上已安装的是旧密钥签名的 APK，第一次切换签名时仍需卸载一次；此后同密钥产出的所有 APK 均可直接覆盖安装。本地复现同一签名：

```bash
export ANTHER_KEYSTORE_FILE=/path/to/antier-release.jks \
       ANTHER_KEYSTORE_PASSWORD=... ANTHER_KEY_ALIAS=antier ANTHER_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

## VPN 与系统设置

应用在“系统设置 → 网络 → VPN”中可见的前提是**至少授权过一次 VPN 权限**（首次连接时系统弹窗）。AnTierVpnService 已声明标准的 `android.net.VpnService` intent-filter，授权后即可在系统 VPN 列表显示，并支持 Always-on。

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

### 4. GitHub Actions 自动构建（可选）

仓库提供了 [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml)：在 GitHub 上自动完成"构建 JNI 库 → 打包 APK"全流程，无需本地安装 Rust/NDK/protoc。

- 手动触发：Actions 页面选择 `Build APK` → `Run workflow`，可指定 ABI（默认 `arm64-v8a x86_64`）和 EasyTier 源码引用（默认 `main`）；
- 自动触发：push/PR 修改 `app/**`、`build-jni.sh`、`gradle/**` 或工作流本身时；
- 产物：`app-debug.apk` 与 `app-release-unsigned.apk` 以 artifact 形式上传。

工作流使用的环境与 EasyTier 官方 CI 一致：JDK 17、Android SDK/NDK 26.0.10792818、Rust 1.95、protoc 35.1。

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
