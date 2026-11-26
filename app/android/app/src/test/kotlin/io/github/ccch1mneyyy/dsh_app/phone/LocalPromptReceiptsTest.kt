package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.*
import org.junit.Test

class LocalPromptReceiptsTest {
    @Test fun bindsOnlyTheSameSessionAndFirstRun() {
        val r = LocalPromptReceipts()
        r.remember("A", "p")
        assertFalse(r.matches("B", "p", "run"))
        assertFalse(r.matches("A", "other", "run"))
        assertTrue(r.matches("A", "p", "run"))
        r.remember("A", "p")
        assertFalse(r.matches("A", "p", "next"))
        r.end("B", "run")
        assertTrue(r.matches("A", "p", "run"))
        r.end("A", "run")
        assertFalse(r.matches("A", "p", "run"))
    }

    @Test fun boundedMemoryAndBridgeResetLoseReceiptsInsteadOfExtendingThem() {
        val r = LocalPromptReceipts()
        repeat(129) { r.remember("A", "p$it") }
        assertFalse(r.matches("A", "p0", "first"))
        assertTrue(r.matches("A", "p1", "next"))
        r.clear()
        assertFalse(r.matches("A", "p1", "next"))
        assertFalse(r.matches("A", "p128", "next"))
    }
}
