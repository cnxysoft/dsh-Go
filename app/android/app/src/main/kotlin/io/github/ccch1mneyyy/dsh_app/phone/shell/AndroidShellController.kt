package io.github.ccch1mneyyy.dsh_app.phone.shell

import android.content.Context
import android.os.SystemClock
import io.github.ccch1mneyyy.dsh_app.phone.Decision
import io.github.ccch1mneyyy.dsh_app.phone.PhoneAndroidShell
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in shell capability, independent of screen-control configuration and confirmation modes. */
class AndroidShellController(context: Context, private val onChanged: () -> Unit = {}) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("android-shell-v1", Context.MODE_PRIVATE)
    private val guard = Any()
    private var enabled = preferences.getBoolean("enabled", false)
    private var backend = preferences.getString("backend", "shizuku").takeIf { it in ShellProtocol.backends } ?: "shizuku"
    private var restorePending = preferences.getBoolean("restoreOnOpen", false)
    private val approval = ShellApprovalState(
        ShellApprovalMode.parsePersisted(preferences.getString("approvalMode", null)),
        SystemClock::elapsedRealtime,
    )
    @Volatile private var restoreError: String? = null
    private var generation = 0L
    private var setupBusy = false
    private var pendingRoot: ShellLink? = null
    private var active: Lease? = null
    @Volatile private var readyLink: ShellLink? = null
    private val adb = ShellAdbBackend(this.context, onChanged)
    private val shizuku = ShellShizukuBackend(this.context, onChanged)

    private class Lease(val ownerId: Long, val generation: Long, val backend: String) {
        val id = UUID.randomUUID().toString()
        @Volatile var cancelled = false
        @Volatile var link: ShellLink? = null
        var sent = false
    }

    fun status(): Map<String, Any?> {
        val config = synchronized(guard) { enabled to backend }
        val shizukuStatus = shizuku.status()
        val adbStatus = adb.status()
        val link = readyLink?.takeIf { it.backend == config.second && it.alive() &&
            (config.second != "shizuku" || shizukuStatus["granted"] == true) }
        val reason = when {
            !config.first -> "disabled"
            link != null -> null
            config.second == "shizuku" && shizukuStatus["installed"] != true -> "shizukuMissing"
            config.second == "shizuku" && shizukuStatus["running"] != true -> "shizukuStopped"
            config.second == "shizuku" && shizukuStatus["granted"] != true -> "permissionRequired"
            config.second == "adb" && adbStatus["paired"] != true -> "adbNotPaired"
            restoreError != null -> restoreError
            config.second == "root" -> "rootNotConnected"
            else -> "notConnected"
        }
        return mapOf(
            "v" to 1, "restoreOnOpen" to preferences.getBoolean("restoreOnOpen", false), "enabled" to config.first, "backend" to config.second,
            "ready" to (config.first && link != null), "uid" to link?.uid,
            "reasonCode" to reason, "shizuku" to shizukuStatus, "adb" to adbStatus,
            "limits" to mapOf("defaultTimeoutMs" to ShellProtocol.DEFAULT_TIMEOUT_MS,
                "maxTimeoutMs" to ShellProtocol.MAX_TIMEOUT_MS, "maxOutputBytes" to ShellProtocol.MAX_OUTPUT_BYTES,
                "maxCommandBytes" to ShellProtocol.MAX_COMMAND_BYTES),
        ) + approval.snapshot().statusFields()
    }

    fun approvalMode(): ShellApprovalMode = approval.currentMode()

    fun setApproval(modeWire: String, durationMs: Long?): Map<String, Any?> {
        val next = ShellApprovalMode.parse(modeWire) ?: return mapOf("ok" to false, "errorCode" to "invalidArguments")
        val persisted = synchronized(guard) {
            if (next != ShellApprovalMode.EVERY && !enabled) return mapOf("ok" to false, "errorCode" to "disabled")
            val toPersist = approval.update(next, durationMs) ?: return mapOf("ok" to false, "errorCode" to "invalidArguments")
            preferences.edit().putString("approvalMode", toPersist.wire).commit()
            toPersist
        }
        onChanged()
        return status() + mapOf("ok" to true, "errorCode" to null, "persistedMode" to persisted.wire)
    }

    fun resetApproval() {
        synchronized(guard) {
            approval.resetToEvery()
            preferences.edit().remove("approvalMode").commit()
        }
        onChanged()
    }

    fun configure(enabled: Boolean, backend: String): Map<String, Any?> {
        if (backend !in ShellProtocol.backends) return mapOf("ok" to false, "errorCode" to "invalidArguments")
        val changed = synchronized(guard) {
            if (this.enabled == enabled && this.backend == backend) false else {
                restorePending = false
                restoreError = null
                // Commit the selected transport and its cleared reconnect/approval preferences together.
                this.enabled = enabled; this.backend = backend; ++generation
                active?.cancelled = true
                approval.resetToEvery()
                clearLinksLocked()
                preferences.edit().remove("restoreOnOpen").remove("approvalMode").putBoolean("enabled", enabled).putString("backend", backend).commit()
                true
            }
        }
        if (changed) { adb.disconnect(); shizuku.disconnect(); onChanged() }
        return status() + mapOf("ok" to true, "errorCode" to null)
    }

    fun requestShizukuPermission(): Boolean = shizuku.requestPermission()
    fun startAdbPairing(): Map<String, Any?> = adb.startPairing()

    fun pairAdb(code: String, port: Int?): Map<String, Any?> {
        val ticket = synchronized(guard) {
            if (setupBusy || active != null) return mapOf("ok" to false, "errorCode" to "busy")
            setupBusy = true; generation
        }
        return try { adb.pair(code, port) { synchronized(guard) { generation == ticket } } }
        finally { synchronized(guard) { setupBusy = false }; onChanged() }
    }

    /** Explicit setup only. status/configure never start a privileged process. */
    // Opt in only for the already verified transport. Never persist a command grant.
    fun setRestoreOnOpen(value: Boolean): Map<String, Any?> = synchronized(guard) {
        if (value && (!enabled || readyLink?.alive() != true)) return mapOf("ok" to false, "errorCode" to "notConnected")
        preferences.edit().putBoolean("restoreOnOpen", value).commit()
        restoreError = null
        restorePending = false
        mapOf("ok" to true)
    }

    fun restoreOnOpen() { connect(restoring = true) }

    fun connect(port: Int? = null, restoring: Boolean = false): Map<String, Any?> {
        val selection = synchronized(guard) {
            if (restoring && !restorePending) return mapOf("ok" to true)
            restorePending = false
            restoreError = null
            if (!enabled) return mapOf("ok" to false, "errorCode" to "disabled")
            if (setupBusy || active != null) return mapOf("ok" to false, "errorCode" to "busy")
            setupBusy = true; generation to backend
        }
        val wanted = { synchronized(guard) { enabled && generation == selection.first && backend == selection.second } }
        return try {
            val connection = if (selection.second == "root") {
                if (port != null) return mapOf("ok" to false, "errorCode" to "invalidArguments")
                connectRoot(wanted)
            } else connection(selection.second, port, wanted)
            val link = connection.link ?: run {
                synchronized(guard) { if (restoring && wanted()) restoreError = connection.errorCode }
                return mapOf("ok" to false, "errorCode" to connection.errorCode)
            }
            synchronized(guard) {
                if (!wanted()) { link.close(); return mapOf("ok" to false, "errorCode" to "cancelled") }
                readyLink = link
                if (pendingRoot === link) pendingRoot = null
            }
            status() + mapOf("ok" to true, "errorCode" to null)
        } finally { synchronized(guard) { setupBusy = false }; onChanged() }
    }

    private fun connectRoot(wanted: () -> Boolean): ShellConnection {
        val link = synchronized(guard) {
            if (!wanted()) return ShellConnection(errorCode = "cancelled")
            readyLink?.takeIf { it.backend == "root" && it.alive() }?.let { return ShellConnection(it) }
            readyLink?.close()
            readyLink = null
            val opened = ShellRootBackend.open(context)
            opened.link?.also { pendingRoot = it } ?: return opened
        }
        val error = try {
            if (link.probe(ShellRootBackend.AUTH_TIMEOUT_MS)) null else "identityChanged"
        } catch (_: SocketTimeoutException) {
            "rootTimeout"
        } catch (_: IOException) {
            "rootStartFailed"
        }
        if (error == null && wanted()) return ShellConnection(link)
        link.close()
        synchronized(guard) { if (pendingRoot === link) pendingRoot = null }
        return ShellConnection(errorCode = if (wanted()) error else "cancelled")
    }

    // Called under guard; close only schedules pipe teardown and never waits for su.
    private fun clearLinksLocked() {
        pendingRoot?.close()
        pendingRoot = null
        readyLink?.takeIf { it.backend == "root" }?.close()
        readyLink = null
    }

    fun execute(
        ownerId: Long,
        command: String,
        timeoutMs: Long,
        stillWanted: () -> Boolean,
        confirm: (backend: String, uid: Int) -> Decision,
    ): Map<String, Any?> {
        ShellProtocol.commandError(command)?.let { return ShellProtocol.failure(it) }
        ShellProtocol.timeoutError(timeoutMs)?.let { return ShellProtocol.failure(it) }
        val lease = synchronized(guard) {
            if (!enabled) return ShellProtocol.failure("disabled")
            if (setupBusy || active != null) return ShellProtocol.failure("busy")
            Lease(ownerId, generation, backend).also { active = it }
        }
        fun wanted(): Boolean = runCatching { stillWanted() }.getOrDefault(false) && synchronized(guard) {
            active === lease && !lease.cancelled && enabled && generation == lease.generation && backend == lease.backend
        }
        var restoreInterrupt = false
        try {
            if (!wanted()) return result(lease, ShellProtocol.failure("cancelled"))
            val connection = connection(lease.backend, null, ::wanted)
            val link = connection.link ?: return result(lease, ShellProtocol.failure(connection.errorCode ?: "connectFailed"))
            lease.link = link
            if (!wanted()) return result(lease, ShellProtocol.failure("cancelled"))
            // Read real UID on this very channel before every confirmation, not from provider status.
            if (!link.probe()) { link.close(); return result(lease, ShellProtocol.failure("identityChanged")) }
            synchronized(guard) {
                if (lease.cancelled || generation != lease.generation) return result(lease, ShellProtocol.failure("cancelled"))
                readyLink = link
            }
            val decision = confirm(lease.backend, link.uid)
            if (!wanted()) return result(lease, ShellProtocol.failure("cancelled"))
            val allowRootRound = approval.currentMode() == ShellApprovalMode.TURN
            PhoneAndroidShell.confirmationError(decision, link.uid, allowRootRound)?.let { return result(lease, ShellProtocol.failure(it)) }
            var response = link.call(mapOf("op" to "start", "jobId" to lease.id,
                "command" to command, "timeoutMs" to timeoutMs, "uid" to link.uid)) {
                wanted() && synchronized(guard) {
                    if (active !== lease || lease.cancelled || generation != lease.generation || !enabled) false
                    else { lease.sent = true; true }
                }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs + 2_000)
            var stopSent = false
            while (response["jobId"] == lease.id && response["done"] != true) {
                if (!wanted() && !stopSent) {
                    link.call(mapOf("op" to "cancel", "jobId" to lease.id))
                    stopSent = true
                }
                if (System.nanoTime() > deadline) {
                    runCatching { link.call(mapOf("op" to "cancel", "jobId" to lease.id)) }
                    link.close()
                    return result(lease, ShellProtocol.failure("cleanupFailed", started = true))
                }
                try { Thread.sleep(50) } catch (_: InterruptedException) {
                    restoreInterrupt = true; lease.cancelled = true
                }
                response = link.call(mapOf("op" to "poll", "jobId" to lease.id))
            }
            if (response["errorCode"] == "outputIncomplete") link.close()
            return result(lease, response)
        } catch (_: Exception) {
            val mayHaveStarted = synchronized(guard) { lease.cancelled = true; lease.sent }
            lease.link?.close()
            return result(lease, ShellProtocol.failure("transportLost", started = mayHaveStarted))
        } finally {
            synchronized(guard) { if (active === lease) active = null }
            if (restoreInterrupt) Thread.currentThread().interrupt()
            onChanged()
        }
    }

    private fun result(lease: Lease, values: Map<String, Any?>): Map<String, Any?> =
        values + mapOf("backend" to lease.backend, "uid" to lease.link?.uid?.takeIf { it >= 0 })

    private fun connection(backend: String, port: Int?, wanted: () -> Boolean): ShellConnection =
        when (backend) {
            "adb" -> adb.connect(port, wanted)
            "shizuku" -> shizuku.connect(wanted)
            // Tool calls may only reuse the explicitly connected root host, never start su.
            "root" -> readyLink?.takeIf { it.backend == "root" && it.alive() }?.let { ShellConnection(it) }
                ?: ShellConnection(errorCode = "rootNotConnected")
            else -> ShellConnection(errorCode = "invalidArguments")
        }

    fun cancel(ownerId: Long) {
        val lease = synchronized(guard) {
            active?.takeIf { it.ownerId == ownerId }?.also { it.cancelled = true }
        } ?: return
        cancelLease(lease)
    }

    /** Stops any in-flight command without tearing down an idle or restoring transport. */
    fun cancelActive() {
        val lease = synchronized(guard) {
            active?.also { it.cancelled = true }
        } ?: return
        cancelLease(lease)
    }

    private fun cancelLease(lease: Lease) {
        if (lease.link == null) {
            when (lease.backend) {
                "adb" -> adb.disconnect()
                "shizuku" -> shizuku.disconnect()
            }
        } else cancelRemote(lease)
    }

    fun cancelAll() {
        val lease = synchronized(guard) {
            ++generation
            approval.clearTemporary()
            clearLinksLocked()
            active?.also { it.cancelled = true }
        }
        if (lease != null && lease.backend != "root") cancelRemote(lease)
        adb.disconnect(); shizuku.disconnect(); onChanged()
    }

    private fun cancelRemote(lease: Lease) {
        val link = lease.link ?: return
        Thread({
            runCatching { link.call(mapOf("op" to "cancel", "jobId" to lease.id)) }
                .onFailure { link.close() }
        }, "dsh-shell-cancel").apply { isDaemon = true; start() }
    }
}
