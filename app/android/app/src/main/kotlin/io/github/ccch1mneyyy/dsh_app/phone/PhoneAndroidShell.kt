package io.github.ccch1mneyyy.dsh_app.phone

import io.github.ccch1mneyyy.dsh_app.phone.shell.ShellApprovalMode
import io.github.ccch1mneyyy.dsh_app.phone.shell.ShellLimits
import io.github.ccch1mneyyy.dsh_app.phone.shell.ShellProtocol

/** The native backend is selected only by App settings, never by model arguments. */
fun interface PhoneShellExecutor {
    fun execute(ownerId: Long, command: String, timeoutMs: Long, stillWanted: () -> Boolean,
                confirm: (String, Int) -> Decision): Map<String, Any?>
}

/** A separate grant: UI app/task waivers deliberately have no effect here. */
object PhoneAndroidShell {
    const val MAX_COMMAND_BYTES = 2048
    const val DEFAULT_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 60_000L

    /** 本轮允许 key for non-root shell commands (they have no target app). */
    const val GRANT = "android-shell"
    /** Separate 本轮允许 key for UID 0 so a non-root grant can never carry into Root. */
    const val ROOT_GRANT = "android-shell-root"
    data class Request(val command: String, val timeoutMs: Long)

    fun parse(args: Args, limits: ShellLimits = ShellLimits.DEFAULT): Request? {
        if (!args.onlyKeys("command", "timeout_ms")) return null
        val command = args.strictText("command") ?: return null
        if (!validCommand(command, limits.maxCommandBytes)) return null
        val timeout = if (args.provided("timeout_ms")) args.strictInt("timeout_ms")?.toLong() ?: return null
            else limits.defaultTimeoutMs
        val maxTimeout = limits.maxTimeoutMs.coerceIn(MAX_TIMEOUT_MS, ShellProtocol.HARD_MAX_TIMEOUT_MS)
        if (timeout !in 100L..maxTimeout) return null
        return Request(command, timeout)
    }

    fun validCommand(command: String, maxCommandBytes: Int = MAX_COMMAND_BYTES): Boolean = command.isNotBlank() &&
        command.toByteArray(Charsets.UTF_8).size <= maxCommandBytes.coerceIn(MAX_COMMAND_BYTES, ShellProtocol.HARD_MAX_COMMAND_BYTES) && command.none {
            (it.code < 32 && it !in "\n\r\t") || it.code in 0x7f..0x9f ||
                it.code == 0x061c || it.code in 0x200e..0x200f || it.code in 0x202a..0x202e || it.code in 0x2066..0x2069
        }

    fun failure(code: String): Map<String, Any?> = linkedMapOf(
        "started" to false, "backend" to null, "uid" to null, "exitCode" to null,
        "stdout" to "", "stderr" to "", "timedOut" to false, "cancelled" to (code == "cancelled"),
        "truncated" to false, "errorCode" to code,
    )

    /** Shell grants are per command unless explicitly relaxed in Android Shell settings. */
    fun confirmationError(decision: Decision, uid: Int = 2000, allowRootRound: Boolean = false): String? = when (decision) {
        Decision.ALLOW_ONCE -> null
        Decision.ALLOW_APP_FOR_TASK -> if (uid == 0 && !allowRootRound) "confirmationDenied" else null
        Decision.UNAVAILABLE -> "confirmationUnavailable"
        Decision.STOP -> "cancelled"
        else -> "confirmationDenied"
    }

    fun run(request: Request, owner: PhoneTasks.Owner, tasks: PhoneTasks, scope: SerialWorker.Scope,
            executor: PhoneShellExecutor?, enabled: () -> Boolean, locked: () -> Boolean,
            gate: () -> UserGate?, ui: PhoneUiText, localPromptMatched: Boolean = false,
            approvalMode: () -> ShellApprovalMode = { ShellApprovalMode.EVERY },
            allowWhenLocked: () -> Boolean = { false }): Map<String, Any?> {
        fun lockedRefused() = locked() && !allowWhenLocked()
        fun live() = tasks.isCurrent(owner) && !tasks.isPaused && enabled() && !lockedRefused() &&
            !Thread.currentThread().isInterrupted
        // 通道未开启 / 锁屏 / 本轮已结束 refuse this one command; they never end the turn.
        if (!enabled()) return failure("disabled")
        if (lockedRefused()) return failure("locked")
        if (!tasks.isCurrent(owner) || tasks.isPaused || Thread.currentThread().isInterrupted) return failure("stopped")
        if (executor == null) return failure("unsupported")
        return executor.execute(owner.taskId, request.command, request.timeoutMs, ::live) { backend, uid ->
            val mode = approvalMode()
            if (!live()) Decision.STOP
            else if (!ShellProtocol.validUid(backend, uid)) Decision.DENY
            else if (mode == ShellApprovalMode.TEMPORARY || mode == ShellApprovalMode.ALWAYS) Decision.ALLOW_ONCE
            else if (uid != 0 && tasks.hasGrant(null, GRANT)) Decision.ALLOW_ONCE
            else if (uid == 0 && mode == ShellApprovalMode.TURN && tasks.hasGrant(null, ROOT_GRANT)) Decision.ALLOW_ONCE
            else {
                val allowRound = uid != 0 || mode == ShellApprovalMode.TURN
                val sourceNote = if (localPromptMatched) ui.get("phone_shell_prompt_match", "请求编号与本机输入框的发送记录一致。") + "\n" else "" // l10n:keep test default
                val prompt = ConfirmRequest(
                    sourceNote + ui.get("phone_shell_confirm",
                        "执行 Android 系统命令（%1\$s，UID %2\$s）。这可能修改系统或应用数据；屏幕操作的免确认不适用：\n\n%3\$s",
                        backend, uid.toString(), request.command), // l10n:keep test default
                    appLabel = null, packageName = null, firstInApp = false, allowRound = allowRound, owner = owner,
                )
                val decision = scope.userWait { if (live()) gate()?.confirm(prompt) ?: Decision.UNAVAILABLE else Decision.STOP }
                if (decision == Decision.ALLOW_APP_FOR_TASK) {
                    if (uid != 0) tasks.confirmIfCurrent(owner, grant = PhoneTasks.grantKey(null, GRANT))
                    else if (mode == ShellApprovalMode.TURN) tasks.confirmIfCurrent(owner, grant = PhoneTasks.grantKey(null, ROOT_GRANT))
                }
                if (live()) decision else Decision.STOP
            }
        }
    }
}
