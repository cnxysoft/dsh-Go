package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMountsTest {
    @Test fun normalizeHostPathRejectsInvalidAndNormalizesSlashes() {
        assertEquals("/sdcard/Projects", HostMounts.normalizeHostPath(" /sdcard//Projects/ "))
        assertEquals("/data/local/tmp/work", HostMounts.normalizeHostPath("/data/local/tmp/work///"))
        assertNull(HostMounts.normalizeHostPath(""))
        assertNull(HostMounts.normalizeHostPath("/"))
        assertNull(HostMounts.normalizeHostPath("sdcard/Projects"))
        assertNull(HostMounts.normalizeHostPath("/sdcard/../etc"))
        assertNull(HostMounts.normalizeHostPath("/sdcard/./etc"))
        assertNull(HostMounts.normalizeHostPath("/sdcard/a:b"))
        assertNull(HostMounts.normalizeHostPath("/sdcard/a\nb"))
    }

    @Test fun normalizeNameSanitizesAndFallsBackToFolderBasename() {
        assertEquals("Projects", HostMounts.normalizeName(null, "/sdcard/Projects"))
        assertEquals("Projects", HostMounts.normalizeName("   ", "/sdcard/Projects"))
        assertEquals("my-code", HostMounts.normalizeName("my code", "/sdcard/Projects"))
        assertEquals("我的项目", HostMounts.normalizeName("我的项目", "/sdcard/Projects"))
        assertEquals("a-b", HostMounts.normalizeName("a/b", "/sdcard/Projects"))
        assertNull(HostMounts.normalizeName("..", "/sdcard/Projects"))
        assertNull(HostMounts.normalizeName("---", "/sdcard/Projects"))
    }

    @Test fun isValidGuestPathAllowsOnlySingleSegmentUnderMntHost() {
        assertTrue(HostMounts.isValidGuestPath("/mnt/host/projects"))
        assertTrue(HostMounts.isValidGuestPath("/mnt/host/我的项目"))
        assertFalse(HostMounts.isValidGuestPath("/mnt"))
        assertFalse(HostMounts.isValidGuestPath("/mnt/host"))
        assertFalse(HostMounts.isValidGuestPath("/mnt/host/"))
        assertFalse(HostMounts.isValidGuestPath("/mnt/host/a/b"))
        assertFalse(HostMounts.isValidGuestPath("/mnt/host/.."))
        assertFalse(HostMounts.isValidGuestPath("/mnt/host/a:b"))
    }

    @Test fun parseJsonDeduplicatesPathsAndCollisions() {
        val raw = """
            [
              {"name": "src", "hostPath": "/sdcard/a/src/"},
              {"name": "src", "hostPath": "/sdcard/b/src"},
              {"name": "dup", "hostPath": "/sdcard/a/src"},
              {"name": "bad", "hostPath": "relative/path"},
              {"name": "", "hostPath": "/data/local/tmp/work", "extra": 123}
            ]
        """.trimIndent()
        val parsed = HostMounts.parseJson(raw)
        assertEquals(
            listOf(
                HostMounts.Entry("src", "/sdcard/a/src"),
                HostMounts.Entry("src-2", "/sdcard/b/src"),
                HostMounts.Entry("work", "/data/local/tmp/work"),
            ),
            parsed,
        )
        assertEquals("/sdcard/b/src:/mnt/host/src-2", parsed[1].bindSpec)
    }

    @Test fun parseJsonHandlesEscapesAndMalformedInput() {
        val raw = """[{"name":"proj\u4e2d","hostPath":"\/sdcard\/proj"}]"""
        assertEquals(listOf(HostMounts.Entry("proj中", "/sdcard/proj")), HostMounts.parseJson(raw))
        assertEquals(emptyList<HostMounts.Entry>(), HostMounts.parseJson(null))
        assertEquals(emptyList<HostMounts.Entry>(), HostMounts.parseJson(""))
        assertEquals(emptyList<HostMounts.Entry>(), HostMounts.parseJson("{not-an-array}"))
    }

    @Test fun prepareBindsCreatesMountPointsAndSkipsUnreadableOrRootfsOverlap() {
        val sandbox = Files.createTempDirectory("dsh-host-mounts-test").toFile()
        try {
            val rootfs = File(sandbox, "rootfs").apply { mkdirs() }
            val insideRootfs = File(rootfs, "root/inside").apply { mkdirs() }
            val readable = File(sandbox, "projects").apply { mkdirs() }
            val regularFile = File(sandbox, "not-a-dir").apply { writeText("hi") }
            val missing = File(sandbox, "missing")
            val logs = ArrayList<String>()

            val entries = listOf(
                HostMounts.Entry("projects", readable.absolutePath),
                HostMounts.Entry("inside", insideRootfs.absolutePath),
                HostMounts.Entry("parent", sandbox.absolutePath),
                HostMounts.Entry("file", regularFile.absolutePath),
                HostMounts.Entry("missing", missing.absolutePath),
            )
            val active = HostMounts.prepareBinds(rootfs, entries) { logs += it }
            if (readable.absolutePath.startsWith("/")) {
                assertEquals(listOf(HostMounts.Entry("projects", readable.absolutePath)), active)
                assertTrue(File(rootfs, "mnt/host/projects").isDirectory)
                assertTrue(logs.any { it.contains("overlaps rootfs") })
                assertTrue(logs.any { it.contains("unreadable") })
            } else {
                // On Windows host JVM, File(sandbox) paths lack POSIX root; verify missing/file still skip cleanly.
                assertTrue(logs.any { it.contains("unreadable") })
                assertFalse(active.any { it.name == "file" || it.name == "missing" })
            }
        } finally {
            sandbox.deleteRecursively()
        }
    }
}
