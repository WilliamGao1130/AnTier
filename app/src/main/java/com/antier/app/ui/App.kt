package com.antier.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.antier.core.PackageRecord
import com.antier.core.VpnSettings
import com.antier.app.ui.theme.ThemeMode
import kotlinx.coroutines.delay

/** 页面栈。 */
sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object ConnectionSettings : Screen
    data object DisplaySettings : Screen
    data object About : Screen
    data object PackagePicker : Screen
    data class NetworkEdit(val id: String) : Screen
}

/**
 * 应用根导航：维护一个页面栈，系统返回（虚拟按键/手势）由 BackHandler 统一
 * 弹栈；页面内容全部基于 Scaffold + WindowInsets，安全区/键盘变化自动重绘。
 */
@Composable
fun AnTierApp(
    viewModel: MainViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    vpnSettings: VpnSettings,
    onVpnSettingsChange: (VpnSettings) -> Unit
) {
    var backStack by remember {
        mutableStateOf(listOf<Screen>(Screen.Home))
    }

    fun push(screen: Screen) {
        backStack = backStack + screen
    }

    fun pop() {
        if (backStack.size > 1) {
            backStack = backStack.dropLast(1)
        }
    }

    // 系统返回键 / 手势返回：统一弹栈，Home 页不消费。
    BackHandler(enabled = backStack.size > 1) { pop() }

    // 周期性刷新运行状态（外部 AIDL 实例、IP 变化等）。
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshStatus()
            delay(3000)
        }
    }

    when (val current = backStack.last()) {
        Screen.Home -> HomeScreen(
            viewModel = viewModel,
            onCreateNetwork = {
                val id = viewModel.createNetwork()
                push(Screen.NetworkEdit(id))
            },
            onOpenNetwork = { id -> push(Screen.NetworkEdit(id)) },
            onOpenSettings = { push(Screen.Settings) }
        )

        Screen.Settings -> SettingsScreen(
            onBack = ::pop,
            onOpenConnection = { push(Screen.ConnectionSettings) },
            onOpenDisplay = { push(Screen.DisplaySettings) },
            onOpenAbout = { push(Screen.About) }
        )

        Screen.ConnectionSettings -> ConnectionSettingsScreen(
            settings = vpnSettings,
            onSettingsChange = onVpnSettingsChange,
            onBack = ::pop,
            onPickPackage = { push(Screen.PackagePicker) }
        )

        Screen.DisplaySettings -> DisplaySettingsScreen(
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            onBack = ::pop
        )

        Screen.About -> AboutScreen(onBack = ::pop)

        Screen.PackagePicker -> PackagePickerScreen(
            defaultMode = vpnSettings.defaultPackageMode,
            onBack = ::pop,
            onSelect = { record: PackageRecord ->
                onVpnSettingsChange(
                    vpnSettings.copy(
                        packageRecords = vpnSettings.packageRecords
                            .filterNot { it.packageName == record.packageName } + record
                    )
                )
                pop()
            }
        )

        is Screen.NetworkEdit -> NetworkEditScreen(
            networkId = current.id,
            viewModel = viewModel,
            onBack = ::pop
        )
    }
}
