package dev.osmcremote.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.osmcremote.AppViewModel
import dev.osmcremote.PowerAction
import dev.osmcremote.Status

private enum class Kind { Toggle, Number, Text, Choice }

private data class Opt(
    val key: String,
    val label: String,
    val help: String,
    val kind: Kind,
    val choices: List<Pair<String, String>> = emptyList(),
)

private data class OptGroup(val title: String, val icon: ImageVector, val warning: String?, val options: List<Opt>)

private val piGroups = listOf(
    OptGroup(
        "Display & HDMI", Icons.Filled.Tv, null,
        listOf(
            Opt("hdmi_force_hotplug", "Force HDMI output", "Use HDMI even if no TV is detected at boot.", Kind.Toggle),
            Opt(
                "hdmi_drive", "HDMI signal type", "DVI mode has no audio over HDMI.", Kind.Choice,
                listOf("1" to "DVI (no sound)", "2" to "HDMI (with sound)"),
            ),
            Opt(
                "hdmi_group", "Resolution group", "Use CEA for TVs, DMT for computer monitors.", Kind.Choice,
                listOf("1" to "CEA (TV)", "2" to "DMT (monitor)"),
            ),
            Opt("hdmi_mode", "Resolution mode", "Mode number within the group, e.g. 16 = 1080p60 (CEA).", Kind.Number),
            Opt("config_hdmi_boost", "HDMI signal boost", "0–11. Try 4–7 for long cables or flickering.", Kind.Number),
            Opt("disable_overscan", "Disable overscan", "Removes the black border on most modern TVs.", Kind.Toggle),
            Opt("hdmi_ignore_cec_init", "Don't switch TV input at boot", "Stops the Pi grabbing the TV on power-up.", Kind.Toggle),
            Opt("hdmi_ignore_cec", "Disable CEC entirely", "Turn off TV-remote control over HDMI.", Kind.Toggle),
            Opt("disable_splash", "Hide rainbow splash", "Skip the coloured boot screen.", Kind.Toggle),
        ),
    ),
    OptGroup(
        "Memory & performance", Icons.Filled.Speed,
        "Overclocking can make the device unstable or overheat. Change one value at a time.",
        listOf(
            Opt("gpu_mem", "GPU memory (MB)", "Memory reserved for video. OSMC normally sets a sensible value.", Kind.Number),
            Opt("arm_freq", "CPU frequency (MHz)", "Leave blank for the default.", Kind.Number),
            Opt("core_freq", "GPU core frequency (MHz)", "Leave blank for the default.", Kind.Number),
            Opt("over_voltage", "Over-voltage", "0–6 (each step is 0.025 V). Needed for higher clocks.", Kind.Number),
            Opt("force_turbo", "Force turbo", "Always run at full clock. Can set a permanent warranty bit with over_voltage.", Kind.Toggle),
        ),
    ),
    OptGroup(
        "Video codec licences", Icons.Filled.Movie,
        "Only needed on Raspberry Pi 1–3. Pi 4 and later decode these in software.",
        listOf(
            Opt("decode_MPG2", "MPEG-2 licence key", "e.g. 0x12345678", Kind.Text),
            Opt("decode_WVC1", "VC-1 licence key", "e.g. 0x12345678", Kind.Text),
        ),
    ),
    OptGroup(
        "Other", Icons.Filled.Tune, null,
        listOf(
            Opt("max_usb_current", "Max USB current", "Lets USB drives draw more power (Pi 2/3).", Kind.Toggle),
            Opt("display_rotate", "Rotate display", "0 = normal, 2 = upside down (older Pis).", Kind.Number),
        ),
    ),
)

/** Values of active `key=value` lines; later lines win (like the firmware). */
private fun parseConfig(text: String): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    for (line in text.lines()) {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#") || t.startsWith("[") || !t.contains('=')) continue
        map[t.substringBefore('=').trim()] = t.substringAfter('=').trim()
    }
    return map
}

/** Set, replace or (blank value) remove `key=value` in config text, keeping everything else. */
private fun applyChange(text: String, key: String, value: String): String {
    val lines = text.lines().toMutableList()
    if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
    fun isKey(l: String): Boolean {
        val t = l.trim()
        return !t.startsWith("#") && t.contains('=') && t.substringBefore('=').trim() == key
    }
    val idx = lines.indices.filter { isKey(lines[it]) }
    when {
        value.isBlank() -> idx.reversed().forEach { lines.removeAt(it) }
        idx.isNotEmpty() -> lines[idx.last()] = "$key=$value"
        else -> {
            val lastSection = lines.lastOrNull { it.trim().startsWith("[") }?.trim()
            if (lastSection != null && !lastSection.equals("[all]", ignoreCase = true)) lines.add("[all]")
            lines.add("$key=$value")
        }
    }
    return lines.joinToString("\n") + "\n"
}

