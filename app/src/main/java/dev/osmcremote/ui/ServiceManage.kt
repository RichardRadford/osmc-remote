package dev.osmcremote.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.osmcremote.AppPackage
import dev.osmcremote.AppViewModel
import dev.osmcremote.CustomService

// =================================================================== install

@Composable
fun InstallDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onInstall: (pkg: String, label: String) -> Unit,
    onRemove: (pkg: String, label: String) -> Unit,
) {
    var apps by remember { mutableStateOf<List<AppPackage>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var other by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.launchOp {
            try {
                apps = vm.osmc.appStore()
            } finally {
                loading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Download, contentDescription = null) },
        title = { Text("Install a service") },
        text = {
            Column {
                Text(
                    "Apps from the OSMC App Store (the same list as My OSMC → App Store), " +
                        "or any Debian package by name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                val list = apps
                when {
                    loading -> SmallSpinner()
                    list.isNullOrEmpty() -> Text(
                        "No App Store packages found. Run Updates → Check now to refresh the package lists, then try again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                        items(list, key = { it.name }) { app ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.displayName, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        app.description.ifBlank { app.name },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                if (app.installed) {
                                    TextButton(
                                        onClick = { onRemove(app.name, app.displayName) },
                                        colors = ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                    ) { Text("Remove") }
                                } else {
                                    FilledTonalButton(onClick = { onInstall(app.name, app.displayName) }) {
                                        Text("Install")
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Other package", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = other,
                        onValueChange = { other = it.trim().lowercase() },
                        placeholder = { Text("e.g. minidlna") },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = Mono),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        enabled = other.length >= 2 && !checking,
                        onClick = {
                            val pkg = other
                            vm.launchOp {
                                checking = true
                                try {
                                    when {
                                        !vm.osmc.packageExists(pkg) -> vm.toast(
                                            "No package called $pkg. Check the name, or refresh the lists with Updates → Check now."
                                        )
                                        vm.osmc.isInstalled(pkg) -> vm.toast("$pkg is already installed.")
                                        else -> onInstall(pkg, pkg)
                                    }
                                } finally {
                                    checking = false
                                }
                            }
                        },
                    ) { if (checking) SmallSpinner() else Text("Install") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// =================================================================== custom service editor

data class ServiceEdit(val svc: CustomService, val isNew: Boolean, val enabled: Boolean)

@Composable
fun CustomServiceDialog(
    vm: AppViewModel,
    edit: ServiceEdit,
    loginUser: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember { mutableStateOf(edit.svc.name) }
    var description by remember { mutableStateOf(edit.svc.description) }
    var command by remember { mutableStateOf(edit.svc.command) }
    var user by remember { mutableStateOf(edit.svc.user) }
    var restart by remember { mutableStateOf(edit.svc.restart) }
    var afterNetwork by remember { mutableStateOf(edit.svc.afterNetwork) }
    var workingDir by remember { mutableStateOf(edit.svc.workingDir) }
    var enable by remember { mutableStateOf(edit.enabled) }
    var startNow by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }

    val svc = CustomService(
        name = name.trim().removeSuffix(".service"),
        description = description.trim(),
        command = command.trim(),
        user = user.trim(),
        restart = restart,
        afterNetwork = afterNetwork,
        workingDir = workingDir.trim(),
    )
    val error = svc.validate()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (edit.isNew) "New custom service" else "Edit ${edit.svc.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = spaced8) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace(' ', '-') },
                    label = { Text("Name") },
                    placeholder = { Text("my-script") },
                    enabled = edit.isNew,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it.replace('\n', ' ') },
                    label = { Text("Command to run") },
                    placeholder = { Text("/home/osmc/scripts/sync.sh") },
                    supportingText = { Text("Runs with /bin/sh, so pipes and && work. It should keep running (don't background it with &).") },
                    textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Run as", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = spaced8) {
                    listOf(loginUser, "root").distinct().forEach { u ->
                        FilterChip(selected = user == u, onClick = { user = u }, label = { Text(u) })
                    }
                    if (user != loginUser && user != "root") {
                        FilterChip(selected = true, onClick = {}, label = { Text(user) })
                    }
                }

                Text("If it stops", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = spaced8) {
                    listOf("no" to "Leave it", "on-failure" to "Restart if it fails", "always" to "Always restart")
                        .forEach { (v, l) ->
                            FilterChip(selected = restart == v, onClick = { restart = v }, label = { Text(l) })
                        }
                }

                OutlinedTextField(
                    value = workingDir,
                    onValueChange = { workingDir = it.trim() },
                    label = { Text("Working folder (optional)") },
                    placeholder = { Text("/home/osmc") },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Mono),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )

                SwitchRow("Wait for the network", "Start after the network is up", afterNetwork) { afterNetwork = it }
                SwitchRow("Start at boot", null, enable) { enable = it }
                SwitchRow(if (edit.isNew) "Start it now" else "Restart it now", null, startNow) { startNow = it }

                if (showErrors && error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    if (error != null) {
                        showErrors = true
                    } else vm.launchOp {
                        saving = true
                        try {
                            vm.osmc.saveCustomService(svc, edit.isNew, enable, startNow)
                            vm.toast(if (edit.isNew) "Created ${svc.name}" else "Saved ${svc.name}")
                            onSaved()
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { if (saving) SmallSpinner() else Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// =================================================================== package job progress

@Composable
fun PackageJobDialog(vm: AppViewModel) {
    if (!vm.pkgDialogVisible) return
    val listState = rememberLazyListState()
    LaunchedEffect(vm.pkgLog.size) {
        if (vm.pkgLog.isNotEmpty()) listState.scrollToItem(vm.pkgLog.lastIndex)
    }
    val code = vm.pkgExitCode
    AlertDialog(
        onDismissRequest = { vm.pkgDialogVisible = false },
        title = { Text(vm.pkgTitle) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        vm.pkgRunning && code == null -> {
                            SmallSpinner()
                            Spacer(Modifier.width(10.dp))
                            Text("Working… you can close this; it carries on.", style = MaterialTheme.typography.bodyMedium)
                        }
                        code == 0 -> StatusPill("Done", Good)
                        code != null -> StatusPill("Failed (exit code $code) — see the log", Bad)
                        else -> StatusPill("Stopped following the log", Warn)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                ) {
                    SelectionContainer {
                        LazyColumn(state = listState, contentPadding = PaddingValues(8.dp)) {
                            items(vm.pkgLog.size) { i ->
                                Text(vm.pkgLog[i], fontFamily = Mono, fontSize = 10.sp, lineHeight = 13.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.pkgDialogVisible = false }) {
                Text(if (vm.pkgRunning) "Hide" else "Close")
            }
        },
    )
}
