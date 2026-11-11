package dev.osmcremote

import android.content.res.AssetManager
import dev.osmcremote.ssh.ExecResult
import dev.osmcremote.ssh.SshClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

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
        rpcHelperReady = false
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

    // Long-running root jobs (apt) run detached on the device with setsid + nohup,
    // logging to /tmp/osmcremote-<name>.log, so they survive the app disconnecting.
    // Scripts end by printing "__OSMCREMOTE_DONE__ <exit code>".

    private suspend fun startDetached(name: String, scriptText: String, what: String) {
        val sh = "/tmp/osmcremote-$name.sh"
        val log = "/tmp/osmcremote-$name.log"
        writeUserFile(sh, scriptText)
        sudo("rm -f $log; setsid nohup sh $sh > $log 2>&1 < /dev/null &").orThrow(what)
    }

    private suspend fun isDetachedRunning(name: String): Boolean =
        run("pgrep -f '[o]smcremote-$name.sh' >/dev/null && echo yes").out.trim() == "yes"

    private suspend fun followDetached(name: String, onLine: (String) -> Unit): Int =
        ssh.stream("tail -n +1 -F /tmp/osmcremote-$name.log 2>/dev/null", onLine) { it.startsWith(DONE_MARKER) }

    /** True while any apt job started by this app is still running on the device. */
    suspend fun isAptBusy(): Boolean = isDetachedRunning("upgrade") || isDetachedRunning("pkg")

    suspend fun startUpgrade() = startDetached("upgrade", script("upgrade.sh"), "Starting the update")
    suspend fun isUpgradeRunning(): Boolean = isDetachedRunning("upgrade")
    suspend fun followUpgrade(onLine: (String) -> Unit): Int = followDetached("upgrade", onLine)

    // ---------------------------------------------------------------- packages (add / remove services)

    /** OSMC App Store items are ordinary packages named like "tvheadend-app-osmc". */
    suspend fun appStore(): List<AppPackage> {
        val avail = run("apt-cache search --names-only 'app-osmc' 2>/dev/null").out
        val installed = run(
            "dpkg-query -W -f='\${db:Status-Abbrev} \${Package}\\n' '*app-osmc*' 2>/dev/null"
        ).out.lines()
            .filter { it.startsWith("ii") }
            .map { it.trim().split(Regex("\\s+")).last() }
            .toSet()
        val list = avail.lines().mapNotNull { line ->
            val name = line.substringBefore(" - ").trim()
            if (name.isEmpty() || !name.contains("app-osmc")) null
            else AppPackage(name, line.substringAfter(" - ", "").trim(), name in installed)
        }
        // installed apps whose package lists are stale still show up
        val extra = installed.filter { n -> list.none { it.name == n } }.map { AppPackage(it, "", true) }
        return (list + extra).sortedBy { it.displayName.lowercase() }
    }

    suspend fun packageExists(pkg: String): Boolean {
        checkPackageName(pkg)
        return run("apt-cache show " + q(pkg) + " >/dev/null 2>&1 && echo yes").out.trim() == "yes"
    }

    suspend fun isInstalled(pkg: String): Boolean =
        run("dpkg-query -W -f='\${db:Status-Abbrev}' " + q(pkg) + " 2>/dev/null").out.startsWith("ii")

    /** Asks apt what removing [pkg] would also remove, and flags anything the app must not touch. */
    suspend fun planRemoval(pkg: String): RemovalPlan {
        checkPackageName(pkg)
        val r = run("LANG=C apt-get -s remove " + q(pkg) + " 2>&1")
        val removed = r.out.lines()
            .filter { it.startsWith("Remv ") }
            .map { it.removePrefix("Remv ").substringBefore(' ') }
        if (removed.isEmpty()) {
            throw OsmcException(
                if (r.ok) "$pkg isn't installed." else "apt couldn't plan the removal: " + r.combined.takeLast(300)
            )
        }
        return RemovalPlan(removed, removed.filter { isProtectedPackage(it) })
    }

    suspend fun startPackageJob(install: List<String>, remove: List<String>) {
        (install + remove).forEach { checkPackageName(it) }
        val opts = "-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold"
        val text = buildString {
            append("#!/bin/sh\nexport DEBIAN_FRONTEND=noninteractive\nrc=0\n")
            if (install.isNotEmpty()) {
                append("echo '== Refreshing package lists =='\napt-get update\necho\n")
                append("echo '== Installing ").append(install.joinToString(" ")).append(" =='\n")
                append("apt-get $opts install ").append(install.joinToString(" ") { q(it) }).append(" || rc=\$?\n")
            }
            if (remove.isNotEmpty()) {
                append("echo '== Removing ").append(remove.joinToString(" ")).append(" =='\n")
                append("apt-get $opts remove ").append(remove.joinToString(" ") { q(it) }).append(" || rc=\$?\n")
            }
            append("systemctl daemon-reload\n")
            append("echo \"$DONE_MARKER \$rc\"\n")
        }
        startDetached("pkg", text, "Starting the package job")
    }

    suspend fun isPackageJobRunning(): Boolean = isDetachedRunning("pkg")
    suspend fun followPackageJob(onLine: (String) -> Unit): Int = followDetached("pkg", onLine)

    // ---------------------------------------------------------------- custom services

    /** Where a service's unit file comes from, which decides how it can be removed. */
    suspend fun serviceOrigin(unit: String): ServiceOrigin {
        val path = run("systemctl show -p FragmentPath --value " + q(unit)).out.trim()
        if (path.isEmpty()) return ServiceOrigin.Unknown("systemd doesn't report a unit file for it.")
        if (path == "/dev/null") return ServiceOrigin.Unknown("It's masked.")
        val alt = when {
            path.startsWith("/usr/lib/") -> path.removePrefix("/usr")
            path.startsWith("/lib/") -> "/usr$path"
            else -> path
        }
        val owner = run(
            "for p in " + q(path) + " " + q(alt) + "; do dpkg -S \"\$p\" 2>/dev/null && break; done | head -n 1"
        ).out.trim()
        if (owner.contains(": ")) {
            val pkg = owner.substringBefore(": ").split(", ").first().trim()
            return ServiceOrigin.Package(pkg, path)
        }
        if (path.startsWith("/etc/systemd/system/")) {
            val ours = run("grep -q " + q(CUSTOM_MARKER) + " " + q(path) + " && echo yes").out.trim() == "yes"
            return ServiceOrigin.UnitFile(path, ours)
        }
        return ServiceOrigin.Unknown("Its unit file ($path) doesn't belong to any package.")
    }

    suspend fun readCustomService(unit: String): CustomService {
        val text = run("cat " + q("/etc/systemd/system/$unit")).orThrow("Reading $unit").out
        return CustomService.parse(unit.removeSuffix(".service"), text)
    }

    suspend fun saveCustomService(svc: CustomService, isNew: Boolean, enable: Boolean, startNow: Boolean) {
        svc.validate()?.let { throw OsmcException(it) }
        val unit = svc.unitName
        val dest = "/etc/systemd/system/$unit"
        if (isNew) {
            val exists = run("systemctl cat " + q(unit) + " >/dev/null 2>&1 && echo yes; [ -e " + q(dest) + " ] && echo yes")
            if (exists.out.contains("yes")) throw OsmcException("A service called ${svc.name} already exists.")
        }
        if (svc.user != "root") {
            if (!run("id -u " + q(svc.user)).ok) throw OsmcException("There's no user called ${svc.user} on the device.")
        }
        writeUserFile(UNIT_TMP, svc.toUnitFile())
        sudo(
            "install -m 644 -o root -g root $UNIT_TMP " + q(dest) + " && rm -f $UNIT_TMP && systemctl daemon-reload"
        ).orThrow("Saving $unit")
        sudo("systemctl " + (if (enable) "enable " else "disable ") + q(unit)).orThrow(if (enable) "Enabling at boot" else "Disabling at boot")
        if (startNow) {
            sudo("systemctl restart " + q(unit)).orThrow("Starting $unit")
        }
    }

    /** Stops, disables and deletes a unit file in /etc/systemd/system (a copy is kept as .osmcremote.bak). */
    suspend fun deleteUnitFile(unit: String, path: String) {
        require(path.startsWith("/etc/systemd/system/") && path.endsWith(".service"))
        sudo(
            "systemctl disable --now " + q(unit) + " 2>/dev/null; " +
                "cp " + q(path) + " " + q("$path.osmcremote.bak") + " && rm -f " + q(path) + " && systemctl daemon-reload"
        ).orThrow("Deleting $unit")
        sudo("systemctl reset-failed " + q(unit) + " 2>/dev/null")
    }

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
        val custom = HashSet<String>()
        for (line in r.out.lines()) {
            val p = line.trim().split(Regex("\\s+"))
            when (p.getOrNull(0)) {
                "F" -> if (p.size >= 3) files[p[1]] = p[2]
                "U" -> if (p.size >= 4) units[p[1]] = p[2] to p[3]
                "C" -> if (p.size >= 2) custom += p[1]
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
                    custom = n in custom,
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

    // ---------------------------------------------------------------- Kodi audio

    private var rpcHelperReady = false

    /**
     * Calls Kodi's JSON-RPC API from the device itself: first over the local TCP
     * port 9090 (via a small Python helper), then over HTTP on 127.0.0.1:8080.
     */
    suspend fun kodiRpc(method: String, params: JSONObject = JSONObject()): Any? {
        if (!rpcHelperReady) {
            writeUserFile(RPC_HELPER, script("kodirpc.py"))
            rpcHelperReady = true
        }
        val req = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)
            .toString()
        var r = ssh.exec("python3 $RPC_HELPER", req)
        if (!r.ok || !r.out.trim().startsWith("{")) {
            val http = ssh.exec(
                "curl -s -m 8 -H 'Content-Type: application/json' --data-binary @- http://127.0.0.1:8080/jsonrpc",
                req,
            )
            if (http.ok && http.out.trim().startsWith("{")) {
                r = http
            } else {
                throw OsmcException(
                    "Couldn't reach Kodi. Check that Kodi is running and that, in Kodi, " +
                        "Settings → Services → Control → “Allow remote control from applications on this system” is on."
                )
            }
        }
        val obj = JSONObject(r.out.trim())
        obj.optJSONObject("error")?.let { throw OsmcException("Kodi said: " + it.optString("message")) }
        return obj.opt("result")
    }

    suspend fun kodiVolume(): KodiVolume {
        val res = kodiRpc(
            "Application.GetProperties",
            JSONObject().put("properties", JSONArray(listOf("volume", "muted"))),
        ) as? JSONObject ?: throw OsmcException("Kodi returned no volume")
        return KodiVolume(res.optInt("volume"), res.optBoolean("muted"))
    }

    suspend fun setKodiVolume(volume: Int) {
        kodiRpc("Application.SetVolume", JSONObject().put("volume", volume.coerceIn(0, 100)))
    }

    suspend fun setKodiMute(mute: Boolean) {
        kodiRpc("Application.SetMute", JSONObject().put("mute", mute))
    }

    /** Every setting in Kodi's Settings → System → Audio, at expert level. */
    suspend fun kodiAudioSettings(): List<KodiSetting> {
        val res = kodiRpc(
            "Settings.GetSettings",
            JSONObject()
                .put("level", "expert")
                .put("filter", JSONObject().put("section", "system").put("category", "audio")),
        ) as? JSONObject ?: return emptyList()
        val arr = res.optJSONArray("settings") ?: return emptyList()
        val out = mutableListOf<KodiSetting>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val opts = mutableListOf<KodiOption>()
            o.optJSONArray("options")?.let { oa ->
                for (j in 0 until oa.length()) {
                    val opt = oa.optJSONObject(j) ?: continue
                    val v = opt.opt("value") ?: continue
                    opts += KodiOption(opt.optString("label", v.toString()), v)
                }
            }
            out += KodiSetting(
                id = o.optString("id"),
                label = o.optString("label", o.optString("id")),
                help = o.optString("help", ""),
                type = o.optString("type"),
                value = o.opt("value"),
                options = opts,
                min = if (o.has("minimum")) o.optDouble("minimum") else null,
                max = if (o.has("maximum")) o.optDouble("maximum") else null,
                step = if (o.has("step")) o.optDouble("step") else null,
                enabled = o.optBoolean("enabled", true),
            )
        }
        return out
    }

    suspend fun setKodiSetting(s: KodiSetting, value: Any) {
        val typed: Any = when (s.type) {
            "boolean" -> value as? Boolean ?: value.toString().toBoolean()
            "integer" -> (value as? Number)?.toInt() ?: value.toString().toDouble().toInt()
            "number" -> (value as? Number)?.toDouble() ?: value.toString().toDouble()
            else -> value.toString()
        }
        kodiRpc("Settings.SetSettingValue", JSONObject().put("setting", s.id).put("value", typed))
    }

    /** Default volume amplification in dB (Kodi's "Set as default for all media"). Null if never saved. */
    suspend fun amplification(): Double? =
        ssh.exec("sh -s get", script("amp.sh")).out.trim().toDoubleOrNull()

    /** Kodi rewrites its settings file on exit, so it is stopped while the file is edited. */
    suspend fun setAmplification(db: Double) {
        val wasRunning = run("systemctl is-active mediacenter").out.trim() == "active"
        if (wasRunning) sudo("systemctl stop mediacenter").orThrow("Stopping Kodi")
        try {
            val value = String.format(Locale.US, "%.6f", db.coerceIn(0.0, 60.0))
            ssh.exec("sh -s set " + q(value), script("amp.sh")).orThrow("Saving the boost")
        } finally {
            if (wasRunning) sudo("systemctl start mediacenter")
        }
    }

    companion object {
        const val RPC_HELPER = "/tmp/osmcremote-kodirpc.py"
        const val UPGRADE_SCRIPT = "/tmp/osmcremote-upgrade.sh"
        const val UPGRADE_LOG = "/tmp/osmcremote-upgrade.log"
        const val EDIT_TMP = "/tmp/osmcremote-edit.tmp"
        const val DONE_MARKER = "__OSMCREMOTE_DONE__"
        const val REBOOT_MARKER = "__OSMCREMOTE_REBOOT__"

        const val UNIT_TMP = "/tmp/osmcremote-unit.tmp"
        const val CUSTOM_MARKER = "Created by OSMC Remote"

        private val PACKAGE_NAME = Regex("^[a-z0-9][a-z0-9+.-]+(:[a-z0-9]+)?$")

        /** Packages whose removal would break the device or cut off this SSH connection. */
        private val PROTECTED = Regex(
            "mediacenter|systemd|dbus|connman|ssh|sudo|udev|base-files|libc6|^apt|dpkg|kernel|" +
                "firmware|bootloader|coreutils|util-linux|login|^init|bash|^dash|python3|osmc-release|" +
                "diskmount|wpasupplicant|network"
        )

        fun isProtectedPackage(pkg: String): Boolean = PROTECTED.containsMatchIn(pkg)

        fun checkPackageName(pkg: String) {
            if (!PACKAGE_NAME.matches(pkg)) throw OsmcException("“$pkg” isn't a valid package name.")
        }

        /** Single-quote a string for POSIX sh. */
        fun q(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"
    }
}
