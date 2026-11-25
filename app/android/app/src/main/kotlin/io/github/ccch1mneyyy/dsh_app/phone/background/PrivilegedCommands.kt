package io.github.ccch1mneyyy.dsh_app.phone.background

import java.io.File

/** The shell commands the ADB and root paths run: fixed text plus the APK paths ApplicationInfo gives. */
object PrivilegedCommands {
    fun appProcess(sourceDir: String, splitSourceDirs: List<String> = emptyList()): String =
        appProcess(sourceDir, splitSourceDirs, PrivilegedDisplayMain::class.java.name)

    internal fun appProcess(sourceDir: String, splitSourceDirs: List<String>, entryPoint: String): String {
        val paths = listOf(sourceDir) + splitSourceDirs
        require(paths.all { it.startsWith('/') && '\u0000' !in it && ':' !in it })
        return "CLASSPATH=${quote(paths.joinToString(":"))} exec /system/bin/app_process /system/bin $entryPoint"
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/** `su -c` takes exactly one shell command on Magisk, KernelSU and APatch. */
internal object RootCommands {
    // Only look in known Android/manager locations; never execute a su from app or guest PATH.
    private val candidates = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/magisk/su")

    fun findSu(canExecute: (String) -> Boolean = { File(it).canExecute() }): String? =
        candidates.firstOrNull(canExecute)

    fun appProcess(su: String, sourceDir: String, splitSourceDirs: List<String> = emptyList()): List<String> =
        command(su, PrivilegedCommands.appProcess(sourceDir, splitSourceDirs))

    fun command(su: String, fixedScript: String): List<String> = listOf(su, "-c", "cd / && $fixedScript")
}
