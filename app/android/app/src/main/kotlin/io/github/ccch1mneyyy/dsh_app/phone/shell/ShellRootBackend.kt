package io.github.ccch1mneyyy.dsh_app.phone.shell

import android.content.Context
import io.github.ccch1mneyyy.dsh_app.phone.background.PrivilegedCommands
import io.github.ccch1mneyyy.dsh_app.phone.background.RootCommands
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Only explicit settings setup may launch this host. No command text passes through su. */
internal object ShellRootBackend {
    const val AUTH_TIMEOUT_MS = 30_000L

    fun open(context: Context): ShellConnection {
        val su = RootCommands.findSu() ?: return ShellConnection(errorCode = "rootUnavailable")
        val info = context.applicationInfo
        val host = PrivilegedCommands.appProcess(info.sourceDir, info.splitSourceDirs.orEmpty().toList(),
            AndroidShellMain::class.java.name)
        val process = try {
            ProcessBuilder(RootCommands.command(su, "$host root")).directory(File("/")).start()
        } catch (_: IOException) {
            return ShellConnection(errorCode = "rootStartFailed")
        }
        // A manager can emit diagnostics while waiting on the user. Drain without storing them.
        thread(isDaemon = true, name = "dsh-root-shell-stderr") {
            runCatching { process.errorStream.use { input ->
                val buffer = ByteArray(4096)
                while (input.read(buffer) >= 0) { }
            } }
        }
        return ShellConnection(ShellLink.pipes("root", process.inputStream, process.outputStream, { process.isAlive }) {
            // EOF tells the privileged host to tear down its owned job before su is killed.
            try {
                process.outputStream.close()
                process.waitFor(2, TimeUnit.SECONDS)
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
                process.errorStream.close()
            }
        })
    }
}
