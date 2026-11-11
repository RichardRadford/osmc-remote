package dev.osmcremote.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.osmcremote.AppViewModel
import dev.osmcremote.CustomService
import dev.osmcremote.ServiceInfo
import dev.osmcremote.ServiceOrigin

private val knownServices = linkedMapOf(
    "mediacenter.service" to "Kodi (media center)",
    "ssh.service" to "SSH server",
    "smbd.service" to "Samba file sharing",
    "nmbd.service" to "Samba network names",
    "nfs-kernel-server.service" to "NFS server",
    "avahi-daemon.service" to "Zeroconf / Avahi",
    "connman.service" to "Network manager (ConnMan)",
    "cron.service" to "Scheduled tasks (cron)",
    "lircd.service" to "LIRC IR remote",
    "eventlircd.service" to "Remote input (eventlircd)",
    "bluetooth.service" to "Bluetooth",
    "tvheadend.service" to "Tvheadend",
    "transmission-daemon.service" to "Transmission",
    "docker.service" to "Docker",
    "plexmediaserver.service" to "Plex Media Server",
    "minidlna.service" to "MiniDLNA",
    "lighttpd.service" to "Lighttpd web server",
    "nginx.service" to "nginx web server",
    "mariadb.service" to "MariaDB (shared library)",
    "mysql.service" to "MySQL (shared library)",
    "sabnzbdplus.service" to "SABnzbd",
    "hyperion.service" to "Hyperion ambilight",
    "hyperiond.service" to "Hyperion ambilight",
    "pihole-FTL.service" to "Pi-hole",
    "systemd-timesyncd.service" to "Network time sync",
    "rpcbind.service" to "RPC bind (NFS)",
)

/** Stopping these can cut off the app or the TV picture, so ask first. */
private val risky = setOf("ssh.service", "connman.service", "mediacenter.service", "dbus.service", "systemd-logind.service")

private enum class SvcFilter(val label: String) { Common("Common"), Mine("Mine"), Running("Running"), Failed("Failed"), All("All") }

