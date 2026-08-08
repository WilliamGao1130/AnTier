package com.antier.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button

/**
 * 主界面（目录页）。
 *
 * - 顶部标题栏：左侧 AnTier，右侧设置按钮。
 * - 底部右侧：圆形 + 新建网络；有任意网络运行时，其上方显示圆形 X 关闭全部。
 * - 主体：垂直滚动网络卡片列表；列表底部留白足够，使最后一个卡片可滚到
 *   悬浮按钮上方；滚动条位于最右缘，悬浮按钮内缩不遮挡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onCreateNetwork: () -> Unit,
    onOpenNetwork: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val networks by viewModel.networks.collectAsState()
    val lastError by viewModel.lastError.collectAsState()
    val anyRunning = networks.any { it.running }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(lastError) {
        lastError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeLastError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AnTier", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                modifier = Modifier.padding(end = 36.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (anyRunning) {
                    FloatingActionButton(
                        onClick = { viewModel.stopAllNetworks() },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "关闭所有网络")
                    }
                }
                FloatingActionButton(onClick = onCreateNetwork) {
                    Icon(Icons.Default.Add, contentDescription = "新建网络配置")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 232.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (networks.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(top = 96.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "还没有网络配置\n点右下角 + 新建一个网络",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                items(networks, key = { it.id ?: it.name }) { card ->
                    NetworkCardItem(
                        card = card,
                        onOpen = { card.id?.let(onOpenNetwork) },
                        onToggle = {
                            if (card.running) {
                                card.id?.let(viewModel::stopNetwork)
                                    ?: viewModel.stopNetworkByName(card.name)
                            } else {
                                card.id?.let(viewModel::startNetwork)
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 网络卡片：名称与按钮较大字号；VPN/NO-TUN、内部/外部、本机 IP 分行显示。 */
@Composable
private fun NetworkCardItem(
    card: NetworkCard,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    card.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Button(onClick = onToggle) {
                    Text(
                        if (card.running) "断开" else "连接",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            val modeText = buildString {
                append(if (card.noTun) "NO-TUN" else "VPN")
                card.socks5Port?.let { append(" (端口 $it)") }
            }
            Text(modeText, style = MaterialTheme.typography.bodyLarge)

            Text(
                when (card.origin) {
                    CardOrigin.INTERNAL -> "内部"
                    CardOrigin.EXTERNAL -> "外部"
                },
                style = MaterialTheme.typography.bodyLarge
            )

            Text(
                card.ipText,
                style = MaterialTheme.typography.bodyLarge,
                color = if (card.running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            card.errorMsg?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
