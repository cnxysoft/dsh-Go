package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootLaunchTest {
    private val runtime = "/data/user/0/io.github.ccch1mneyyy.dsh_app/files/runtime"
    private val root = "$runtime/rootfs"
    private val native = "/data/app/x/lib/arm64"
    private val su = "/system/bin/su"
    private val command = "echo \$\$ > /tmp/dsh-web.pid; exec /usr/local/bin/dsh web --host 127.0.0.1 --port 3080 --no-open"

    private val fixedBinds = listOf(
        "/dev", "/dev/urandom:/dev/random", "/proc", "/proc/self/fd:/dev/fd",
        "/proc/self/fd/0:/dev/stdin", "/proc/self/fd/1:/dev/stdout", "/proc/self/fd/2:/dev/stderr",
        "/sys", "$root/var/lib/dsh-app/empty:/sys/fs/selinux",
        "$root/var/lib/dsh-app/shm:/dev/shm", "/system", "/apex",
    )
    private val guest = listOf(
        "/usr/bin/env", "-i", "HOME=/root", "DEEPSEEK_API_KEY=sk-secret-123",
        "TZ=Asia/Shanghai", "/bin/sh", "-c", command,
    )

    private fun proot(binds: List<String> = fixedBinds, tail: List<String> = guest) = listOf(
        "$native/libproot.so", "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "-0", "--rootfs=$root", "--cwd=/root",
    ) + binds.flatMap { listOf("-b", it) } + tail

    private val hostEnv = linkedMapOf(
        "PATH" to "/system/bin:/system/xbin", "HOME" to "/files", "PROOT_TMP_DIR" to "/tmp",
        "PROOT_LOADER" to "$native/libproot-loader.so", "LD_LIBRARY_PATH" to native,
    )

    @Test fun unavailableChecksRequiredSystemBinaries() {
        val all = setOf("/system/bin/su", "/system/bin/unshare", "/system/bin/chroot", "/system/bin/mount")
        assertNull(ChrootLaunch.unavailable { it in all })
        assertEquals(ChrootLaunch.NO_SU, ChrootLaunch.unavailable { it in (all - "/system/bin/su") })
        assertEquals(ChrootLaunch.NO_UNSHARE, ChrootLaunch.unavailable { it in (all - "/system/bin/unshare") })
        assertEquals(ChrootLaunch.NO_CHROOT, ChrootLaunch.unavailable { it in (all - "/system/bin/chroot") })
        assertEquals(ChrootLaunch.NO_MOUNT, ChrootLaunch.unavailable { it in (all - "/system/bin/mount") })
    }

    @Test fun prepareBuildsPrivateMountNamespacePlanWithoutSecretsInArgv() {
        val binds = fixedBinds + listOf(
            "/storage/emulated/0:/sdcard",
            "/storage",
            "/sdcard/Projects:/mnt/host/projects",
            "$root:$root",
        )
        val plan = ChrootLaunch.prepare(su, root, native, proot(binds), hostEnv)

        assertEquals(listOf(su, "-c", "cd / && exec /system/bin/unshare -m /system/bin/sh"), plan.argv)
        assertFalse(plan.argv.joinToString(" ").contains("sk-secret-123"))
        assertEquals(mapOf("PATH" to "/system/bin:/system/xbin", "HOME" to "/files"), plan.environment)

        assertTrue(plan.stdinScript.contains("--make-rslave"))
        assertTrue(plan.stdinScript.contains("/system/bin/mount -o bind /dev"))
        assertTrue(plan.stdinScript.contains("/system/bin/mount -o bind /proc"))
        assertTrue(plan.stdinScript.contains("/system/bin/mount -o bind '/storage/emulated/0' '$root/sdcard'"))
        assertTrue(plan.stdinScript.contains("/system/bin/mount -o rbind '/storage' '$root/storage'"))
        assertTrue(plan.stdinScript.contains("/system/bin/mount -o bind '/sdcard/Projects' '$root/mnt/host/projects'"))
        assertTrue(plan.stdinScript.contains("umask 0022"))
        assertTrue(plan.stdinScript.contains(ChrootLaunch.PID_FILE))
        assertTrue(plan.stdinScript.contains("exec /system/bin/chroot \"\$ROOTFS\" /bin/sh -c 'cd /root && exec \"\$@\"' -- '/usr/bin/env' '-i'"))
        assertTrue(plan.stdinScript.contains("'DEEPSEEK_API_KEY=sk-secret-123'"))
        assertTrue(plan.stdinScript.contains("< /dev/null"))
    }

    @Test fun prepareRejectsUnexpectedBindsOrMalformedCommand() {
        val cases = listOf(
            proot(fixedBinds + "/data/other:/mnt"),
            proot(fixedBinds + "/data/other:/mnt/host"),
            proot(fixedBinds + "/data/other:/mnt/host/nested/dir"),
            proot(fixedBinds.drop(1)),
            proot(tail = listOf("/bin/sh", "-c", command)),
        )
        for (argv in cases) {
            assertThrows(ChrootLaunch.Unsupported::class.java) {
                ChrootLaunch.prepare(su, root, native, argv, hostEnv)
            }
        }
    }

    @Test fun cleanupScriptSweepsOnlyRootfsChrootProcessesAndRepairsOwnership() {
        val script = ChrootLaunch.cleanupScript(root, 10234, 10234)
        assertTrue(script.contains("ROOTFS='$root'"))
        assertTrue(script.contains("/system/bin/ls -ld /proc/[0-9]*/root"))
        assertTrue(script.contains("-xdev -user 0 -exec /system/bin/chown -h 10234:10234"))
        assertTrue(script.contains(ChrootLaunch.PID_FILE))
    }
}
