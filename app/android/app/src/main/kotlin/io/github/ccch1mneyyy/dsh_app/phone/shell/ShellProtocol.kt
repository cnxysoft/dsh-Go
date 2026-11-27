package io.github.ccch1mneyyy.dsh_app.phone.shell

data class ShellLimits(
    val defaultTimeoutMs: Long = ShellProtocol.DEFAULT_TIMEOUT_MS,
    val maxTimeoutMs: Long = ShellProtocol.MAX_TIMEOUT_MS,
    val maxOutputBytes: Int = ShellProtocol.MAX_OUTPUT_BYTES,
    val maxCommandBytes: Int = ShellProtocol.MAX_COMMAND_BYTES,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "defaultTimeoutMs" to defaultTimeoutMs,
        "maxTimeoutMs" to maxTimeoutMs,
        "maxOutputBytes" to maxOutputBytes,
        "maxCommandBytes" to maxCommandBytes,
    )

    companion object {
        val DEFAULT = ShellLimits()
    }
}

/** Deliberately independent of the virtual-display protocol. */
object ShellProtocol {
    const val REVISION = "dsh.android-shell.v1"
    const val TAG = "dsh.android-shell"
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 60_000L
    const val MAX_OUTPUT_BYTES = 64 * 1024
    const val MAX_COMMAND_BYTES = 2048
    const val HARD_MAX_TIMEOUT_MS = 600_000L
    const val HARD_MAX_OUTPUT_BYTES = 512 * 1024
    const val SHIZUKU_MAX_OUTPUT_BYTES = 256 * 1024
    const val HARD_MAX_COMMAND_BYTES = 64 * 1024
    const val RPC_TIMEOUT_MS = 5_000L

    val commandByteOptions = listOf(2048, 8 * 1024, 32 * 1024, 64 * 1024)
    val maxTimeoutMsOptions = listOf(60_000L, 120_000L, 300_000L, 600_000L)
    val maxOutputByteOptions = listOf(64 * 1024, 128 * 1024, 256 * 1024, 512 * 1024)

    fun commandError(command: String, maxCommandBytes: Int = MAX_COMMAND_BYTES): String? = when {
        command.isBlank() -> "invalidCommand"
        command.toByteArray(Charsets.UTF_8).size > maxCommandBytes.coerceIn(MAX_COMMAND_BYTES, HARD_MAX_COMMAND_BYTES) -> "commandTooLong"
        command.any { c ->
            (c.code in 0..31 && c != '\n' && c != '\r' && c != '\t') ||
                c.code in 127..159 || c.code in 0x202a..0x202e || c.code in 0x2066..0x2069 ||
                c.code == 0x061c || c.code == 0x200e || c.code == 0x200f
        } -> "invalidCommand"
        else -> null
    }

    fun timeoutError(timeoutMs: Long, maxTimeoutMs: Long = MAX_TIMEOUT_MS): String? =
        if (timeoutMs !in 100..maxTimeoutMs.coerceIn(MAX_TIMEOUT_MS, HARD_MAX_TIMEOUT_MS)) "invalidTimeout" else null

    val backends = setOf("adb", "shizuku", "root")

    fun validUid(backend: String, uid: Int): Boolean = when (backend) {
        "root" -> uid == 0
        "adb" -> uid == 2000
        "shizuku" -> uid == 2000 || uid == 0
        else -> false
    }

    fun failure(code: String, started: Boolean = false): Map<String, Any?> = mapOf(
        "ok" to false, "started" to started, "errorCode" to code,
        "stdout" to "", "stderr" to "", "exitCode" to null,
        "timedOut" to (code == "timeout"), "cancelled" to (code == "cancelled"), "truncated" to false,
    )
}

internal data class ShellSnapshot(
    val jobId: String,
    val started: Boolean,
    val done: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val truncated: Boolean,
    val errorCode: String?,
) {
    fun map(): Map<String, Any?> = mapOf(
        "ok" to (errorCode == null), "jobId" to jobId, "started" to started,
        "done" to done, "stdout" to stdout, "stderr" to stderr,
        "exitCode" to exitCode, "timedOut" to timedOut,
        "cancelled" to cancelled, "truncated" to truncated, "errorCode" to errorCode,
    )
}
