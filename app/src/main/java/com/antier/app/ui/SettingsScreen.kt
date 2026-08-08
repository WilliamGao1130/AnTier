package com.antier.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 设置二级菜单目录：连接设置 / 显示设置 / 关于。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenConnection: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenAbout: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(
                listOf(
                    Triple("连接设置", "VPN 连接逻辑、冲突策略、包配置记录", 1),
                    Triple("显示设置", "主题：系统 / 浅色 / 深色", 2),
                    Triple("关于", "软件名称、版本、开源许可", 3)
                ),
                key = { it.third }
            ) { (title, subtitle, kind) ->
                val onClick = when (kind) {
                    1 -> onOpenConnection
                    2 -> onOpenDisplay
                    else -> onOpenAbout
                }
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = { Text(subtitle) },
                    leadingContent = {
                        Icon(
                            when (kind) {
                                1 -> Icons.Default.Lock
                                2 -> Icons.Default.Settings
                                else -> Icons.Default.Info
                            },
                            contentDescription = null
                        )
                    },
                    modifier = Modifier.clickable(onClick = onClick)
                )
                HorizontalDivider()
            }
        }
    }
}
