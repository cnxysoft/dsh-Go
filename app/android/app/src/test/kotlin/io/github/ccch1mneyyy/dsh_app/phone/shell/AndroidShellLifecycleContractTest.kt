package io.github.ccch1mneyyy.dsh_app.phone.shell

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Prevent engine launch/restart resets from tearing down an idle or restoring Android Shell link. */
class AndroidShellLifecycleContractTest {
    @Test fun normalEngineLaunchCancelsActiveCommandOnlyWhileDisableAndSafeModeDisconnectShell() {
        val phone = File("src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone")
        val control = File(phone, "PhoneControl.kt").readText()
        assertTrue(control.contains("stopBridge(stopTransport = false, disconnectShell = true)"))
        assertTrue(control.contains("stopBridge(disconnectShell = safe)"))
        assertTrue(control.contains("private fun stopBridge(stopTransport: Boolean = true, disconnectShell: Boolean = false)"))
        assertTrue(control.contains("if (disconnectShell) shell.cancelAll() else shell.cancelActive()"))
        assertTrue(control.contains("override fun onStopAllRequested(reason: String, source: PhoneTasks.StopSource) { shell.cancelActive();"))

        val controller = File(phone, "shell/AndroidShellController.kt").readText()
        val cancelActiveBody = controller.substringAfter("fun cancelActive()").substringBefore("private fun cancelLease")
        assertTrue(cancelActiveBody.contains("active?.also { it.cancelled = true }"))
        assertFalse(cancelActiveBody.contains("++generation"))
        assertFalse(cancelActiveBody.contains("clearLinksLocked()"))

        val plugin = File(phone, "PhonePlugin.kt").readText()
        assertTrue(plugin.contains("if (AppVisibility.isResumed(binding.activity)) onHostResumed()"))
    }
}
