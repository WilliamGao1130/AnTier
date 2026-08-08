package com.antier.app.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.antier.core.PackageConnectionMode
import com.antier.core.PackageRecord

private data class AppEntry(val packageName: String, val label: String)

/** 包选择：点按某个包后选择三种连接逻辑之一或取消。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagePickerScreen(
    defaultMode: PackageConnectionMode,
    onBack: () -> Unit,
    onSelect: (PackageRecord) -> Unit
) {
    val context = LocalContext.current
    val apps = remember { loadAllApps(context) }
    var pending by remember { mutableStateOf<AppEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择包") },
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                vertical = 8.dp
            )
        ) {
            items(apps, key = { it.packageName }) { app ->
                ListItem(
                    headlineContent = { Text(app.label) },
                    supportingContent = { Text(app.packageName) },
                    modifier = Modifier.clickable { pending = app }
                )
            }
        }
    }

    pending?.let { app ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(app.label) },
            text = {
                Column {
                    Text(
                        "选择该包的连接逻辑：",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    PackageConnectionMode.entries.forEach { mode ->
                        Text(
                            mode.title(),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(
                                        PackageRecord(
                                            packageName = app.packageName,
                                            label = app.label,
                                            mode = mode
                                        )
                                    )
                                    pending = null
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                    Text(
                        "（默认：${defaultMode.title()}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("取消") }
            }
        )
    }
}

private fun loadAllApps(context: Context): List<AppEntry> {
    return runCatching {
        val pm = context.packageManager
        pm.getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .map { app ->
                val label = runCatching { app.loadLabel(pm).toString() }
                    .getOrDefault(app.packageName)
                AppEntry(app.packageName, label.ifBlank { app.packageName })
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
}
