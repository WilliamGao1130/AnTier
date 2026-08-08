package com.antier.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.antier.core.PackageConnectionMode
import com.antier.core.PackageRecord
import com.antier.core.VpnConflictPolicy
import com.antier.core.VpnSettings

fun PackageConnectionMode.title(): String = when (this) {
    PackageConnectionMode.USE_VNET_CLOSE_OTHERS -> "使用虚拟网段，关闭其他网络"
    PackageConnectionMode.USE_VNET_PROVIDE_OTHERS -> "使用虚拟网段，提供其他网络"
    PackageConnectionMode.NO_VNET_PROVIDE_OTHERS -> "关闭虚拟网段，提供其他网络"
}

fun PackageConnectionMode.description(): String = when (this) {
    PackageConnectionMode.USE_VNET_CLOSE_OTHERS ->
        "该包流量进入 VPN，只访问虚拟网段；对应网络启动时按冲突策略关闭其他 VPN 网络。"
    PackageConnectionMode.USE_VNET_PROVIDE_OTHERS ->
        "该包流量进入 VPN，并允许经出口节点访问其他网络/互联网。"
    PackageConnectionMode.NO_VNET_PROVIDE_OTHERS ->
        "该包不使用虚拟网段，直接走原网络（绕过 VPN）。"
}

/**
 * 连接设置：
 * - 冲突策略（先行者优先/后来者优先）；
 * - 默认配置下拉（未记录的包使用哪种连接逻辑）；
 * - 包配置记录（增加记录 → 包选择 → 三种连接逻辑之一）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(
    settings: VpnSettings,
    onSettingsChange: (VpnSettings) -> Unit,
    onBack: () -> Unit,
    onPickPackage: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("连接设置") },
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionCard("VPN 冲突策略") {
                    Text(
                        "多个需要 VPN 的网络同时启动时，如何处理：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    RadioOption(
                        title = "先行者优先",
                        description = "先启动的 VPN 网络获胜，新启动的网络谦让（关闭自己）。",
                        selected = settings.conflictPolicy == VpnConflictPolicy.FIRST_COMES_FIRST,
                        onClick = {
                            onSettingsChange(
                                settings.copy(conflictPolicy = VpnConflictPolicy.FIRST_COMES_FIRST)
                            )
                        }
                    )
                    RadioOption(
                        title = "后来者优先",
                        description = "新启动的 VPN 网络挤占（关闭先启动的 VPN 网络）。",
                        selected = settings.conflictPolicy == VpnConflictPolicy.LAST_COMES_FIRST,
                        onClick = {
                            onSettingsChange(
                                settings.copy(conflictPolicy = VpnConflictPolicy.LAST_COMES_FIRST)
                            )
                        }
                    )
                }
            }

            item {
                SectionCard("默认配置") {
                    Text(
                        "未单独记录的包使用哪种连接逻辑：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    var menuOpen by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = menuOpen,
                        onExpandedChange = { menuOpen = it }
                    ) {
                        OutlinedTextField(
                            value = settings.defaultPackageMode.title(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("默认配置") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            PackageConnectionMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.title()) },
                                    onClick = {
                                        onSettingsChange(settings.copy(defaultPackageMode = mode))
                                        menuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        settings.defaultPackageMode.description(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            item {
                SectionCard("包配置记录") {
                    Text(
                        "三种连接逻辑：\n" +
                            PackageConnectionMode.entries.joinToString("\n") { it.title() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider()
                    if (settings.packageRecords.isEmpty()) {
                        Text(
                            "暂无记录，所有包按上面的默认配置处理。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        settings.packageRecords.forEach { record ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(record.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${record.packageName} · ${record.mode.title()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        onSettingsChange(
                                            settings.copy(
                                                packageRecords = settings.packageRecords
                                                    .filterNot { it.packageName == record.packageName }
                                            )
                                        )
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除记录")
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = onPickPackage,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("增加记录", Modifier.padding(start = 8.dp))
                    }
                }
            }

            item {
                Text(
                    "说明：Android VpnService 只能按包允许/绕过，A/B 的路由差异由会话级路由决定，无法按包分开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun RadioOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
