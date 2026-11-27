package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Experimental native Root chroot launch: maps the same prepared PRoot command onto
 * `su -c 'cd / && exec /system/bin/unshare -m /system/bin/sh'` with a private mount
 * namespace (`unshare -m` + `rslave`), bind-mounting `/dev`, `/proc`, `/sys`, phone storage
 * and user host mounts before `exec /system/bin/chroot`.
 *
 * The bootstrap script (including guest env values) is fed over stdin so no secrets appear in
 * `/proc/<pid>/cmdline` or launch logs. Because mounts live inside a private mount namespace,
 * the kernel automatically tears down every mount when the chroot process tree exits.
 */
object ChrootLaunch {
    const val FAILED = "chrootFailed"
    const val UNSUPPORTED = "chrootUnsupported"
    const val NO_SU = "chrootNoSu"
    const val NO_UNSHARE = "chrootNoUnshare"
    const val NO_CHROOT = "chrootNoChroot"
    const val NO_MOUNT = "chrootNoMount"
    const val PID_FILE = "tmp/dsh-chroot.pid"

    private const val UNSHARE_BIN = "/system/bin/unshare"
    private const val CHROOT_BIN = "/system/bin/chroot"
    private const val MOUNT_BIN = "/system/bin/mount"
    private const val SH_BIN = "/system/bin/sh"

    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/su",
    )

    private val FIXED_BINDS = setOf(
        "/dev",
        "/dev/urandom:/dev/random",
        "/proc",
        "/proc/self/fd:/dev/fd",
        "/proc/self/fd/0:/dev/stdin",
        "/proc/self/fd/1:/dev/stdout",
        "/proc/self/fd/2:/dev/stderr",
        "/sys",
        "/system",
        "/apex",
    )

    /** [stdinScript] may contain guest credentials; never log it. */
    data class Plan(
        val argv: List<String>,
        val stdinScript: String,
        val environment: Map<String, String>,
    )

    class Unsupported(reason: String) : IllegalArgumentException("chroot: unsupported launch ($reason)")

    fun findSu(canExecute: (String) -> Boolean = { File(it).canExecute() }): String? =
        SU_CANDIDATES.firstOrNull(canExecute)

    fun unavailable(canExecute: (String) -> Boolean = { File(it).canExecute() }): String? = when {
        findSu(canExecute) == null -> NO_SU
        !canExecute(UNSHARE_BIN) -> NO_UNSHARE
        !canExecute(CHROOT_BIN) -> NO_CHROOT
        !canExecute(MOUNT_BIN) -> NO_MOUNT
        else -> null
    }

    /**
     * Converts the expanded PRoot command ([prootArgv]) into a private-namespace chroot launch plan.
     */
    fun prepare(
        su: String,
        rootfs: String,
        nativeLibraryDir: String,
        prootArgv: List<String>,
        hostEnvironment: Map<String, String>,
    ): Plan {
        if (!absolutePath(su) || !absolutePath(rootfs) || rootfs.lastIndexOf('/') == 0 || !absolutePath(nativeLibraryDir)) {
            throw Unsupported("path")
        }
        val prefix = listOf(
            "$nativeLibraryDir/libproot.so", "--kill-on-exit", "--link2symlink",
            "--sysvipc", "-L", "-0", "--rootfs=$rootfs", "--cwd=/root",
        )
        if (prootArgv.take(prefix.size) != prefix) throw Unsupported("prefix")
        val envAt = prootArgv.indexOf("/usr/bin/env")
        if (envAt < prefix.size || prootArgv.getOrNull(envAt + 1) != "-i" || envAt + 2 >= prootArgv.size) {
            throw Unsupported("command")
        }

        val fixedWithRootfs = FIXED_BINDS + setOf(
            "$rootfs/var/lib/dsh-app/empty:/sys/fs/selinux",
            "$rootfs/var/lib/dsh-app/shm:/dev/shm",
        )
        val remaining = fixedWithRootfs.toMutableSet()
        val extraBinds = LinkedHashMap<String, String>() // guest -> host
        var at = prefix.size
        while (at < envAt) {
            if (prootArgv[at] != "-b" || at + 1 >= envAt) throw Unsupported("bind")
            val value = prootArgv[at + 1]
            if (fixedWithRootfs.contains(value)) {
                if (!remaining.remove(value)) throw Unsupported("bind")
            } else {
                val parts = value.split(':')
                if (parts.size !in 1..2 || parts.any { !absolutePath(it) }) throw Unsupported("bind")
                val host = parts[0]
                val guest = parts.getOrElse(1) { host }
                val alias = host == rootfs && guest == rootfs
                if (!alias) {
                    if (guest != "/sdcard" && guest != "/storage" && !HostMounts.isValidGuestPath(guest)) {
                        throw Unsupported("bind")
                    }
                    if (extraBinds.put(guest, host) != null) throw Unsupported("bind")
                }
            }
            at += 2
        }
        if (remaining.isNotEmpty()) throw Unsupported("bind")

        val guestCmd = prootArgv.drop(envAt)
        val script = buildString {
            appendLine("set -e")
            appendLine("ROOTFS=${shQuote(rootfs)}")
            // Sever mount propagation so private binds never leak into Android's host mount table.
            appendLine("$MOUNT_BIN --make-rslave / 2>/dev/null || $MOUNT_BIN -o rslave / 2>/dev/null || $MOUNT_BIN --make-rprivate / 2>/dev/null || true")
            appendLine("mkdir -p \"\$ROOTFS/dev\" \"\$ROOTFS/dev/pts\" \"\$ROOTFS/proc\" \"\$ROOTFS/sys\" \"\$ROOTFS/tmp\" \"\$ROOTFS/root\"")
            appendLine("$MOUNT_BIN -o bind /dev \"\$ROOTFS/dev\"")
            appendLine("if [ -d /dev/pts ]; then $MOUNT_BIN -o bind /dev/pts \"\$ROOTFS/dev/pts\" 2>/dev/null || true; fi")
            appendLine("if [ ! -d \"\$ROOTFS/dev/shm\" ]; then mkdir -p \"\$ROOTFS/dev/shm\" 2>/dev/null || true; fi")
            appendLine("if [ -d \"\$ROOTFS/dev/shm\" ]; then $MOUNT_BIN -t tmpfs -o rw,nosuid,nodev tmpfs \"\$ROOTFS/dev/shm\" 2>/dev/null || $MOUNT_BIN -o bind \"\$ROOTFS/var/lib/dsh-app/shm\" \"\$ROOTFS/dev/shm\" 2>/dev/null || true; fi")
            appendLine("$MOUNT_BIN -o bind /proc \"\$ROOTFS/proc\"")
            appendLine("$MOUNT_BIN -o bind /sys \"\$ROOTFS/sys\"")
            appendLine("if [ -d \"\$ROOTFS/sys/fs/selinux\" ] && [ -d \"\$ROOTFS/var/lib/dsh-app/empty\" ]; then $MOUNT_BIN -o bind \"\$ROOTFS/var/lib/dsh-app/empty\" \"\$ROOTFS/sys/fs/selinux\" 2>/dev/null || true; fi")
            appendLine("if [ -d /system ]; then mkdir -p \"\$ROOTFS/system\"; $MOUNT_BIN -o bind /system \"\$ROOTFS/system\" 2>/dev/null || true; fi")
            appendLine("if [ -d /apex ]; then mkdir -p \"\$ROOTFS/apex\"; $MOUNT_BIN -o bind /apex \"\$ROOTFS/apex\" 2>/dev/null || true; fi")
            for ((guest, host) in extraBinds) {
                val qHost = shQuote(host)
                val qTarget = shQuote(rootfs + guest)
                appendLine("mkdir -p $qTarget")
                if (guest == "/storage") {
                    appendLine("$MOUNT_BIN -o rbind $qHost $qTarget 2>/dev/null || $MOUNT_BIN -o bind $qHost $qTarget")
                } else {
                    appendLine("$MOUNT_BIN -o bind $qHost $qTarget")
                }
            }
            appendLine("umask 0022")
            appendLine("echo \$\$ > \"\$ROOTFS/$PID_FILE\"")
            val quotedGuest = guestCmd.joinToString(" ") { shQuote(it) }
            appendLine("exec $CHROOT_BIN \"\$ROOTFS\" /bin/sh -c 'cd /root && exec \"\$@\"' -- $quotedGuest < /dev/null")
        }

        val environment = hostEnvironment.filterKeys { !it.startsWith("PROOT_") && it != "LD_LIBRARY_PATH" }
        return Plan(
            argv = listOf(su, "-c", "cd / && exec $UNSHARE_BIN -m $SH_BIN"),
            stdinScript = script,
            environment = environment,
        )
    }

    /**
     * Generates a fast, toybox-compatible root cleanup script that terminates ONLY processes
     * whose `/proc/<pid>/root` points at [rootfs], removes stale pid markers, and restores
     * ownership of any root-owned files under `root/`, `tmp/`, and `etc/` back to `[appUid]:[appGid]`.
     */
    fun cleanupScript(rootfs: String, appUid: Int, appGid: Int): String {
        require(absolutePath(rootfs) && appUid >= 0 && appGid >= 0)
        val qRoot = shQuote(rootfs)
        return buildString {
            appendLine("ROOTFS=$qRoot")
            appendLine("pids() {")
            appendLine("  /system/bin/ls -ld /proc/[0-9]*/root 2>/dev/null | while IFS= read -r line; do")
            appendLine("    case \"\$line\" in")
            appendLine("      *\" -> \$ROOTFS\")")
            appendLine("        rest=\"\${line#*/proc/}\"")
            appendLine("        pid=\"\${rest%%/root*}\"")
            appendLine("        [ \"\$pid\" != \"\$\$\" ] && echo \"\$pid\"")
            appendLine("        ;;")
            appendLine("    esac")
            appendLine("  done")
            appendLine("}")
            appendLine("list=\$(pids)")
            appendLine("if [ -n \"\$list\" ]; then")
            appendLine("  kill -TERM \$list 2>/dev/null || true")
            appendLine("  i=0")
            appendLine("  while [ \"\$i\" -lt 30 ]; do")
            appendLine("    list=\$(pids)")
            appendLine("    [ -z \"\$list\" ] && break")
            appendLine("    sleep 0.1")
            appendLine("    i=\$((i + 1))")
            appendLine("  done")
            appendLine("  [ -n \"\$list\" ] && kill -KILL \$list 2>/dev/null || true")
            appendLine("fi")
            appendLine("rm -f \"\$ROOTFS/$PID_FILE\" \"\$ROOTFS/tmp/dsh-web.pid\" 2>/dev/null || true")
            appendLine("for d in \"\$ROOTFS/root\" \"\$ROOTFS/tmp\" \"\$ROOTFS/etc\"; do")
            appendLine("  [ -e \"\$d\" ] && /system/bin/find \"\$d\" -xdev -user 0 -exec /system/bin/chown -h $appUid:$appGid {} + 2>/dev/null || true")
            appendLine("done")
        }
    }

    /** Best-effort bounded root stop + ownership repair for a chroot launch. */
    fun stopAndCleanup(
        su: String,
        rootfs: File,
        appUid: Int,
        appGid: Int,
        timeoutMs: Long = 8_000L,
    ): Boolean {
        val canonical = runCatching { rootfs.canonicalPath }.getOrNull() ?: return false
        if (!absolutePath(su) || !absolutePath(canonical) || appUid < 0 || appGid < 0) return false
        return try {
            val proc = ProcessBuilder(su, "-c", "cd / && exec $SH_BIN")
                .redirectErrorStream(true)
                .start()
            proc.outputStream.use { out ->
                out.write(cleanupScript(canonical, appUid, appGid).toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val finished = proc.waitFor(timeoutMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                false
            } else {
                proc.exitValue() == 0
            }
        } catch (_: Exception) {
            false
        }
    }

    internal fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun absolutePath(value: String): Boolean =
        value.startsWith('/') && value != "/" &&
            value.none { it == ':' || it == '\n' || it == '\r' || it == '\u0000' } &&
            value.substring(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." }
}
