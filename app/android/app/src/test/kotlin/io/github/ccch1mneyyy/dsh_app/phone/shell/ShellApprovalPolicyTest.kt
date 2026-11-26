package io.github.ccch1mneyyy.dsh_app.phone.shell

import org.junit.Assert.*
import org.junit.Test

class ShellApprovalPolicyTest {
    @Test fun persistedParseNeverRestoresTemporaryMode() {
        assertEquals(ShellApprovalMode.EVERY, ShellApprovalMode.parsePersisted(null))
        assertEquals(ShellApprovalMode.EVERY, ShellApprovalMode.parsePersisted("temporary"))
        assertEquals(ShellApprovalMode.EVERY, ShellApprovalMode.parsePersisted("unknown"))
        assertEquals(ShellApprovalMode.TURN, ShellApprovalMode.parsePersisted("turn"))
        assertEquals(ShellApprovalMode.ALWAYS, ShellApprovalMode.parsePersisted("always"))
    }

    @Test fun timedTemporaryApprovalExpiresMonotonicallyAndPersistsEvery() {
        var now = 1_000L
        val state = ShellApprovalState(ShellApprovalMode.ALWAYS) { now }
        assertEquals(ShellApprovalMode.ALWAYS, state.currentMode())

        val persisted = state.update(ShellApprovalMode.TEMPORARY, 600_000L)
        assertEquals(ShellApprovalMode.EVERY, persisted)
        val active = state.snapshot()
        assertEquals(ShellApprovalMode.TEMPORARY, active.mode)
        assertEquals(600_000L, active.expiresInMs)
        assertFalse(active.untilRestart)

        now = 301_000L
        assertEquals(300_000L, state.snapshot().expiresInMs)
        assertEquals(ShellApprovalMode.TEMPORARY, state.currentMode())

        now = 601_000L
        val expired = state.snapshot()
        assertEquals(ShellApprovalMode.EVERY, expired.mode)
        assertNull(expired.expiresInMs)
        assertFalse(expired.untilRestart)
    }

    @Test fun untilRestartTemporaryClearsOnStopWithoutAffectingAlways() {
        val state = ShellApprovalState(ShellApprovalMode.EVERY) { 5_000L }
        assertEquals(ShellApprovalMode.EVERY, state.update(ShellApprovalMode.TEMPORARY, null))
        val snap = state.snapshot()
        assertEquals(ShellApprovalMode.TEMPORARY, snap.mode)
        assertNull(snap.expiresInMs)
        assertTrue(snap.untilRestart)

        state.clearTemporary()
        assertEquals(ShellApprovalMode.EVERY, state.currentMode())

        assertEquals(ShellApprovalMode.ALWAYS, state.update(ShellApprovalMode.ALWAYS, null))
        state.clearTemporary()
        assertEquals(ShellApprovalMode.ALWAYS, state.currentMode())

        state.resetToEvery()
        assertEquals(ShellApprovalMode.EVERY, state.currentMode())
    }

    @Test fun rejectsInvalidDurationsAndUnexpectedDurationForNonTemporaryModes() {
        val state = ShellApprovalState()
        assertNull(state.update(ShellApprovalMode.EVERY, 600_000L))
        assertNull(state.update(ShellApprovalMode.TURN, 600_000L))
        assertNull(state.update(ShellApprovalMode.ALWAYS, 600_000L))
        assertNull(state.update(ShellApprovalMode.TEMPORARY, 0L))
        assertNull(state.update(ShellApprovalMode.TEMPORARY, 59_999L))
        assertNull(state.update(ShellApprovalMode.TEMPORARY, 24L * 60 * 60 * 1000 + 1))
        assertEquals(ShellApprovalMode.EVERY, state.currentMode())
    }
}
