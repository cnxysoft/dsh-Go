package io.github.ccch1mneyyy.dsh_app.phone.shell

/**
 * Confirmation policy for Android Shell commands, independent of screen-control waivers.
 *
 * - EVERY: confirm every UID 0 command (default; UID 2000 still allows per-turn approval).
 * - TURN: allow per-turn confirmation for UID 0 as well.
 * - TEMPORARY: in-memory auto-approve until a monotonic deadline or process restart.
 * - ALWAYS: persisted auto-approve (explicit opt-in; reset on disable or backend switch).
 */
enum class ShellApprovalMode(val wire: String) {
    EVERY("every"),
    TURN("turn"),
    TEMPORARY("temporary"),
    ALWAYS("always");

    companion object {
        const val MIN_TEMP_DURATION_MS = 60_000L
        const val MAX_TEMP_DURATION_MS = 24L * 60 * 60 * 1000

        fun parse(value: Any?): ShellApprovalMode? = entries.firstOrNull { it.wire == value }

        /** TEMPORARY is strictly in-memory and never restored across a process restart. */
        fun parsePersisted(value: Any?): ShellApprovalMode = when (value) {
            TURN.wire -> TURN
            ALWAYS.wire -> ALWAYS
            else -> EVERY
        }
    }
}

data class ShellApprovalSnapshot(
    val mode: ShellApprovalMode,
    val expiresInMs: Long?,
    val untilRestart: Boolean,
) {
    fun statusFields(): Map<String, Any?> = mapOf(
        "approvalMode" to mode.wire,
        "approvalExpiresInMs" to expiresInMs,
        "approvalUntilRestart" to untilRestart,
    )
}

/** Pure monotonic-clock state holder for Android Shell confirmation mode. */
internal class ShellApprovalState(
    persistedMode: ShellApprovalMode = ShellApprovalMode.EVERY,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var mode: ShellApprovalMode = when (persistedMode) {
        ShellApprovalMode.TURN, ShellApprovalMode.ALWAYS -> persistedMode
        else -> ShellApprovalMode.EVERY
    }
    private var tempDeadlineMs: Long? = null
    private var tempUntilRestart: Boolean = false

    @Synchronized fun snapshot(): ShellApprovalSnapshot {
        expireIfNeededLocked()
        val remaining = if (mode == ShellApprovalMode.TEMPORARY && !tempUntilRestart) {
            (checkNotNull(tempDeadlineMs) - nowMs()).coerceAtLeast(0L)
        } else null
        return ShellApprovalSnapshot(
            mode = mode,
            expiresInMs = remaining,
            untilRestart = mode == ShellApprovalMode.TEMPORARY && tempUntilRestart,
        )
    }

    @Synchronized fun currentMode(): ShellApprovalMode = snapshot().mode

    /**
     * Updates the policy and returns the mode to persist on disk (TEMPORARY persists EVERY so a
     * restart never resurrects an older ALWAYS/TURN setting), or null on invalid arguments.
     */
    @Synchronized fun update(next: ShellApprovalMode, durationMs: Long?): ShellApprovalMode? {
        if (next == ShellApprovalMode.TEMPORARY) {
            if (durationMs != null && durationMs !in ShellApprovalMode.MIN_TEMP_DURATION_MS..ShellApprovalMode.MAX_TEMP_DURATION_MS) {
                return null
            }
            mode = ShellApprovalMode.TEMPORARY
            if (durationMs == null) {
                tempUntilRestart = true
                tempDeadlineMs = null
            } else {
                tempUntilRestart = false
                tempDeadlineMs = nowMs() + durationMs
            }
            return ShellApprovalMode.EVERY
        }
        if (durationMs != null) return null
        mode = next
        tempUntilRestart = false
        tempDeadlineMs = null
        return next
    }

    /** Clears any active in-memory temporary approval (e.g., on manual Stop). */
    @Synchronized fun clearTemporary() {
        if (mode == ShellApprovalMode.TEMPORARY) {
            mode = ShellApprovalMode.EVERY
            tempUntilRestart = false
            tempDeadlineMs = null
        }
    }

    /** Resets back to default EVERY (e.g., on backend switch, Shell disable, or Phone Control off). */
    @Synchronized fun resetToEvery() {
        mode = ShellApprovalMode.EVERY
        tempUntilRestart = false
        tempDeadlineMs = null
    }

    private fun expireIfNeededLocked() {
        if (mode == ShellApprovalMode.TEMPORARY && !tempUntilRestart) {
            val deadline = tempDeadlineMs
            if (deadline == null || nowMs() >= deadline) {
                mode = ShellApprovalMode.EVERY
                tempDeadlineMs = null
            }
        }
    }
}
