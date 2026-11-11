package dev.osmcremote

import android.content.res.AssetManager
import dev.osmcremote.ssh.ExecResult
import dev.osmcremote.ssh.SshClient

/** Everything the app knows how to do on an OSMC box, expressed as shell commands. */
class Osmc(private val ssh: SshClient, private val assets: AssetManager) {

    enum class SudoMode { Unknown, NoPassword, Password, Unavailable }

    private var password: String = ""

    var sudoMode: SudoMode = SudoMode.Unknown
        private set

    // ---------------------------------------------------------------- basics

    private fun script(name: String): String =
        assets.open("sh/$name").bufferedReader().use { it.readText() }

    /** OSMC's `osmc` user has passwordless sudo by default; fall back to the login password. */
    suspend fun initSudo(pw: String) {
        password = pw
        sudoMode = when {
            ssh.exec("sudo -n true").ok -> SudoMode.NoPassword
            ssh.exec("sudo -S -p '' true", pw + "\n").ok -> SudoMode.Password
            else -> SudoMode.Unavailable
        }
    }

    suspend fun run(cmd: String): ExecResult = ssh.exec(cmd)

    suspend fun sudo(cmd: String): ExecResult = when (sudoMode) {
        SudoMode.NoPassword -> ssh.exec("sudo sh -c " + q(cmd))
        SudoMode.Password -> ssh.exec("sudo -S -p '' sh -c " + q(cmd), password + "\n")
        else -> throw OsmcException("This user can't use sudo, so admin actions aren't available.")
    }

    private fun ExecResult.orThrow(what: String): ExecResult {
        if (!ok) throw OsmcException("$what failed: " + combined.ifBlank { "exit code $code" }.take(400))
        return this
    }

    private suspend fun writeUserFile(path: String, content: String, secret: Boolean = false) {
        val prefix = if (secret) "umask 077; " else ""
        ssh.exec(prefix + "cat > " + q(path), content).orThrow("Writing $path")
    }

    // ---------------------------------------------------------------- status

    suspend fun status(): Status {
        val r = ssh.exec("sh -s", script("status.sh"))
        val m = r.out.lines()
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        val mem = m["MEM"].orEmpty().split(' ').mapNotNull { it.toLongOrNull() }
        val disk = m["DISK"].orEmpty().split(' ').mapNotNull { it.toLongOrNull() }
        return Status(
            model = m["MODEL"].orEmpty().ifBlank { "OSMC device" },
            host = m["HOST"].orEmpty().ifBlank { "osmc" },
            os = m["OS"].orEmpty(),
            kodi = m["KODI"].orEmpty(),
            kernel = m["KERNEL"].orEmpty(),
            uptimeSec = m["UPTIME"]?.toDoubleOrNull() ?: 0.0,
            load = m["LOAD"].orEmpty().split(' ').mapNotNull { it.toDoubleOrNull() },
            cpus = m["CPUS"]?.toIntOrNull() ?: 1,
            tempC = m["TEMP"]?.toDoubleOrNull()?.let { if (it > 1000) it / 1000.0 else it },
            memTotal = mem.getOrElse(0) { 0L },
            memUsed = mem.getOrElse(1) { 0L },
            memAvailable = mem.getOrElse(2) { 0L },
            diskTotal = disk.getOrElse(0) { 0L },
            diskUsed = disk.getOrElse(1) { 0L },
            ip = m["IP"].orEmpty().split(' ').firstOrNull { it.isNotBlank() }.orEmpty(),
            kodiState = m["KODI_STATE"].orEmpty(),
            throttled = m["THROTTLED"]?.takeIf { it.isNotBlank() },
            cpuFreqMHz = m["CPUFREQ"]?.toLongOrNull()?.let { (it / 1000).toInt() },
        )
    }

    // ---------------------------------------------------------------- power

    suspend fun power(action: PowerAction) {
        when (action) {
            PowerAction.RestartKodi -> sudo("systemctl restart mediacenter").orThrow("Restarting Kodi")
            PowerAction.StopKodi -> sudo("systemctl stop mediacenter").orThrow("Stopping Kodi")
            PowerAction.StartKodi -> sudo("systemctl start mediacenter").orThrow("Starting Kodi")
            PowerAction.Reboot -> sudo("systemctl reboot")
            PowerAction.Shutdown -> sudo("systemctl poweroff")
        }
    }

