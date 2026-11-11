package dev.osmcremote

class OsmcException(message: String) : Exception(message)

data class Status(
    val model: String,
    val host: String,
    val os: String,
    val kodi: String,
    val kernel: String,
    val uptimeSec: Double,
    val load: List<Double>,
    val cpus: Int,
    val tempC: Double?,
    val memTotal: Long,
    val memUsed: Long,
    val memAvailable: Long,
    val diskTotal: Long,
    val diskUsed: Long,
    val ip: String,
    val kodiState: String,
    val throttled: String?,
    val cpuFreqMHz: Int?,
) {
    val isPi: Boolean get() = model.contains("raspberry", ignoreCase = true)
    val isVero: Boolean get() = model.contains("vero", ignoreCase = true)
    val kodiRunning: Boolean get() = kodiState == "active"

    /** Human-readable meaning of `vcgencmd get_throttled` (Raspberry Pi only). */
    val throttleProblems: List<String>
        get() {
            val v = throttled?.removePrefix("0x")?.toLongOrNull(16) ?: return emptyList()
            val out = mutableListOf<String>()
            if (v and 0x1L != 0L) out += "Under-voltage right now — check the power supply"
            if (v and 0x2L != 0L) out += "CPU frequency is capped right now"
            if (v and 0x4L != 0L) out += "CPU is being throttled right now"
            if (v and 0x8L != 0L) out += "Soft temperature limit active"
            if (v and 0x10000L != 0L) out += "Under-voltage has happened since boot"
            if (v and 0x20000L != 0L) out += "Frequency capping has happened since boot"
            if (v and 0x40000L != 0L) out += "Throttling has happened since boot"
            if (v and 0x80000L != 0L) out += "Soft temperature limit has been hit since boot"
            return out
        }
}

data class Upgradable(val name: String, val newVersion: String, val oldVersion: String)

data class UpdateCheck(val packages: List<Upgradable>, val warnings: List<String>)

data class ServiceInfo(
    val name: String,
    val unitFileState: String,
    val active: String,
    val sub: String,
) {
    val unit: String get() = name.removeSuffix(".service")
    val isRunning: Boolean get() = active == "active"
    val isFailed: Boolean get() = active == "failed"
    val isEnabled: Boolean get() = unitFileState == "enabled"
    val canToggleBoot: Boolean get() = unitFileState == "enabled" || unitFileState == "disabled"
}

data class NetTech(val type: String, val powered: Boolean, val connected: Boolean)

data class NetService(val id: String, val name: String, val flags: String) {
    val isWifi: Boolean get() = id.startsWith("wifi_")
    val connected: Boolean get() = flags.contains('O') || flags.contains('R')
    val saved: Boolean get() = flags.startsWith("*")
    val enterprise: Boolean get() = id.contains("ieee8021x")
    val secured: Boolean get() = id.contains("_psk") || id.contains("_wep") || enterprise
}

data class NetworkInfo(
    val addresses: String,
    val techs: List<NetTech>,
    val services: List<NetService>,
)

enum class LogSource(val label: String) {
    Kodi("Kodi"),
    System("System"),
    Kernel("Kernel"),
    Updates("Updates"),
}

enum class PowerAction { RestartKodi, StopKodi, StartKodi, Reboot, Shutdown }

data class KodiVolume(val volume: Int, val muted: Boolean)

data class KodiOption(val label: String, val value: Any)

/** One Kodi setting as reported by JSON-RPC Settings.GetSettings. */
data class KodiSetting(
    val id: String,
    val label: String,
    val help: String,
    val type: String,
    val value: Any?,
    val options: List<KodiOption>,
    val min: Double?,
    val max: Double?,
    val step: Double?,
    val enabled: Boolean,
) {
    val editable: Boolean
        get() = when (type) {
            "boolean" -> true
            "integer", "number" -> options.isNotEmpty() || (min != null && max != null)
            "string" -> options.isNotEmpty()
            else -> false
        }

    fun sameValue(other: Any?): Boolean = value?.toString() == other?.toString()

    val valueLabel: String
        get() = options.firstOrNull { sameValue(it.value) }?.label ?: value?.toString().orEmpty()
}
