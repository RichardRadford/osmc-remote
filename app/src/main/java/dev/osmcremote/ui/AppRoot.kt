package dev.osmcremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.osmcremote.AppViewModel
import dev.osmcremote.ConnState
import dev.osmcremote.Status

enum class MainTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Dashboard),
    Updates("Updates", Icons.Filled.SystemUpdate),
    Config("Config", Icons.Filled.Tune),
    Services("Services", Icons.Filled.SettingsApplications),
    Tools("Tools", Icons.Filled.Build),
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val conn by vm.conn.collectAsState()
    when (val c = conn) {
        is ConnState.Connected -> MainScaffold(vm, c.status)
        else -> ConnectScreen(vm, c)
    }

    vm.hostKeyPrompt?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.dismissHostKey() },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Device identity changed") },
            text = {
                Column {
                    Text(
                        "${p.config.host} presented a different SSH host key from last time. " +
                            "That's normal after reinstalling OSMC, but it could also mean " +
                            "someone is intercepting the connection."
                    )
                    Text(
                        "\nNew:   ${p.fingerprint}\nSaved: ${p.expected}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.acceptHostKey() }) { Text("Trust new key") } },
            dismissButton = { TextButton(onClick = { vm.dismissHostKey() }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MainScaffold(vm: AppViewModel, status: Status) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Home) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(status.host, style = MaterialTheme.typography.titleLarge)
                        Text(
                            status.model,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.disconnect() }) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Disconnect")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            if (t == MainTab.Updates && vm.upgradeRunning) {
                                BadgedBox(badge = { Badge() }) { Icon(t.icon, contentDescription = null) }
                            } else {
                                Icon(t.icon, contentDescription = null)
                            }
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (tab) {
                MainTab.Home -> DashboardScreen(vm, status)
                MainTab.Updates -> UpdatesScreen(vm)
                MainTab.Config -> ConfigScreen(vm, status)
                MainTab.Services -> ServicesScreen(vm)
                MainTab.Tools -> ToolsScreen(vm)
            }
        }
    }
}