@Composable
fun ServicesScreen(vm: AppViewModel) {
    var list by remember { mutableStateOf<List<ServiceInfo>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busyName by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SvcFilter.Common) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var logFor by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var showInstall by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ServiceEdit?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    val loginUser = remember { vm.store.load().first.user.ifBlank { "osmc" } }

    fun reload() {
        vm.launchOp {
            loading = true
            try {
                list = vm.osmc.services()
            } finally {
                loading = false
            }
        }
    }

    fun act(s: ServiceInfo, action: String) {
        val perform = {
            vm.launchOp {
                busyName = s.name
                try {
                    vm.osmc.serviceAction(s.name, action)
                    vm.toast("${s.unit}: $action done")
                    list = vm.osmc.services()
                } finally {
                    busyName = null
                }
            }
        }
        if ((action == "stop" || action == "disable" || action == "restart") && s.name in risky) {
            val why = when (s.name) {
                "ssh.service" -> "This app talks to the device over SSH — you may be disconnected and unable to reconnect."
                "connman.service" -> "This manages networking — the device may drop off the network."
                "mediacenter.service" -> "This is Kodi itself — the TV will lose its interface."
                else -> "This is a core system service."
            }
            confirm = Confirm("${action.replaceFirstChar { it.uppercase() }} ${s.unit}?", why, action.replaceFirstChar { it.uppercase() }, destructive = true) { perform() }
        } else {
            perform()
        }
    }

    fun confirmInstall(pkg: String, label: String) {
        confirm = Confirm(
            "Install $label?",
            "It'll be downloaded and set up on the device, which can take a few minutes. " +
                "You can follow the progress here.",
            "Install",
        ) {
            showInstall = false
            vm.startPackageJob("Installing $label", listOf(pkg), emptyList())
        }
    }

    suspend fun planPackageRemoval(pkg: String, label: String) {
        val plan = vm.osmc.planRemoval(pkg)
        if (plan.blocked.isNotEmpty()) {
            info = "Removing $pkg would also remove ${plan.blocked.joinToString(", ")}, which the device needs " +
                "to work or to keep this connection open, so the app won't do it."
        } else {
            confirm = Confirm(
                "Uninstall $label?",
                "These packages will be removed:\n• " + plan.packages.joinToString("\n• ") +
                    "\n\nTheir configuration files are kept, so reinstalling later restores your settings.",
                "Uninstall",
                destructive = true,
            ) {
                showInstall = false
                vm.startPackageJob("Removing $label", emptyList(), listOf(pkg))
            }
        }
    }

    fun removePackageByName(pkg: String, label: String) {
        vm.launchOp { planPackageRemoval(pkg, label) }
    }

    fun requestRemove(s: ServiceInfo) {
        vm.launchOp {
            busyName = s.name
            try {
                when (val o = vm.osmc.serviceOrigin(s.name)) {
                    is ServiceOrigin.Package -> planPackageRemoval(o.pkg, knownServices[s.name] ?: o.pkg)
                    is ServiceOrigin.UnitFile -> {
                        confirm = Confirm(
                        "Delete ${s.unit}?",
                        if (o.ours) {
                            "This stops the service, removes it from boot and deletes it."
                        } else {
                            "This unit file wasn't created by this app or installed by a package. It'll be stopped, " +
                                "disabled and deleted. A copy is kept at ${o.path}.osmcremote.bak."
                        },
                        "Delete",
                        destructive = true,
                    ) {
                        vm.launchOp {
                            busyName = s.name
                            try {
                                vm.osmc.deleteUnitFile(s.name, o.path)
                                vm.toast("Deleted ${s.unit}")
                                list = vm.osmc.services()
                            } finally {
                                busyName = null
                            }
                        }
                    }
                    }
                    is ServiceOrigin.Unknown -> {
                        info = "${s.unit} can't be removed from the app. ${o.reason}"
                    }
                }
            } finally {
                busyName = null
            }
        }
    }

    fun editService(s: ServiceInfo) {
        vm.launchOp {
            busyName = s.name
            try {
                editing = ServiceEdit(vm.osmc.readCustomService(s.name), isNew = false, enabled = s.isEnabled)
            } finally {
                busyName = null
            }
        }
    }

    fun showLog(s: ServiceInfo) {
        logFor = s.unit to null
        vm.launchOp {
            val text = vm.osmc.serviceLog(s.name)
            if (logFor?.first == s.unit) logFor = s.unit to text.ifBlank { "(no log entries)" }
        }
    }

    LaunchedEffect(Unit) {
        reload()
        vm.reattachPackageJob()
    }
    // refresh once an install / removal finishes
    LaunchedEffect(vm.pkgExitCode) { if (vm.pkgExitCode != null) reload() }

    val all = list.orEmpty()
    val shown = all.filter { s ->
        val label = knownServices[s.name].orEmpty()
        val matchesQuery = query.isBlank() || s.name.contains(query, true) || label.contains(query, true)
        val matchesFilter = when (filter) {
            SvcFilter.Common -> s.name in knownServices || s.custom
            SvcFilter.Mine -> s.custom
            SvcFilter.Running -> s.isRunning
            SvcFilter.Failed -> s.isFailed
            SvcFilter.All -> true
        }
        matchesQuery && (matchesFilter || query.isNotBlank())
    }.let { l ->
        if (filter == SvcFilter.Common) l.sortedBy { knownServices.keys.indexOf(it.name).let { i -> if (i < 0) 999 else i } } else l
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            if (vm.pkgRunning && !vm.pkgDialogVisible) {
                Card(
                    onClick = { vm.pkgDialogVisible = true },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallSpinner()
                        Spacer(Modifier.width(10.dp))
                        Text(vm.pkgTitle + "…", Modifier.weight(1f))
                        Text("View", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search services") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (loading) SmallSpinner() else IconButton(onClick = { reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = spaced8,
            ) {
                SvcFilter.entries.forEach { f ->
                    val count = when (f) {
                        SvcFilter.Failed -> all.count { it.isFailed }
                        SvcFilter.Running -> all.count { it.isRunning }
                        SvcFilter.Mine -> all.count { it.custom }
                        else -> null
                    }
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(if (count != null && list != null) "${f.label} ($count)" else f.label) },
                    )
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = spaced8,
        ) {
            if (list != null && shown.isEmpty()) {
                item {
                    Text(
                        "No services match.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(shown, key = { it.name }) { s ->
                ServiceRow(
                    s = s,
                    label = knownServices[s.name],
                    busy = busyName == s.name,
                    onAction = { act(s, it) },
                    onLog = { showLog(s) },
                    onEdit = if (s.custom) ({ editService(s) }) else null,
                    onRemove = { requestRemove(s) },
                )
            }
        }
    }

    Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
        ExtendedFloatingActionButton(
            onClick = { addMenu = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add") },
        )
        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
            DropdownMenuItem(
                text = { Text("Install an app or package") },
                leadingIcon = { Icon(Icons.Filled.Download, null) },
                onClick = {
                    addMenu = false
                    showInstall = true
                },
            )
            DropdownMenuItem(
                text = { Text("Create a custom service") },
                leadingIcon = { Icon(Icons.Filled.Code, null) },
                onClick = {
                    addMenu = false
                    editing = ServiceEdit(CustomService(user = loginUser), isNew = true, enabled = true)
                },
            )
        }
    }
    }

    if (showInstall) {
        InstallDialog(
            vm = vm,
            onDismiss = { showInstall = false },
            onInstall = { pkg, label -> confirmInstall(pkg, label) },
            onRemove = { pkg, label -> removePackageByName(pkg, label) },
        )
    }

    editing?.let { e ->
        CustomServiceDialog(
            vm = vm,
            edit = e,
            loginUser = loginUser,
            onDismiss = { editing = null },
            onSaved = {
                editing = null
                if (e.isNew) filter = SvcFilter.Mine
                reload()
            },
        )
    }

    PackageJobDialog(vm)

    info?.let { msg ->
        AlertDialog(
            onDismissRequest = { info = null },
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            title = { Text("Can't remove this") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { info = null }) { Text("OK") } },
        )
    }

    logFor?.let { (unit, text) ->
        AlertDialog(
            onDismissRequest = { logFor = null },
            title = { Text("$unit log") },
            text = {
                if (text == null) {
                    SmallSpinner()
                } else {
                    Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(text, fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { logFor = null }) { Text("Close") } },
        )
    }

    confirm?.let { ConfirmDialog(it) { confirm = null } }
}

@Composable
private fun ServiceRow(
    s: ServiceInfo,
    label: String?,
    busy: Boolean,
    onAction: (String) -> Unit,
    onLog: () -> Unit,
    onEdit: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val (stateText, stateColor) = when {
        s.isFailed -> "Failed" to Bad
        s.isRunning -> "Running" to Good
        else -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Box(Modifier.size(10.dp).padding(0.dp)) {
                    Surface(shape = CircleShape, color = stateColor, modifier = Modifier.fillMaxSize()) {}
                }
            },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label ?: s.unit, modifier = Modifier.weight(1f, fill = false))
                    if (s.custom) {
                        Spacer(Modifier.width(6.dp))
                        StatusPill("mine", MaterialTheme.colorScheme.primary)
                    }
                }
            },
            supportingContent = {
                val boot = when (s.unitFileState) {
                    "enabled" -> "starts at boot"
                    "disabled" -> "doesn't start at boot"
                    "masked" -> "masked"
                    else -> s.unitFileState
                }
                Text((if (label != null) s.unit + " · " else "") + boot)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) SmallSpinner() else StatusPill(stateText, stateColor)
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !busy) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Actions")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (!s.isRunning) {
                                DropdownMenuItem(
                                    text = { Text("Start") },
                                    leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                                    onClick = { menu = false; onAction("start") },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Stop") },
                                    leadingIcon = { Icon(Icons.Filled.Stop, null) },
                                    onClick = { menu = false; onAction("stop") },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Restart") },
                                leadingIcon = { Icon(Icons.Filled.Replay, null) },
                                onClick = { menu = false; onAction("restart") },
                            )
                            if (s.canToggleBoot) {
                                if (s.isEnabled) {
                                    DropdownMenuItem(
                                        text = { Text("Don't start at boot") },
                                        leadingIcon = { Icon(Icons.Filled.Block, null) },
                                        onClick = { menu = false; onAction("disable") },
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Start at boot") },
                                        leadingIcon = { Icon(Icons.Filled.CheckCircle, null) },
                                        onClick = { menu = false; onAction("enable") },
                                    )
                                }
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("View log") },
                                leadingIcon = { Icon(Icons.Filled.Description, null) },
                                onClick = { menu = false; onLog() },
                            )
                            if (onEdit != null) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                    onClick = { menu = false; onEdit() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Remove…", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = { menu = false; onRemove() },
                            )
                        }
                    }
                }
            },
        )
    }
}
