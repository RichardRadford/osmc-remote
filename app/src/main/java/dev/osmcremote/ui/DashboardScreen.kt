package dev.osmcremote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.osmcremote.AppViewModel
import dev.osmcremote.Osmc
import dev.osmcremote.PowerAction
import dev.osmcremote.Status
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun DashboardScreen(vm: AppViewModel, status: Status) {
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    // Live refresh while this tab is visible.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            vm.pollStatus()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = spaced12,
    ) {
        HeroCard(status)

        Row(horizontalArrangement = spaced12) {
            val temp = status.tempC
            val tempFrac = ((temp ?: 0.0) / 85.0).toFloat()
            MetricTile(
                icon = Icons.Filled.Thermostat,
                label = "CPU temp",
                value = if (temp != null) String.format(Locale.US, "%.0f°C", temp) else "—",
                sub = status.cpuFreqMHz?.let { "$it MHz" } ?: "",
                fraction = if (temp != null) tempFrac else null,
                color = levelColor(tempFrac),
                modifier = Modifier.weight(1f),
            )
            val load1 = status.load.firstOrNull() ?: 0.0
            val loadFrac = (load1 / status.cpus.coerceAtLeast(1)).toFloat()
            MetricTile(
                icon = Icons.Filled.Speed,
                label = "CPU load",
                value = String.format(Locale.US, "%.0f%%", (loadFrac * 100).coerceAtMost(999f)),
                sub = status.load.joinToString(" ") { String.format(Locale.US, "%.2f", it) },
                fraction = loadFrac,
                color = levelColor(loadFrac),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = spaced12) {
            val memFrac = if (status.memTotal > 0) {
                ((status.memTotal - status.memAvailable).toDouble() / status.memTotal).toFloat()
            } else 0f
            MetricTile(
                icon = Icons.Filled.Memory,
                label = "Memory",
                value = String.format(Locale.US, "%.0f%%", memFrac * 100),
                sub = formatBytes(status.memTotal - status.memAvailable) + " of " + formatBytes(status.memTotal),
                fraction = memFrac,
                color = levelColor(memFrac),
                modifier = Modifier.weight(1f),
            )
            val diskFrac = if (status.diskTotal > 0) (status.diskUsed.toDouble() / status.diskTotal).toFloat() else 0f
            MetricTile(
                icon = Icons.Filled.Storage,
                label = "Storage",
                value = String.format(Locale.US, "%.0f%%", diskFrac * 100),
                sub = formatBytes(status.diskUsed) + " of " + formatBytes(status.diskTotal),
                fraction = diskFrac,
                color = levelColor(diskFrac),
                modifier = Modifier.weight(1f),
            )
        }

        val problems = status.throttleProblems
        if (problems.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Power / thermal warnings", style = MaterialTheme.typography.titleSmall)
                        problems.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        SectionCard("Power", Icons.Filled.PowerSettingsNew) {
            FlowRow(horizontalArrangement = spaced8, verticalArrangement = spaced8) {
                FilledTonalButton(onClick = {
                    confirm = Confirm("Restart Kodi?", "Anything playing will stop.", "Restart") {
                        vm.power(PowerAction.RestartKodi)
                    }
                }) {
                    Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restart Kodi")
                }
                if (status.kodiRunning) {
                    FilledTonalButton(onClick = {
                        confirm = Confirm(
                            "Stop Kodi?",
                            "The TV will show a blank screen until Kodi is started again.",
                            "Stop",
                        ) { vm.power(PowerAction.StopKodi) }
                    }) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Stop Kodi")
                    }
                } else {
                    FilledTonalButton(onClick = { vm.power(PowerAction.StartKodi) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Start Kodi")
                    }
                }
                OutlinedButton(onClick = {
                    confirm = Confirm("Reboot the device?", "You'll be disconnected until it's back up.", "Reboot") {
                        vm.power(PowerAction.Reboot)
                    }
                }) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reboot")
                }
                OutlinedButton(
                    onClick = {
                        confirm = Confirm(
                            "Shut down the device?",
                            "You'll need physical access (or to replug the power) to turn it back on.",
                            "Shut down",
                            destructive = true,
                        ) { vm.power(PowerAction.Shutdown) }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Shut down")
                }
            }
        }

        SectionCard("System", Icons.Filled.DeveloperBoard) {
            InfoRow("Model", status.model)
            InfoRow("OS", status.os)
            InfoRow("Kodi package", status.kodi)
            InfoRow("Kernel", status.kernel)
            InfoRow("CPU cores", status.cpus.toString())
            InfoRow("IP address", status.ip)
            InfoRow(
                "Admin access",
                when (vm.osmc.sudoMode) {
                    Osmc.SudoMode.NoPassword -> "sudo (no password)"
                    Osmc.SudoMode.Password -> "sudo (with password)"
                    Osmc.SudoMode.Unavailable -> "not available"
                    Osmc.SudoMode.Unknown -> "checking…"
                },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    confirm?.let { ConfirmDialog(it) { confirm = null } }
}

@Composable
private fun HeroCard(status: Status) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    Icons.Filled.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(13.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    status.host,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Up " + formatUptime(status.uptimeSec) + (if (status.ip.isNotBlank()) " · " + status.ip else ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(8.dp))
                if (status.kodiRunning) StatusPill("Kodi running", Good) else StatusPill("Kodi stopped", Warn)
            }
        }
    }
}

@Composable
private fun MetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    sub: String,
    fraction: Float?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                sub.ifBlank { " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (fraction != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = color,
                    trackColor = color.copy(alpha = 0.15f),
                )
            }
        }
    }
}
