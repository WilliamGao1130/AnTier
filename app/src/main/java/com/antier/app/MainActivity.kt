package com.antier.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.antier.app.ui.HomeScreen
import com.antier.app.ui.MainViewModel
import com.antier.app.ui.theme.AnTierTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                viewModel.onVpnGranted()
            } else {
                viewModel.onVpnDenied()
            }
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.bind(applicationContext)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AnTierTheme {
                LaunchedEffect(Unit) {
                    viewModel.vpnPrepareRequest.collect { request ->
                        if (request != null) {
                            val prepareIntent = VpnService.prepare(this@MainActivity)
                            if (prepareIntent == null) {
                                viewModel.onVpnGranted()
                            } else {
                                vpnLauncher.launch(prepareIntent)
                            }
                            viewModel.consumeVpnPrepareRequest()
                        }
                    }
                }
                HomeScreen(viewModel = viewModel)
            }
        }
    }

    override fun onDestroy() {
        viewModel.unbind(applicationContext)
        super.onDestroy()
    }
}
