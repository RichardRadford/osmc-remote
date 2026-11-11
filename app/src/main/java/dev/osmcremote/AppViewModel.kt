package dev.osmcremote

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.osmcremote.data.DeviceConfig
import dev.osmcremote.data.DeviceStore
import dev.osmcremote.ssh.HostKeyChangedException
import dev.osmcremote.ssh.SshClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.UnknownHostException

sealed interface ConnState {
    data class Idle(val message: String? = null, val isError: Boolean = false) : ConnState
    data object Connecting : ConnState
    data class Connected(val status: Status) : ConnState
}

data class HostKeyPrompt(
    val config: DeviceConfig,
    val remember: Boolean,
    val fingerprint: String,
    val expected: String,
)

data class TermEntry(
    val id: Long,
    val command: String,
    val asRoot: Boolean,
    val output: String = "",
    val code: Int? = null, // null while running
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val store = DeviceStore(app)
    private val ssh = SshClient()
    val osmc = Osmc(ssh, app.assets)

    private val _conn = MutableStateFlow<ConnState>(ConnState.Idle())
    val conn: StateFlow<ConnState> = _conn.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    var hostKeyPrompt by mutableStateOf<HostKeyPrompt?>(null)
        private set

    // Update (apt) progress lives here so it survives switching tabs.
    val upgradeLog = mutableStateListOf<String>()
    var upgradeRunning by mutableStateOf(false)
        private set
    var upgradeExitCode by mutableStateOf<Int?>(null)
        private set
    var upgradeNeedsReboot by mutableStateOf(false)
        private set
    private var upgradeJob: Job? = null

    // Package install/remove job (Services → Add / Remove)
    val pkgLog = mutableStateListOf<String>()
    var pkgTitle by mutableStateOf("")
        private set
    var pkgRunning by mutableStateOf(false)
        private set
    var pkgExitCode by mutableStateOf<Int?>(null)
        private set
    var pkgDialogVisible by mutableStateOf(false)
    private var pkgJob: Job? = null

    val terminal = mutableStateListOf<TermEntry>()
    private var termId = 0L

    fun toast(msg: String) {
        _messages.tryEmit(msg)
    }

    // ---------------------------------------------------------------- connection

    fun connect(cfg: DeviceConfig, remember: Boolean, acceptFingerprint: String? = null) {
        if (_conn.value is ConnState.Connecting) return
        _conn.value = ConnState.Connecting
        viewModelScope.launch {
            try {
                val expected = acceptFingerprint ?: store.fingerprint(cfg.host, cfg.port)
                val fp = ssh.connect(cfg.host.trim(), cfg.port, cfg.user.trim(), cfg.password, expected)
                store.save(cfg, remember)
                store.saveFingerprint(cfg.host, cfg.port, fp)
                osmc.initSudo(cfg.password)
                _conn.value = ConnState.Connected(osmc.status())
                if (osmc.sudoMode == Osmc.SudoMode.Unavailable) {
                    toast("This user can't use sudo, so admin actions will fail.")
                }
            } catch (e: HostKeyChangedException) {
                hostKeyPrompt = HostKeyPrompt(cfg, remember, e.fingerprint, e.expected)
                _conn.value = ConnState.Idle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { ssh.disconnectNow() }
                _conn.value = ConnState.Idle(friendly(e), isError = true)
            }
        }
    }

    fun acceptHostKey() {
        val p = hostKeyPrompt ?: return
        hostKeyPrompt = null
        connect(p.config, p.remember, acceptFingerprint = p.fingerprint)
    }

    fun dismissHostKey() {
        hostKeyPrompt = null
    }

    fun disconnect(message: String? = null) {
        viewModelScope.launch {
            upgradeJob?.cancel()
            pkgJob?.cancel()
            withContext(Dispatchers.IO) { ssh.disconnectNow() }
            _conn.value = ConnState.Idle(message)
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Runs [block] in the ViewModel scope and turns failures into a snackbar or a reconnect prompt. */
    fun launchOp(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleError(e)
        }
    }

    private fun handleError(e: Exception) {
        if (!ssh.isConnected && _conn.value is ConnState.Connected) {
            _conn.value = ConnState.Idle("Connection lost. " + friendly(e), isError = true)
        } else {
            toast(friendly(e))
        }
    }

    fun friendly(e: Throwable): String {
        val msg = e.message.orEmpty()
        val cause = e.cause
        return when {
            e is OsmcException -> msg
            msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) ->
                "Wrong username or password."
            e is UnknownHostException || cause is UnknownHostException || msg.contains("UnknownHost") ->
                "Can't find that host. Check the IP address or hostname."
            msg.contains("timeout", true) || msg.contains("timed out", true) ->
                "Connection timed out. Is the device on and on the same network?"
            msg.contains("refused", true) ->
                "Connection refused. Is SSH enabled on the device (My OSMC → Services)?"
            msg.contains("No route", true) || msg.contains("unreachable", true) ->
                "The device isn't reachable from this network."
            else -> msg.ifBlank { e.javaClass.simpleName }
        }
    }

    /** Called every few seconds by the dashboard. */
    suspend fun pollStatus() {
        try {
            val s = osmc.status()
            if (_conn.value is ConnState.Connected) _conn.value = ConnState.Connected(s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!ssh.isConnected) handleError(e)
        }
    }

    // ---------------------------------------------------------------- power

    fun power(action: PowerAction) = launchOp {
        when (action) {
            PowerAction.Reboot, PowerAction.Shutdown -> {
                try {
                    withTimeoutOrNull(8_000) { osmc.power(action) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // the connection dropping mid-command is expected here
                }
                upgradeJob?.cancel()
                withContext(Dispatchers.IO) { ssh.disconnectNow() }
                _conn.value = ConnState.Idle(
                    if (action == PowerAction.Reboot) "The device is rebooting. Reconnect in a minute or so."
                    else "The device is shutting down."
                )
            }
            else -> {
                osmc.power(action)
                toast(
                    when (action) {
                        PowerAction.RestartKodi -> "Kodi is restarting"
                        PowerAction.StopKodi -> "Kodi stopped"
                        else -> "Kodi started"
                    }
                )
                pollStatus()
            }
        }
    }

    // ---------------------------------------------------------------- updates

    fun startUpgrade() {
        if (upgradeJob?.isActive == true) return
        if (pkgJob?.isActive == true) {
            toast("A package install or removal is still running. Wait for it to finish.")
            return
        }
        upgradeLog.clear()
        upgradeExitCode = null
        upgradeNeedsReboot = false
        upgradeJob = launchOp {
            upgradeRunning = true
            try {
                osmc.startUpgrade()
                followUpgrade()
            } finally {
                upgradeRunning = false
            }
        }
    }

    /** If an update started earlier is still running on the device, show its live log again. */
    fun reattachUpgrade() {
        if (upgradeJob?.isActive == true) return
        upgradeJob = launchOp {
            if (osmc.isUpgradeRunning()) {
                upgradeLog.clear()
                upgradeExitCode = null
                upgradeNeedsReboot = false
                upgradeRunning = true
                try {
                    followUpgrade()
                } finally {
                    upgradeRunning = false
                }
            }
        }
    }

    private suspend fun followUpgrade() {
        osmc.followUpgrade { line ->
            // called on an IO thread; hop to main to touch Compose state, in order
            viewModelScope.launch(Dispatchers.Main) { onUpgradeLine(line) }
        }
    }

    private fun onUpgradeLine(line: String) {
        when {
            line.startsWith(Osmc.DONE_MARKER) ->
                upgradeExitCode = line.removePrefix(Osmc.DONE_MARKER).trim().toIntOrNull() ?: 1
            line.startsWith(Osmc.REBOOT_MARKER) -> upgradeNeedsReboot = true
            else -> {
                upgradeLog.add(line)
                if (upgradeLog.size > 5000) repeat(500) { upgradeLog.removeAt(0) }
            }
        }
    }

    // ---------------------------------------------------------------- packages

    fun startPackageJob(title: String, install: List<String>, remove: List<String>) {
        if (pkgJob?.isActive == true || upgradeJob?.isActive == true) {
            toast("Another install or update is still running. Wait for it to finish.")
            return
        }
        pkgJob = launchOp {
            if (osmc.isAptBusy()) {
                toast("An update or install is already running on the device. Try again when it finishes.")
                return@launchOp
            }
            pkgLog.clear()
            pkgExitCode = null
            pkgTitle = title
            pkgDialogVisible = true
            pkgRunning = true
            try {
                osmc.startPackageJob(install, remove)
                osmc.followPackageJob { line ->
                    viewModelScope.launch(Dispatchers.Main) { onPkgLine(line) }
                }
            } finally {
                pkgRunning = false
            }
        }
    }

    /** Re-show a package job that's still running on the device (e.g. after the app was closed). */
    fun reattachPackageJob() {
        if (pkgJob?.isActive == true) return
        pkgJob = launchOp {
            if (osmc.isPackageJobRunning()) {
                pkgLog.clear()
                pkgExitCode = null
                if (pkgTitle.isBlank()) pkgTitle = "Package changes in progress"
                pkgRunning = true
                try {
                    osmc.followPackageJob { line ->
                        viewModelScope.launch(Dispatchers.Main) { onPkgLine(line) }
                    }
                } finally {
                    pkgRunning = false
                }
            }
        }
    }

    private fun onPkgLine(line: String) {
        if (line.startsWith(Osmc.DONE_MARKER)) {
            pkgExitCode = line.removePrefix(Osmc.DONE_MARKER).trim().toIntOrNull() ?: 1
        } else {
            pkgLog.add(line)
            if (pkgLog.size > 5000) repeat(500) { pkgLog.removeAt(0) }
        }
    }

    // ---------------------------------------------------------------- terminal

    fun runTerminal(cmd: String, asRoot: Boolean) {
        val id = ++termId
        terminal.add(TermEntry(id, cmd, asRoot))
        viewModelScope.launch {
            val updated = try {
                val r = if (asRoot) osmc.sudo(cmd) else osmc.run(cmd)
                TermEntry(id, cmd, asRoot, r.combined, r.code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!ssh.isConnected) handleError(e)
                TermEntry(id, cmd, asRoot, friendly(e), -1)
            }
            val i = terminal.indexOfFirst { it.id == id }
            if (i >= 0) terminal[i] = updated
        }
    }

    override fun onCleared() {
        Thread { ssh.disconnectNow() }.start()
        super.onCleared()
    }
}
