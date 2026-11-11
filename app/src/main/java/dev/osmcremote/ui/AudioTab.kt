package dev.osmcremote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.osmcremote.AppViewModel
import dev.osmcremote.KodiSetting
import dev.osmcremote.KodiVolume
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AudioTab(vm: AppViewModel) {
    var volume by remember { mutableStateOf<KodiVolume?>(null) }
    var dragVolume by remember { mutableStateOf<Float?>(null) }
    var settings by remember { mutableStateOf<List<KodiSetting>?>(null) }
    var kodiError by remember { mutableStateOf<String?>(null) }
    var savedAmp by remember { mutableStateOf<Double?>(null) }
    var ampLoaded by remember { mutableStateOf(false) }
    var ampDraft by remember { mutableStateOf(0f) }
    var loading by remember { mutableStateOf(false) }
    var ampSaving by remember { mutableStateOf(false) }
    var busySetting by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    fun loadKodi() {
        vm.launchOp {
            loading = true
            try {
                volume = vm.osmc.kodiVolume()
                settings = vm.osmc.kodiAudioSettings()
                kodiError = null
            } catch (e: dev.osmcremote.OsmcException) {
                kodiError = e.message
            } finally {
                loading = false
            }
        }
    }

    fun loadAmp() {
        vm.launchOp {
            val a = vm.osmc.amplification()
            savedAmp = a
            ampDraft = (a ?: 0.0).toFloat()
            ampLoaded = true
        }
    }

    LaunchedEffect(Unit) {
        loadKodi()
        loadAmp()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = spaced12,
    ) {
        // ------------------------------------------------------------ live volume
        SectionCard(
            "Volume",
            Icons.AutoMirrored.Filled.VolumeUp,
            action = {
                if (loading) SmallSpinner() else IconButton(onClick = { loadKodi() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            },
        ) {
            val v = volume
            when {
                kodiError != null -> Text(kodiError.orEmpty(), color = MaterialTheme.colorScheme.error)
                v == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        val newMute = !v.muted
                        volume = v.copy(muted = newMute)
                        vm.launchOp { vm.osmc.setKodiMute(newMute) }
                    }) {
                        Icon(
                            if (v.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (v.muted) "Unmute" else "Mute",
                            tint = if (v.muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = dragVolume ?: v.volume.toFloat(),
                        onValueChange = { dragVolume = it },
                        onValueChangeFinished = {
                            val target = (dragVolume ?: v.volume.toFloat()).roundToInt()
                            dragVolume = null
                            volume = v.copy(volume = target)
                            vm.launchOp { vm.osmc.setKodiVolume(target) }
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(dragVolume ?: v.volume.toFloat()).roundToInt()}%",
                        modifier = Modifier.width(48.dp).padding(start = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        // ------------------------------------------------------------ amplification
        SectionCard("Volume boost (amplification)", Icons.Filled.GraphicEq) {
            Text(
                "Kodi's default boost for all videos, the same as “Volume amplification” → " +
                    "“Set as default for all media” in Kodi's audio menu during playback. " +
                    "Useful when films are much quieter than TV.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (!ampLoaded) {
                SmallSpinner()
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = ampDraft,
                        onValueChange = { ampDraft = it.roundToInt().toFloat() },
                        valueRange = 0f..60f,
                        steps = 59,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "+${ampDraft.roundToInt()} dB",
                        modifier = Modifier.width(64.dp).padding(start = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "Currently saved: " + (savedAmp?.let { "+${it.roundToInt()} dB" } ?: "not set (0 dB)") +
                        ". Around +6 to +12 dB is typical; high values can distort loud scenes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = spaced8, verticalAlignment = Alignment.CenterVertically) {
                    val changed = (savedAmp ?: 0.0).roundToInt() != ampDraft.roundToInt()
                    Button(
                        enabled = changed && !ampSaving,
                        onClick = {
                            val target = ampDraft.roundToInt().toDouble()
                            confirm = Confirm(
                                "Set boost to +${target.roundToInt()} dB?",
                                "Kodi will restart to apply this (about 10–20 seconds). Anything playing will stop.",
                                "Apply",
                            ) {
                                vm.launchOp {
                                    ampSaving = true
                                    try {
                                        vm.osmc.setAmplification(target)
                                        savedAmp = target
                                        vm.toast("Boost set to +${target.roundToInt()} dB. Kodi is restarting.")
                                    } finally {
                                        ampSaving = false
                                    }
                                }
                            }
                        },
                    ) { Text("Apply") }
                    if (ampSaving) SmallSpinner()
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Videos where you've already changed audio settings in Kodi keep their own value.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ------------------------------------------------------------ Kodi audio settings
        val list = settings
        if (list != null) {
            SectionCard("Kodi audio settings", Icons.Filled.Tune) {
                Text(
                    "Live from Kodi (Settings → System → Audio). Changes apply immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val shown = list.filter { it.editable }
                if (shown.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Kodi didn't report any audio settings.")
                }
                shown.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp)) else Spacer(Modifier.height(12.dp))
                    KodiSettingRow(
                        s = s,
                        busy = busySetting == s.id,
                        onChange = { newValue ->
                            vm.launchOp {
                                busySetting = s.id
                                try {
                                    vm.osmc.setKodiSetting(s, newValue)
                                    // other settings can become enabled/disabled as a result
                                    settings = vm.osmc.kodiAudioSettings()
                                } finally {
                                    busySetting = null
                                }
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    confirm?.let { ConfirmDialog(it) { confirm = null } }
}

@Composable
private fun KodiSettingRow(s: KodiSetting, busy: Boolean, onChange: (Any) -> Unit) {
    val dim = if (s.enabled) 1f else 0.45f
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                )
                if (s.help.isNotBlank()) {
                    Text(
                        s.help,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            when {
                busy -> SmallSpinner()
                s.type == "boolean" -> Switch(
                    checked = s.value == true || s.value?.toString() == "true",
                    onCheckedChange = { onChange(it) },
                    enabled = s.enabled,
                )
                s.options.isNotEmpty() -> OptionPicker(s, onChange)
            }
        }
        // numeric settings without a fixed list of choices get a slider
        if (!busy && s.type != "boolean" && s.options.isEmpty() && s.min != null && s.max != null && s.max > s.min) {
            val min = s.min.toFloat()
            val max = s.max.toFloat()
            val step = (s.step ?: 1.0).toFloat().takeIf { it > 0f } ?: 1f
            val steps = (((max - min) / step).roundToInt() - 1).coerceIn(0, 200)
            val current = (s.value as? Number)?.toFloat() ?: s.value?.toString()?.toFloatOrNull() ?: min
            var draft by remember(s.id, s.value) { mutableStateOf(current) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = draft.coerceIn(min, max),
                    onValueChange = { draft = it },
                    onValueChangeFinished = {
                        if (draft != current) onChange(if (s.type == "integer") draft.roundToInt() else draft.toDouble())
                    },
                    valueRange = min..max,
                    steps = steps,
                    enabled = s.enabled,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (s.type == "integer") draft.roundToInt().toString() else String.format(Locale.US, "%.1f", draft),
                    modifier = Modifier.width(48.dp).padding(start = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun OptionPicker(s: KodiSetting, onChange: (Any) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = s.enabled,
            modifier = Modifier.widthIn(max = 190.dp),
        ) {
            Text(s.valueLabel.ifBlank { "Choose" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            s.options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label) },
                    leadingIcon = if (s.sameValue(opt.value)) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        open = false
                        if (!s.sameValue(opt.value)) onChange(opt.value)
                    },
                )
            }
        }
    }
}
