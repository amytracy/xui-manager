package dev.autorasqr.xuimanager.data.ssh

import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.AppException
import dev.autorasqr.xuimanager.domain.model.SudoMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ExecResult(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
    val stdoutText: String get() = stdout.decodeToString()
    val ok: Boolean get() = exitCode == 0
}

class SshSession internal constructor(
    private val client: SSHClient,
    /** Used only to answer `sudo -S`; never written anywhere else. */
    private val loginPassword: String?,
) {
    suspend fun exec(command: String, stdin: ByteArray? = null, timeoutSeconds: Long = 60): ExecResult =
        withContext(Dispatchers.IO) {
            client.startSession().use { session ->
                val cmd = session.exec(command)
                coroutineScope {
                    // Closing the channel unblocks the reads below if the remote side hangs. The flag is set
                    // before closing so a read that returns early is never mistaken for a complete result.
                    val timedOut = AtomicBoolean(false)
                    val watchdog = launch {
                        delay(TimeUnit.SECONDS.toMillis(timeoutSeconds))
                        timedOut.set(true)
                        runCatching { cmd.close() }
                    }
                    try {
                        val stderr = async(Dispatchers.IO) { cmd.errorStream.readBytes() }
                        // stdin is written concurrently: a command that echoes its input would otherwise block
                        // on a full stdout window while we are still writing.
                        val writer = async(Dispatchers.IO) {
                            if (stdin != null) {
                                cmd.outputStream.use { out -> out.write(stdin) }
                            } else {
                                runCatching { cmd.outputStream.close() }
                            }
                        }
                        val stdout = cmd.inputStream.readBytes()
                        writer.await()
                        val err = stderr.await()
                        cmd.join(5, TimeUnit.SECONDS)
                        if (timedOut.get()) throw AppException(AppError.Timeout)
                        ExecResult(cmd.exitStatus ?: -1, stdout, err.decodeToString())
                    } finally {
                        watchdog.cancel()
                    }
                }
            }
        }

    /** Runs [script] with POSIX sh regardless of the login shell (bash, zsh, fish…). */
    suspend fun execSh(script: String, stdin: ByteArray? = null, timeoutSeconds: Long = 60): ExecResult =
        exec("sh -c ${shQuote(script)}", stdin, timeoutSeconds)

    /** Runs [script] with `sh` as root, through sudo when the login user is not root. */
    suspend fun execPrivileged(
        script: String,
        sudo: SudoMode,
        stdin: ByteArray? = null,
        timeoutSeconds: Long = 60,
    ): ExecResult {
        val quoted = shQuote(script)
        return when (sudo) {
            SudoMode.ROOT -> exec("sh -c $quoted", stdin, timeoutSeconds)
            SudoMode.NOPASSWD -> exec("sudo -n sh -c $quoted", stdin, timeoutSeconds)
            SudoMode.PASSWORD -> {
                val password = loginPassword ?: throw AppException(AppError.SudoPasswordRequired)
                val input = (password + "\n").toByteArray() + (stdin ?: ByteArray(0))
                val result = exec("sudo -S -p '' sh -c $quoted", input, timeoutSeconds)
                if (result.exitCode == 1 && result.stderr.contains("incorrect password", ignoreCase = true)) {
                    throw AppException(AppError.SudoPasswordRequired)
                }
                if (result.exitCode == 1 && result.stderr.contains("not in the sudoers", ignoreCase = true)) {
                    throw AppException(AppError.NoRootAccess)
                }
                result
            }
            SudoMode.NONE -> throw AppException(AppError.NoRootAccess)
        }
    }

    /** Writes [content] to [path] as the login user with mode 600 (the directory is created with 700). */
    suspend fun writePrivateFile(path: String, content: ByteArray) {
        val dir = path.substringBeforeLast('/')
        val result = execSh(
            "umask 077 && mkdir -p ${shQuote(dir)} && cat > ${shQuote(path)}",
            stdin = content,
        )
        if (!result.ok) throw AppException(AppError.Unexpected("write failed: ${result.stderr.take(200)}"))
    }
}
