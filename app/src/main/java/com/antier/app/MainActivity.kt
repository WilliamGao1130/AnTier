package com.antier.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.antier.app.ui.AnTierApp
import com.antier.app.ui.MainViewModel
import com.antier.app.ui.theme.AnTierTheme
import com.antier.app.ui.theme.ThemeStore
import com.antier.core.VpnSettingsStore

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                viewModel.onVpnGranted()
            } else {
                viewModel.onVpnDenied()
            }
            // 授权结果处理完后才消费请求；不能提前消费，否则 onVpnGranted 拿不到 Intent。
            viewModel.consumeVpnPrepareRequest()
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.bind(applicationContext)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            var themeMode by remember { mutableStateOf(ThemeStore.load(this)) }
            var vpnSettings by remember { mutableStateOf(VpnSettingsStore.load(this)) }

            AnTierTheme(themeMode = themeMode) {
                LaunchedEffect(Unit) {
                    viewModel.vpnPrepareRequest.collect { request ->
                        if (request != null) {
                            val prepareIntent = VpnService.prepare(this@MainActivity)
                            if (prepareIntent == null) {
                                viewModel.onVpnGranted()
                                viewModel.consumeVpnPrepareRequest()
                            } else {
                                vpnLauncher.launch(prepareIntent)
                                // 等待授权结果返回后再消费，避免 onVpnGranted 丢失请求。
                            }
                        }
                    }
                }
                AnTierApp(
                    viewModel = viewModel,
                    themeMode = themeMode,
                    onThemeModeChange = {
                        themeMode = it
                        ThemeStore.save(this, it)
                    },
                    vpnSettings = vpnSettings,
                    onVpnSettingsChange = {
                        vpnSettings = it
                        VpnSettingsStore.save(this, it)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        viewModel.unbind(applicationContext)
        super.onDestroy()
    }
}
