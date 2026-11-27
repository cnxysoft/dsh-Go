package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import java.io.File

/**
 * User-configured Android host folders bind-mounted under `/mnt/host/<name>` inside the local
 * Linux guest (PRoot, tawcroot, and Direct). Stored in FlutterSharedPreferences as a JSON array
 * of `{"name": "...", "hostPath": "..."}` objects.
 */
object HostMounts {
    const val PREF_NAME = "FlutterSharedPreferences"
    const val PREF_KEY = "flutter.host_mounts"
    const val GUEST_PREFIX = "/mnt/host"
    private const val MAX_NAME_LEN = 64

    data class Entry(val name: String, val hostPath: String) {
        val guestPath: String get() = "$GUEST_PREFIX/$name"
        val bindSpec: String get() = "$hostPath:$guestPath"
    }

    /** True iff [guest] is a single safe segment directly under `/mnt/host/`. */
    fun isValidGuestPath(guest: String): Boolean {
        if (!guest.startsWith("$GUEST_PREFIX/")) return false
        val name = guest.removePrefix("$GUEST_PREFIX/")
        return isValidNameSegment(name)
    }

    fun isValidNameSegment(name: String): Boolean =
        name.isNotEmpty() &&
            name.length <= MAX_NAME_LEN &&
            name != "." &&
            name != ".." &&
            name.none { it == '/' || it == ':' || it == '\n' || it == '\r' || it == '\u0000' }

