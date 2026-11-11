package dev.osmcremote.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.util.Properties

/** Thrown when the device presents a different host key than the one saved earlier. */
class HostKeyChangedException(val fingerprint: String, val expected: String) :
    Exception("The device's SSH host key has changed")

data class ExecResult(val out: String, val err: String, val code: Int) {
    val ok: Boolean get() = code == 0

    /** stdout and stderr together, trimmed. */
    val combined: String
        get() = listOf(out.trimEnd(), err.trimEnd()).filter { it.isNotEmpty() }.joinToString("\n")
}

/**
 * Small coroutine-friendly wrapper around a single JSch session.
 * Every blocking call runs on [Dispatchers.IO] and is interruptible, so
 * cancelling the calling coroutine also tears down the SSH channel.
 */
class SshClient {
    private val jsch = JSch()

    @Volatile
    private var session: Session? = null

    val isConnected: Boolean get() = session?.isConnected == true

    /**
     * Connects with a password. Returns the server's host key fingerprint.
     * If [expectedFingerprint] is set and differs, the connection is dropped
     * and [HostKeyChangedException] is thrown (trust on first use).
     */
    suspend fun connect(
        host: String,
        port: Int,
        user: String,
        password: String,
        expectedFingerprint: String?,
    ): String = runInterruptible(Dispatchers.IO) {
        disconnectNow()
        val s = jsch.getSession(user, host, port)
        s.setPassword(password)
        s.setUserInfo(PasswordUserInfo(password))
        val cfg = Properties()
        cfg.setProperty("StrictHostKeyChecking", "no")
        cfg.setProperty("PreferredAuthentications", "password,keyboard-interactive")
        s.setConfig(cfg)
        s.setServerAliveInterval(15_000)
        s.setServerAliveCountMax(3)
        s.connect(12_000)
        val fp = s.hostKey.getFingerPrint(jsch)
        if (expectedFingerprint != null && expectedFingerprint != fp) {
            s.disconnect()
            throw HostKeyChangedException(fp, expectedFingerprint)
        }
        session = s
        fp
    }

    fun disconnectNow() {
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        session = null
    }

    private fun requireSession(): Session =
        session?.takeIf { it.isConnected } ?: throw IllegalStateException("Not connected to the device")

    /**
     * Runs [command] and waits for it to finish. [stdin] is written then closed,
     * so commands that unexpectedly prompt for input get EOF instead of hanging.
     */
    suspend fun exec(command: String, stdin: String? = null): ExecResult = runInterruptible(Dispatchers.IO) {
        val ch = requireSession().openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val err = ByteArrayOutputStream()
        ch.setErrStream(err)
        val input = ch.inputStream
        val output = ch.outputStream
        try {
            ch.connect(10_000)
            if (stdin != null) output.write(stdin.toByteArray(Charsets.UTF_8))
            output.flush()
            output.close()
            val bytes = input.readBytes()
            var waited = 0
            while (!ch.isClosed && waited < 5_000) {
                Thread.sleep(20)
                waited += 20
            }
            ExecResult(String(bytes, Charsets.UTF_8), err.toString("UTF-8"), ch.exitStatus)
        } finally {
            ch.disconnect()
        }
    }

    /**
     * Runs a long-lived [command] and hands each output line to [onLine]
     * (called on a background thread). Stops when the command ends, when
     * [stopWhen] returns true, or when the coroutine is cancelled.
     */
    suspend fun stream(
        command: String,
        onLine: (String) -> Unit,
        stopWhen: (String) -> Boolean = { false },
    ): Int = runInterruptible(Dispatchers.IO) {
        val ch = requireSession().openChannel("exec") as ChannelExec
        ch.setCommand(command)
        ch.setErrStream(ByteArrayOutputStream())
        val input = ch.inputStream
        val output = ch.outputStream
        try {
            ch.connect(10_000)
            output.close()
            val reader = input.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                onLine(line)
                if (stopWhen(line)) break
            }
            ch.exitStatus
        } finally {
            ch.disconnect()
        }
    }

    private class PasswordUserInfo(private val pw: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = pw
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = true
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<String>?,
            echo: BooleanArray?,
        ): Array<String> = Array(prompt?.size ?: 0) { pw }
    }
}