@Composable
fun ConfigScreen(vm: AppViewModel, status: Status) {
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var path by rememberSaveable { mutableStateOf<String?>(null) }
    var original by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf("") }
    var easyMode by rememberSaveable { mutableStateOf(status.isPi) }
    var busy by remember { mutableStateOf(false) }
    var savedOnce by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var askPath by remember { mutableStateOf(false) }

    val dirty = original != null && working != original

    fun load(p: String) {
        vm.launchOp {
            busy = true
            try {
                val t = vm.osmc.readFile(p)
                path = p
                original = t
                working = t
            } finally {
                busy = false
            }
        }
    }

    fun save() {
        val p = path ?: return
        val text = working
        confirm = Confirm(
            "Save $p?",
            "A backup is kept at $p.osmcremote.bak. Changes take effect after a reboot.",
            "Save",
        ) {
            vm.launchOp {
                busy = true
                try {
                    vm.osmc.writeFile(p, text)
                    original = text
                    savedOnce = true
                    vm.toast("Saved $p")
                } finally {
                    busy = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.launchOp {
            val f = vm.osmc.configFiles()
            files = f
            val pick = path ?: f.firstOrNull { it.endsWith("config-user.txt") }
                ?: f.firstOrNull { it.endsWith("config.txt") } ?: f.firstOrNull()
            if (pick != null) load(pick)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = spaced12,
    ) {
        SectionCard(
            "Boot configuration",
            Icons.Filled.Settings,
            action = {
                IconButton(onClick = { path?.let { load(it) } }, enabled = path != null && !busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                }
            },
        ) {
            Text(
                if (status.isPi) {
                    "On a Raspberry Pi, OSMC keeps your own changes in config-user.txt when it exists, " +
                        "so updates don't overwrite them."
                } else {
                    "Pi-specific settings are hidden on this device. You can still edit boot files in the raw editor."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = spaced8) {
                files.forEach { f ->
                    FilterChip(
                        selected = f == path,
                        onClick = {
                            if (f != path) {
                                if (dirty) {
                                    confirm = Confirm("Discard changes?", "Your unsaved edits will be lost.", "Discard") { load(f) }
                                } else load(f)
                            }
                        },
                        label = { Text(f.substringAfterLast('/')) },
                    )
                }
                AssistChip(
                    onClick = { askPath = true },
                    label = { Text("Other file…") },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp)) },
                )
            }
            if (path != null) {
                Spacer(Modifier.height(6.dp))
                Text(path.orEmpty(), style = MaterialTheme.typography.labelSmall, fontFamily = Mono)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = spaced8, verticalAlignment = Alignment.CenterVertically) {
                if (status.isPi) {
                    FilterChip(selected = easyMode, onClick = { easyMode = true }, label = { Text("Easy settings") })
                }
                FilterChip(selected = !easyMode || !status.isPi, onClick = { easyMode = false }, label = { Text("Raw editor") })
                Spacer(Modifier.weight(1f))
                if (busy) SmallSpinner()
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = spaced8) {
                Button(onClick = { save() }, enabled = dirty && !busy) {
                    Icon(Icons.Filled.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save")
                }
                OutlinedButton(onClick = { working = original.orEmpty() }, enabled = dirty && !busy) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Revert")
                }
            }
        }

        if (savedOnce && !dirty) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved. Reboot for the changes to take effect.", Modifier.weight(1f))
                    TextButton(onClick = {
                        confirm = Confirm("Reboot now?", "You'll be disconnected until it's back up.", "Reboot") {
                            vm.power(PowerAction.Reboot)
                        }
                    }) { Text("Reboot") }
                }
            }
        }

        if (original != null) {
            if (easyMode && status.isPi) {
                val values = parseConfig(working)
                piGroups.forEach { g ->
                    SectionCard(g.title, g.icon) {
                        g.warning?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                        }
                        g.options.forEachIndexed { i, opt ->
                            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            OptionRow(opt, values[opt.key].orEmpty()) { v -> working = applyChange(working, opt.key, v) }
                        }
                    }
                }
                Text(
                    "Settings inside conditional sections like [pi4] are best edited in the raw editor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = working,
                    onValueChange = { working = it },
                    textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
            }
        } else if (!busy && files.isEmpty()) {
            Text(
                "No boot config files found. Use “Other file…” to open one by path.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    if (askPath) {
        var p by remember { mutableStateOf(path ?: "/boot/") }
        AlertDialog(
            onDismissRequest = { askPath = false },
            title = { Text("Open file") },
            text = {
                OutlinedTextField(
                    value = p,
                    onValueChange = { p = it },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Mono),
                    label = { Text("Full path on the device") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askPath = false
                    val target = p.trim()
                    if (target.startsWith("/")) {
                        if (target !in files) files = files + target
                        load(target)
                    }
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { askPath = false }) { Text("Cancel") } },
        )
    }

    confirm?.let { ConfirmDialog(it) { confirm = null } }
}

@Composable
private fun OptionRow(opt: Opt, value: String, onChange: (String) -> Unit) {
    when (opt.kind) {
        Kind.Toggle -> Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(opt.label, style = MaterialTheme.typography.bodyLarge)
                Text(opt.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = value == "1", onCheckedChange = { onChange(if (it) "1" else "0") })
        }

        Kind.Choice -> Column {
            Text(opt.label, style = MaterialTheme.typography.bodyLarge)
            Text(opt.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = spaced8) {
                FilterChip(selected = value.isBlank(), onClick = { onChange("") }, label = { Text("Default") })
                opt.choices.forEach { (v, label) ->
                    FilterChip(selected = value == v, onClick = { onChange(v) }, label = { Text(label) })
                }
            }
        }

        Kind.Number, Kind.Text -> OutlinedTextField(
            value = value,
            onValueChange = { v ->
                if (opt.kind == Kind.Text || v.all { it.isDigit() || it == '-' }) onChange(v.trim())
            },
            label = { Text(opt.label) },
            supportingText = { Text(opt.help) },
            placeholder = { Text("Default") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (opt.kind == Kind.Number) KeyboardType.Number else KeyboardType.Ascii,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