    /**
     * Normalizes an absolute host path, stripping redundant/trailing slashes.
     * Returns null for relative paths, root `/`, traversal segments, or characters unsafe in bind specs.
     */
    fun normalizeHostPath(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith('/')) return null
        if (trimmed.any { it == ':' || it == '\n' || it == '\r' || it == '\u0000' }) return null
        val parts = trimmed.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) return null
        return "/" + parts.joinToString("/")
    }

    /**
     * Produces a safe single mount-point name from [raw] or, if blank, from the last segment of
     * [normalizedHostPath].
     */
    fun normalizeName(raw: String?, normalizedHostPath: String): String? {
        val seed = raw?.trim()?.takeIf { it.isNotEmpty() }
            ?: normalizedHostPath.substringAfterLast('/')
        val cleaned = buildString {
            for (ch in seed) {
                when {
                    ch == '/' || ch == ':' || ch == '\n' || ch == '\r' || ch == '\u0000' -> append('-')
                    ch.isWhitespace() -> append('-')
                    else -> append(ch)
                }
            }
        }.trim('-').take(MAX_NAME_LEN)
        return cleaned.takeIf { isValidNameSegment(it) }
    }

    /**
     * Parses a JSON array of `{"name": "...", "hostPath": "..."}` entries, normalizing paths/names,
     * dropping invalid or duplicate host paths, and suffixing duplicate names (`foo-2`, ...).
     */
    fun parseJson(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        val objects = JsonLite.parseObjectArray(raw) ?: return emptyList()
        val seenPaths = LinkedHashSet<String>()
        val usedNames = HashSet<String>()
        val out = ArrayList<Entry>()
        for (obj in objects) {
            val hostPath = obj["hostPath"]?.let(::normalizeHostPath) ?: continue
            if (!seenPaths.add(hostPath)) continue
            val baseName = normalizeName(obj["name"], hostPath) ?: continue
            val uniqueName = deduplicateName(baseName, usedNames)
            usedNames += uniqueName
            out += Entry(uniqueName, hostPath)
        }
        return out
    }

    fun read(context: Context): List<Entry> = try {
        val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(PREF_KEY, null)
        parseJson(raw)
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Filters [entries] to directories that exist, are readable right now, and do not overlap
     * [rootfs], ensuring each guest mount-point directory exists under [rootfs].
     */
    fun prepareBinds(rootfs: File, entries: List<Entry>, log: (String) -> Unit = {}): List<Entry> {
        if (entries.isEmpty()) return emptyList()
        val rootPaths = linkedSetOf(
            normalizeHostPath(rootfs.absolutePath),
            runCatching { normalizeHostPath(rootfs.canonicalPath) }.getOrNull(),
        ).filterNotNull()
        val active = ArrayList<Entry>()
        for (entry in entries) {
            val hostDir = File(entry.hostPath)
            val hostCanonical = runCatching { normalizeHostPath(hostDir.canonicalPath) }.getOrNull()
            if (overlapsAny(entry.hostPath, rootPaths) || (hostCanonical != null && overlapsAny(hostCanonical, rootPaths))) {
                log("[dsh-app] host mount skipped (overlaps rootfs): ${entry.hostPath}")
                continue
            }
            if (!hostDir.isDirectory || !hostDir.canRead()) {
                log("[dsh-app] host mount skipped (unreadable): ${entry.hostPath}")
                continue
            }
            val mountPoint = File(rootfs, entry.guestPath.removePrefix("/"))
            if (!mountPoint.isDirectory && !mountPoint.mkdirs() && !mountPoint.isDirectory) {
                log("[dsh-app] host mount skipped (cannot prepare ${entry.guestPath}): ${entry.hostPath}")
                continue
            }
            active += entry
        }
        return active
    }

    private fun overlapsAny(path: String, roots: List<String>): Boolean =
        roots.any { root -> path == root || path.startsWith("$root/") || root.startsWith("$path/") }

    private fun deduplicateName(base: String, used: Set<String>): String {
        if (base !in used) return base
        var n = 2
        while (true) {
            val suffix = "-$n"
            val candidate = base.take((MAX_NAME_LEN - suffix.length).coerceAtLeast(1)) + suffix
            if (candidate !in used) return candidate
            n++
        }
    }

    /** Tiny dependency-free JSON reader for an array of flat string-valued objects (JVM-testable). */
    private object JsonLite {
        fun parseObjectArray(text: String): List<Map<String, String>>? {
            val p = Parser(text)
            p.skipWs()
            if (!p.consume('[')) return null
            val list = ArrayList<Map<String, String>>()
            p.skipWs()
            if (p.consume(']')) return list
            while (true) {
                p.skipWs()
                val obj = p.parseObject() ?: return null
                list += obj
                p.skipWs()
                if (p.consume(',')) continue
                if (p.consume(']')) return list
                return null
            }
        }

        private class Parser(private val s: String) {
            private var i = 0

            fun skipWs() {
                while (i < s.length && s[i].isWhitespace()) i++
            }

            fun consume(c: Char): Boolean {
                if (i < s.length && s[i] == c) {
                    i++
                    return true
                }
                return false
            }

            fun parseObject(): Map<String, String>? {
                if (!consume('{')) return null
                val map = LinkedHashMap<String, String>()
                skipWs()
                if (consume('}')) return map
                while (true) {
                    skipWs()
                    val key = parseString() ?: return null
                    skipWs()
                    if (!consume(':')) return null
                    skipWs()
                    val value = parseStringOrSkip()
                    if (value != null) map[key] = value
                    else if (!skipValue()) return null
                    skipWs()
                    if (consume(',')) continue
                    if (consume('}')) return map
                    return null
                }
            }

            private fun parseStringOrSkip(): String? = if (i < s.length && s[i] == '"') parseString() else null

            private fun parseString(): String? {
                if (!consume('"')) return null
                val out = StringBuilder()
                while (i < s.length) {
                    val c = s[i++]
                    when (c) {
                        '"' -> return out.toString()
                        '\\' -> {
                            if (i >= s.length) return null
                            when (val esc = s[i++]) {
                                '"', '\\', '/' -> out.append(esc)
                                'b' -> out.append('\b')
                                'f' -> out.append('\u000C')
                                'n' -> out.append('\n')
                                'r' -> out.append('\r')
                                't' -> out.append('\t')
                                'u' -> {
                                    if (i + 4 > s.length) return null
                                    val code = s.substring(i, i + 4).toIntOrNull(16) ?: return null
                                    out.append(code.toChar())
                                    i += 4
                                }
                                else -> return null
                            }
                        }
                        else -> out.append(c)
                    }
                }
                return null
            }

            private fun skipValue(): Boolean {
                if (i >= s.length) return false
                return when (s[i]) {
                    '"' -> parseString() != null
                    '{' -> {
                        i++
                        var depth = 1
                        while (i < s.length && depth > 0) {
                            when (s[i]) {
                                '"' -> if (parseString() == null) return false
                                '{' -> { depth++; i++ }
                                '}' -> { depth--; i++ }
                                else -> i++
                            }
                        }
                        depth == 0
                    }
                    '[' -> {
                        i++
                        var depth = 1
                        while (i < s.length && depth > 0) {
                            when (s[i]) {
                                '"' -> if (parseString() == null) return false
                                '[' -> { depth++; i++ }
                                ']' -> { depth--; i++ }
                                else -> i++
                            }
                        }
                        depth == 0
                    }
                    else -> {
                        val start = i
                        while (i < s.length && s[i] !in ",}] \t\r\n") i++
                        i > start
                    }
                }
            }
        }
    }
}