    // ---------------------------------------------------------------- updates

    suspend fun checkUpdates(): UpdateCheck {
        val r = sudo(
            "apt-get update -qq 2>&1 | grep -E '^(E|W|Err):' ; " +
                "apt list --upgradable 2>/dev/null"
        )
        val warnings = mutableListOf<String>()
        val pkgs = mutableListOf<Upgradable>()
        for (line in r.out.lines()) {
            val t = line.trim()
            when {
                t.startsWith("E:") || t.startsWith("W:") || t.startsWith("Err:") -> warnings += t
                t.contains("[upgradable from:") -> {
                    val parts = t.split(' ')
                    pkgs += Upgradable(
                        name = t.substringBefore('/'),
                        newVersion = parts.getOrElse(1) { "" },
                        oldVersion = t.substringAfter("from:").trim().removeSuffix("]"),
                    )
                }
            }
        }
        return UpdateCheck(pkgs, warnings)
    }

    suspend fun startUpgrade() {
        writeUserFile(UPGRADE_SCRIPT, script("upgrade.sh"))
        sudo(
            "rm -f $UPGRADE_LOG; " +
                "setsid nohup sh $UPGRADE_SCRIPT > $UPGRADE_LOG 2>&1 < /dev/null &"
        ).orThrow("Starting the update")
    }

    suspend fun isUpgradeRunning(): Boolean =
        run("pgrep -f '[o]smcremote-upgrade.sh' >/dev/null && echo yes").out.trim() == "yes"

    suspend fun followUpgrade(onLine: (String) -> Unit): Int =
        ssh.stream("tail -n +1 -F $UPGRADE_LOG 2>/dev/null", onLine) { it.startsWith(DONE_MARKER) }

    // ---------------------------------------------------------------- boot config

