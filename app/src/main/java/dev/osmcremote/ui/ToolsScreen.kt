package dev.osmcremote.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.osmcremote.AppViewModel
import dev.osmcremote.LogSource
import dev.osmcremote.NetService
import dev.osmcremote.NetworkInfo
import dev.osmcremote.TermEntry

@Composable
fun ToolsScreen(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Audio", "Network", "Logs", "Terminal")
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> AudioTab(vm)
            1 -> NetworkTab(vm)
            2 -> LogsTab(vm)
            else -> TerminalTab(vm)
        }
    }
}

// =================================================================== Network

@Composable
private fun NetworkTab(vm: AppViewModel) {
    var info by remember { mutableStateOf<NetworkInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<NetService?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    fun load(scan: Boolean) {
        vm.launchOp {
            busy = true
            try {
                info = vm.osmc.network(scan)
            } finally {
                busy = false
            }
        }
    }

    fun op(msg: String, block: suspend () -> Unit) {
        vm.launchOp {
            busy = true
            try {
                block()
                vm.toast(msg)
                info = vm.osmc.network(false)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { load(false) }

    val wifiInUse = info?.services?.any { it.isWifi && it.connected } == true

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = spaced12,
    ) {
        item {
            Row(horizontalArrangement = spaced8, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { load(false) }, enabled = !busy) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh")
                }
                FilledTonalButton(onClick = { load(true) }, enabled = !busy) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan Wi-Fi")
                }
                if (busy) SmallSpinner()
            }
        }

        info?.let { inf ->
            item {
                SectionCard("Adapters", Icons.Filled.SettingsEthernet) {
                    if (inf.techs.isEmpty()) Text("No adapters reported by ConnMan.")
                    inf.techs.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (t.type) {
                                    "wifi" -> Icons.Filled.Wifi
                                    "bluetooth" -> Icons.Filled.Bluetooth
                                    else -> Icons.Filled.Lan
                                },
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.type.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (t.connected) "Connected" else if (t.powered) "On, not connected" else "Off",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (t.connected) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = t.powered,
                                enabled = !busy,
                                onCheckedChange = { on ->
                                    val doIt = { op("${t.type} turned ${if (on) "on" else "off"}") { vm.osmc.setTechPowered(t.type, on) } }
                                    if (!on && (t.connected || (t.type == "wifi" && wifiInUse))) {
                                        confirm = Confirm(
                                            "Turn off ${t.type}?",
                                            "If this phone reaches the device over ${t.type}, you'll be disconnected.",
                                            "Turn off",
                                            destructive = true,
                                        ) { doIt() }
                                    } else doIt()
                                },
                            )
                        }
                    }
                }
            }

            item {
                SectionCard("Addresses", Icons.Filled.Lan) {
                    SelectionContainer {
                        Text(inf.addresses.ifBlank { "—" }, fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }

            item {
                Text("Networks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (inf.services.isEmpty()) {
                item {
                    Text(
                        "No networks listed. Try Scan Wi-Fi.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(inf.services, key = { it.id }) { s ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Icon(
                                if (s.isWifi) (if (s.secured) Icons.Filled.WifiLock else Icons.Filled.Wifi) else Icons.Filled.Lan,
                                contentDescription = null,
                                tint = if (s.connected) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        headlineContent = { Text(s.name) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    if (s.connected) "Connected" else null,
                                    if (s.saved && !s.connected) "Saved" else null,
                                    if (s.enterprise) "Enterprise" else if (s.secured) "Secured" else if (s.isWifi) "Open" else null,
                                ).joinToString(" · ").ifBlank { "Available" },
                            )
                        },
                        trailingContent = {
                            when {
                                s.connected -> TextButton(onClick = {
                                    confirm = Confirm(
                                        "Disconnect from ${s.name}?",
                                        "If this phone reaches the device through this network, you'll lose the connection.",
                                        "Disconnect",
                                        destructive = true,
                                    ) { op("Disconnected from ${s.name}") { vm.osmc.disconnectService(s.id) } }
                                }, enabled = !busy) { Text("Disconnect") }

                                s.enterprise && !s.saved -> Text(
                                    "Use My OSMC",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                else -> TextButton(onClick = {
                                    if (s.secured && !s.saved) target = s
                                    else op("Connecting to ${s.name}") { vm.osmc.connectService(s.id) }
                                }, enabled = !busy) { Text("Connect") }
                            }
                        },
                    )
                }
            }
        }
    }

    target?.let { s ->
        var pass by remember(s.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text("Connect to ${s.name}") },
            text = {
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Wi-Fi password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    target = null
                    val p = pass
                    op("Connecting to ${s.name}") { vm.osmc.connectWifi(s, p) }
                }, enabled = pass.length >= 8 || s.id.contains("_wep")) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text("Cancel") } },
        )
    }

    confirm?.let { ConfirmDialog(it) { confirm = null } }
}

