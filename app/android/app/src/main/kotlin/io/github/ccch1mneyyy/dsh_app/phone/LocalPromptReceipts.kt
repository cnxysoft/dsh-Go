package io.github.ccch1mneyyy.dsh_app.phone

/** An in-memory receipt from the local composer, not proof that a shell command is authorized. */
internal class LocalPromptReceipts {
    private data class Key(val session: String, val prompt: String)
    private val recent = LinkedHashMap<Key, String?>() // null until a run first presents the receipt

    @Synchronized fun remember(session: String, prompt: String) {
        val key = Key(session, prompt)
        if (key in recent) return // An idempotent send must not unbind an existing run.
        if (recent.size == 128) recent.remove(recent.keys.first())
        recent[key] = null
    }

    @Synchronized fun matches(session: String, prompt: String, run: String): Boolean {
        val key = Key(session, prompt)
        if (key !in recent) return false
        val bound = recent[key]
        if (bound != null) return bound == run
        recent[key] = run
        return true
    }

    @Synchronized fun end(session: String, run: String) {
        recent.entries.removeAll { it.key.session == session && it.value == run }
    }

    @Synchronized fun clear() = recent.clear()
}
