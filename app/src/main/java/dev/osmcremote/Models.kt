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
    val custom: Boolean = false,
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

data class AppPackage(val name: String, val description: String, val installed: Boolean) {
    /** "armv7-tvheadend-app-osmc" -> "Tvheadend" */
    val displayName: String
        get() = name.removeSuffix("-osmc").removeSuffix("-app")
            .replace(Regex("^(armv\\d+|aarch64|arm64|vero\\d*|rbp\\d*)-"), "")
            .split('-')
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}

sealed interface ServiceOrigin {
    data class Package(val pkg: String, val path: String) : ServiceOrigin
    data class UnitFile(val path: String, val ours: Boolean) : ServiceOrigin
    data class Unknown(val reason: String) : ServiceOrigin
}

data class RemovalPlan(val packages: List<String>, val blocked: List<String>)

/** A simple systemd service that runs one shell command. */
data class CustomService(
    val name: String = "",
    val description: String = "",
    val command: String = "",
    val user: String = "osmc",
    val restart: String = "on-failure", // no | on-failure | always
    val afterNetwork: Boolean = true,
    val workingDir: String = "",
) {
    val unitName: String get() = "$name.service"

    /** Returns an error message, or null if the service can be saved. */
    fun validate(): String? = when {
        !NAME.matches(name) -> "Use letters, numbers, dashes or underscores for the name (no spaces)."
        command.isBlank() -> "Enter the command to run."
        command.contains('\n') -> "The command must be a single line. Put longer scripts in a file and run that."
        !USER.matches(user) -> "That isn't a valid user name."
        workingDir.isNotBlank() && !workingDir.startsWith("/") -> "The working folder must be a full path starting with /."
        restart !in setOf("no", "on-failure", "always") -> "Unknown restart policy."
        else -> null
    }

    fun toUnitFile(): String = buildString {
        append("# ").append(MARKER).append(" - edit in the app or by hand\n")
        append("[Unit]\n")
        append("Description=").append(description.ifBlank { name }.replace('\n', ' ')).append('\n')
        if (afterNetwork) append("Wants=network-online.target\nAfter=network-online.target\n")
        append("\n[Service]\nType=simple\n")
        append("User=").append(user).append('\n')
        if (workingDir.isNotBlank()) append("WorkingDirectory=").append(workingDir).append('\n')
        append("ExecStart=/bin/sh -c \"").append(escapeExec(command)).append("\"\n")
        append("Restart=").append(restart).append('\n')
        if (restart != "no") append("RestartSec=5\n")
        append("\n[Install]\nWantedBy=multi-user.target\n")
    }

    companion object {
        const val MARKER = "Created by OSMC Remote"
        private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,60}$")
        private val USER = Regex("^[a-z_][a-z0-9_-]*$")
        private const val EXEC_PREFIX = "/bin/sh -c \""

        /** systemd unquotes C-style escapes and expands % specifiers and \$VARS, so escape all of them. */
        fun escapeExec(cmd: String): String = cmd
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("%", "%%")
            .replace("$", "$$")

        fun unescapeExec(s: String): String {
            val unq = s.replace("$$", "$").replace("%%", "%")
            val sb = StringBuilder()
            var i = 0
            while (i < unq.length) {
                val c = unq[i]
                if (c == '\\' && i + 1 < unq.length) {
                    sb.append(unq[i + 1])
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        fun parse(name: String, text: String): CustomService {
            var svc = CustomService(name = name, afterNetwork = false)
            for (raw in text.lines()) {
                val line = raw.trim()
                val key = line.substringBefore('=', "").trim()
                val value = line.substringAfter('=', "").trim()
                svc = when (key) {
                    "Description" -> svc.copy(description = value)
                    "User" -> svc.copy(user = value)
                    "Restart" -> svc.copy(restart = value)
                    "WorkingDirectory" -> svc.copy(workingDir = value)
                    "After" -> if (value.contains("network-online")) svc.copy(afterNetwork = true) else svc
                    "ExecStart" -> {
                        val cmd = if (value.startsWith(EXEC_PREFIX) && value.endsWith("\"")) {
                            unescapeExec(value.removePrefix(EXEC_PREFIX).removeSuffix("\""))
                        } else value
                        svc.copy(command = cmd)
                    }
                    else -> svc
                }
            }
            if (svc.user.isBlank()) svc = svc.copy(user = "root")
            return svc
        }
    }
}