// =================================================================== Logs

@Composable
private fun LogsTab(vm: AppViewModel) {
    var source by rememberSaveable { mutableStateOf(LogSource.Kodi) }
    var text by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var uploadedUrl by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    fun load() {
        vm.launchOp {
            busy = true
            try {
                text = vm.osmc.readLog(source)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(source) { load() }
    val lines = remember(text) { text?.lines().orEmpty() }
    LaunchedEffect(lines) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
            horizontalArrangement = spaced8,
        ) {
            LogSource.entries.forEach { s ->
                FilterChip(selected = source == s, onClick = { source = s }, label = { Text(s.label) })
            }
        }
        Row(horizontalArrangement = spaced8, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { load() }, enabled = !busy) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reload")
            }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "OSMC ${source.label} log")
                    putExtra(Intent.EXTRA_TEXT, text.orEmpty().takeLast(90_000))
                }
                context.startActivity(Intent.createChooser(send, "Share log"))
            }, enabled = !text.isNullOrEmpty()) {
                Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share")
            }
            FilledTonalButton(onClick = {
                vm.launchOp {
                    uploading = true
                    try {
                        uploadedUrl = vm.osmc.uploadLogs()
                    } finally {
                        uploading = false
                    }
                }
            }, enabled = !uploading) {
                if (uploading) SmallSpinner() else Icon(Icons.Filled.CloudUpload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Upload all")
            }
        }
        Text(
            "“Upload all” runs OSMC's grab-logs and gives you a link to share on the OSMC forum.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 12.dp),
        ) {
            if (busy && text == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                SelectionContainer {
                    LazyColumn(state = listState, contentPadding = PaddingValues(10.dp)) {
                        items(lines.size) { i ->
                            Text(lines[i], fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }
    }

    uploadedUrl?.let { url ->
        val clipboard = LocalClipboardManager.current
        val uri = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { uploadedUrl = null },
            title = { Text("Logs uploaded") },
            text = {
                SelectionContainer { Text(url, fontFamily = Mono) }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(url))
                    vm.toast("Link copied")
                    uploadedUrl = null
                }) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { uri.openUri(url) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open")
                }
            },
        )
    }
}

// =================================================================== Terminal

private val quickCommands = listOf(
    "Disk" to "df -h",
    "Memory" to "free -h",
    "Uptime" to "uptime",
    "Top" to "top -bn1 | head -n 25",
    "Temp" to "vcgencmd measure_temp 2>/dev/null || cat /sys/class/thermal/thermal_zone0/temp",
    "USB" to "lsusb",
    "Mounts" to "findmnt -D 2>/dev/null || mount",
    "Kodi pkgs" to "dpkg -l | grep -i mediacenter",
)

@Composable
private fun TerminalTab(vm: AppViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    var asRoot by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun submit() {
        val cmd = input.trim()
        if (cmd.isEmpty()) return
        vm.runTerminal(cmd, asRoot)
        input = ""
    }

    LaunchedEffect(vm.terminal.size, vm.terminal.lastOrNull()?.code) {
        if (vm.terminal.isNotEmpty()) listState.animateScrollToItem(vm.terminal.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = spaced8,
        ) {
            quickCommands.forEach { (label, cmd) ->
                AssistChip(onClick = { vm.runTerminal(cmd, false) }, label = { Text(label) })
            }
            if (vm.terminal.isNotEmpty()) {
                AssistChip(
                    onClick = { vm.terminal.clear() },
                    label = { Text("Clear") },
                    leadingIcon = { Icon(Icons.Filled.DeleteSweep, null, Modifier.size(18.dp)) },
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = spaced8,
        ) {
            if (vm.terminal.isEmpty()) {
                item {
                    Text(
                        "Run one-off shell commands on the device. Interactive programs " +
                            "(nano, top without -b, anything that asks questions) won't work here. " +
                            "Turn on “root” to run with sudo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(vm.terminal, key = { it.id }) { e -> TermCard(e) }
        }
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = asRoot,
                    onClick = { asRoot = !asRoot },
                    label = { Text("root") },
                    leadingIcon = if (asRoot) {
                        { Icon(Icons.Filled.Lock, null, Modifier.size(16.dp)) }
                    } else null,
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(if (asRoot) "# command" else "$ command") },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { submit() }, enabled = input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Run")
                }
            }
        }
    }
}

@Composable
private fun TermCard(e: TermEntry) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                (if (e.asRoot) "# " else "$ ") + e.command,
                fontFamily = Mono,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            when (val code = e.code) {
                null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> {
                    SelectionContainer {
                        Text(
                            e.output.ifBlank { "(no output)" },
                            fontFamily = Mono,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                        )
                    }
                    if (code != 0) {
                        Text(
                            "exit code $code",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
