package dev.osmcremote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.osmcremote.AppViewModel
import dev.osmcremote.PowerAction
import dev.osmcremote.UpdateCheck

@Composable
fun UpdatesScreen(vm: AppViewModel) {
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateCheck?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    fun check() {
        if (checking) return
        vm.launchOp {
            checking = true
            try {
                result = vm.osmc.checkUpdates()
            } finally {
                checking = false
            }
        }
    }

    LaunchedEffect(Unit) { vm.reattachUpgrade() }
    LaunchedEffect(vm.upgradeExitCode) { if (vm.upgradeExitCode == 0) check() }

    val showProgress = vm.upgradeRunning || vm.upgradeLog.isNotEmpty() || vm.upgradeExitCode != null

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = spaced12,
    ) {
        item {
            SectionCard("OSMC updates", Icons.Filled.SystemUpdate) {
                Text(
                    "Checks the OSMC package repositories and installs updates — the same as " +
                        "My OSMC → Updates, or `apt update && apt full-upgrade`.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = spaced8) {
                    FilledTonalButton(onClick = { check() }, enabled = !checking && !vm.upgradeRunning) {
                        if (checking) SmallSpinner() else Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (checking) "Checking…" else "Check now")
                    }
                    Button(
                        onClick = {
                            confirm = Confirm(
                                "Install updates?",
                                "Kodi may restart while packages are installed. The update keeps running " +
                                    "on the device even if you close this app — come back here to see progress.",
                                "Install",
                            ) { vm.startUpgrade() }
                        },
                        enabled = !vm.upgradeRunning && !checking,
                    ) {
                        Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Install updates")
                    }
                }
            }
        }

        if (showProgress) {
            item { ProgressCard(vm) { confirm = it } }
        }

        result?.let { r ->
            item {
                Column {
                    Text(
                        when (r.packages.size) {
                            0 -> "You're up to date"
                            1 -> "1 update available"
                            else -> "${r.packages.size} updates available"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    r.warnings.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            items(r.packages) { p ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Inventory2, contentDescription = null) },
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text("${p.oldVersion}  →  ${p.newVersion}") },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
            }
        }
    }

    confirm?.let { ConfirmDialog(it) { confirm = null } }
}

@Composable
private fun ProgressCard(vm: AppViewModel, onConfirm: (Confirm) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.upgradeLog.size) {
        if (vm.upgradeLog.isNotEmpty()) listState.scrollToItem(vm.upgradeLog.lastIndex)
    }

    SectionCard("Update progress", Icons.Filled.Code) {
        val code = vm.upgradeExitCode
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                vm.upgradeRunning && code == null -> {
                    SmallSpinner()
                    Spacer(Modifier.width(10.dp))
                    Text("Installing… you can leave this screen.", style = MaterialTheme.typography.bodyMedium)
                }
                code == 0 -> StatusPill("Finished successfully", Good)
                code != null -> StatusPill("Failed (exit code $code) — see the log", Bad)
                else -> StatusPill("Stopped following the log", Warn)
            }
        }
        if (vm.upgradeNeedsReboot && code != null) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("A reboot is needed to finish.", Modifier.weight(1f))
                    Button(onClick = {
                        onConfirm(Confirm("Reboot now?", "You'll be disconnected until it's back up.", "Reboot") {
                            vm.power(PowerAction.Reboot)
                        })
                    }) { Text("Reboot") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().height(320.dp),
        ) {
            SelectionContainer {
                LazyColumn(state = listState, contentPadding = PaddingValues(10.dp)) {
                    items(vm.upgradeLog.size) { i ->
                        Text(
                            vm.upgradeLog[i],
                            fontFamily = Mono,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
        }
    }
}