    suspend fun configFiles(): List<String> {
        val candidates = listOf(
            "/boot/config-user.txt", "/boot/config.txt", "/boot/firmware/config.txt",
            "/boot/cmdline.txt", "/boot/uEnv.txt", "/boot/env.txt",
        )
        val cmd = "for f in " + candidates.joinToString(" ") + "; do [ -f \"\$f\" ] && echo \"\$f\"; done"
        return run(cmd).out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun readFile(path: String): String {
        val r = run("cat " + q(path))
        if (r.ok) return r.out
        return sudo("cat " + q(path)).orThrow("Reading $path").out
    }

    /** Backs the file up to <file>.osmcremote.bak, then replaces it. */
    suspend fun writeFile(path: String, content: String) {
        writeUserFile(EDIT_TMP, content)
        sudo(
            "cp " + q(path) + " " + q("$path.osmcremote.bak") + " 2>/dev/null; " +
                "cp $EDIT_TMP " + q(path) + " && rm -f $EDIT_TMP && sync"
        ).orThrow("Saving $path")
    }

    // ---------------------------------------------------------------- services

    suspend fun services(): List<ServiceInfo> {
        val r = ssh.exec("sh -s", script("services.sh"))
        val files = HashMap<String, String>()
        val units = HashMap<String, Pair<String, String>>()
        for (line in r.out.lines()) {
            val p = line.trim().split(Regex("\\s+"))
            when (p.getOrNull(0)) {
                "F" -> if (p.size >= 3) files[p[1]] = p[2]
                "U" -> if (p.size >= 4) units[p[1]] = p[2] to p[3]
            }
        }
        return (files.keys + units.keys)
            .filter { it.endsWith(".service") && !it.endsWith("@.service") }
            .distinct()
            .map { n ->
                ServiceInfo(
                    name = n,
                    unitFileState = files[n] ?: "static",
                    active = units[n]?.first ?: "inactive",
                    sub = units[n]?.second ?: "dead",
                )
            }
            .sortedBy { it.name }
    }

    suspend fun serviceAction(name: String, action: String) {
        require(action in setOf("start", "stop", "restart", "enable", "disable"))
        sudo("systemctl $action " + q(name)).orThrow("systemctl $action")
    }

    suspend fun serviceLog(name: String): String =
        sudo("journalctl -u " + q(name) + " -n 150 --no-pager 2>&1").combined

    // ---------------------------------------------------------------- network

    suspend fun network(scan: Boolean): NetworkInfo {
        if (scan) sudo("connmanctl scan wifi")
        val addresses = run("ip -brief addr 2>/dev/null || ip addr").out.trim()
        val techText = sudo("connmanctl technologies").out
        val svcText = sudo("connmanctl services").out

        val techs = mutableListOf<NetTech>()
        var type: String? = null
        var powered = false
        var connected = false
        for (l in techText.lines()) {
            val t = l.trim()
            if (t.startsWith("/net/connman/technology/")) {
                type?.let { techs += NetTech(it, powered, connected) }
                type = t.substringAfterLast('/')
                powered = false
                connected = false
            } else if (t.startsWith("Powered")) {
                powered = t.endsWith("True")
            } else if (t.startsWith("Connected")) {
                connected = t.endsWith("True")
            }
        }
        type?.let { techs += NetTech(it, powered, connected) }

        val services = svcText.lines().mapNotNull { line ->
            if (line.length < 5) return@mapNotNull null
            val flags = line.take(4)
            val rest = line.drop(4).trim()
            if (!rest.contains(' ')) return@mapNotNull null // hidden network: id only
            val id = rest.substringAfterLast(' ')
            val name = rest.substringBeforeLast(' ').trim()
            if (name.isEmpty()) null else NetService(id, name, flags)
        }
        return NetworkInfo(addresses, techs, services)
    }

    suspend fun setTechPowered(type: String, on: Boolean) {
        sudo("connmanctl " + (if (on) "enable " else "disable ") + q(type)).orThrow("Changing $type")
    }

    suspend fun connectService(id: String) {
        sudo("connmanctl connect " + q(id)).orThrow("Connecting")
    }

    suspend fun disconnectService(id: String) {
        sudo("connmanctl disconnect " + q(id)).orThrow("Disconnecting")
    }

    /** Uses ConnMan's provisioning files so no interactive agent is needed. */
    suspend fun connectWifi(svc: NetService, passphrase: String) {
        val safe = svc.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        val cfg = buildString {
            append("[service_osmcremote_").append(safe).append("]\n")
            append("Type = wifi\n")
            append("Name = ").append(svc.name).append('\n')
            append("Passphrase = ").append(passphrase).append('\n')
        }
        val tmp = "/tmp/osmcremote-wifi.config"
        val dest = "/var/lib/connman/osmcremote-$safe.config"
        writeUserFile(tmp, cfg, secret = true)
        sudo(
            "mv $tmp " + q(dest) + " && chown root:root " + q(dest) + " && chmod 600 " + q(dest) +
                "; sleep 2; connmanctl connect " + q(svc.id)
        ).orThrow("Connecting to ${svc.name}")
    }

    // ---------------------------------------------------------------- logs

    suspend fun readLog(src: LogSource): String = when (src) {
        LogSource.Kodi -> run("tail -n 500 ~/.kodi/temp/kodi.log 2>&1").combined
        LogSource.System -> sudo("journalctl -b --no-pager -n 500 2>&1").combined
        LogSource.Kernel -> sudo("dmesg 2>&1 | tail -n 500").combined
        LogSource.Updates -> run("tail -n 500 $UPGRADE_LOG 2>&1").combined
    }

    /** OSMC's own log uploader; returns the paste URL. */
    suspend fun uploadLogs(): String {
        val r = run("grab-logs -A 2>&1")
        val url = Regex("https?://\\S+").findAll(r.combined).map { it.value.trimEnd('.', ',') }.lastOrNull()
        return url ?: throw OsmcException("Log upload failed: " + r.combined.takeLast(300))
    }

    companion object {
        const val UPGRADE_SCRIPT = "/tmp/osmcremote-upgrade.sh"
        const val UPGRADE_LOG = "/tmp/osmcremote-upgrade.log"
        const val EDIT_TMP = "/tmp/osmcremote-edit.tmp"
        const val DONE_MARKER = "__OSMCREMOTE_DONE__"
        const val REBOOT_MARKER = "__OSMCREMOTE_REBOOT__"

        /** Single-quote a string for POSIX sh. */
        fun q(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"
    }
}
