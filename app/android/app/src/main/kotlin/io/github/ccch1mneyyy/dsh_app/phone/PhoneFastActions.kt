package io.github.ccch1mneyyy.dsh_app.phone

/**
 * Pure-JVM helpers for fast native UI operations:
 * 1. Device-side keyword filtering on formatted [ScreenSnapshot] text (`screen(query)` / `tap(query)`).
 * 2. Safe text-based element matching for chained `tap(then: [...])` steps.
 */
internal object PhoneFastActions {
    const val MAX_CHAIN_STEPS = 5
    const val MAX_STEP_CHARS = 60
    const val MAX_QUERY_TERMS = 8
    const val CHAIN_RETRY_COUNT = 6
    const val CHAIN_RETRY_DELAY_MS = 400L
    const val WAIT_FOR_MAX_POLLS = 12
    const val WAIT_FOR_POLL_MS = 420L

    private val QUERY_SPLIT = Regex("""[\s|]+""")
    private val ROW_SPLIT = Regex(""" {3,}""")
    private val ELEMENT_LINE = Regex("""^[+⚠]*\[(\d+)]\s*(.*)$""")
    private val ORDINAL_SUFFIX = Regex("""^(.+?)#(-?[1-9]\d*)$""")
    private val DETAIL_COORD = Regex("""\s+@\d+,\d+$""")
    private val LIST_HEADER = Regex("""^\s*列表\s*\[\d+]""")
    private val KNOWN_ROLES = listOf(
        "按钮", "标签", "开关", "勾选框", "单选", "滑块", "下拉框", "链接", "输入框", "标题", "图片", "文本",
    )
    private val NON_INTERACTIVE_ROLES = setOf("文本", "标题", "图片")
    private val STATE_SUFFIXES = listOf(" · 不可用", " · 已聚焦", " · 已勾选", " · 已选", " · 开", " · 关", " · ✓", " · 空")

    data class ParsedElement(
        val id: Int,
        val role: String,
        val label: String,
        val interactive: Boolean,
        val disabled: Boolean,
    )

    sealed interface MatchResult {
        data class Found(val element: ParsedElement) : MatchResult
        data class Ambiguous(val candidates: List<ParsedElement>) : MatchResult
        data object NotFound : MatchResult
    }

    fun parseQueryTerms(query: String?): List<String> {
        if (query.isNullOrBlank()) return emptyList()
        return query.trim().split(QUERY_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_QUERY_TERMS)
    }

    /** Checks whether page content (excluding the top `屏幕 ...` header line when present) contains any wait term. */
    fun matchesWaitFor(screenText: String, waitFor: String): Boolean {
        val terms = parseQueryTerms(waitFor)
        if (terms.isEmpty()) return true
        val lines = screenText.lines()
        val body = if (lines.size > 1) lines.drop(1) else lines
        return body.any { line -> terms.any { term -> line.contains(term, ignoreCase = true) } }
    }

    fun parseThenSteps(raw: Any?): Either<String, List<String>> {
        if (raw == null) return Either.Right(emptyList())
        val list = raw as? List<*> ?: return Either.Left(
            // l10n:keep agent tool result
            "then 需为 1–$MAX_CHAIN_STEPS 个目标文案字符串数组。",
        )
        if (list.isEmpty() || list.size > MAX_CHAIN_STEPS) {
            // l10n:keep agent tool result
            return Either.Left("then 需包含 1–$MAX_CHAIN_STEPS 个后续目标文案。")
        }
        val out = ArrayList<String>(list.size)
        for (item in list) {
            val s = (item as? String)?.trim()
            if (s.isNullOrEmpty() || s.length > MAX_STEP_CHARS) {
                // l10n:keep agent tool result
                return Either.Left("then 的每项需为非空字符串，且不超过 $MAX_STEP_CHARS 字。")
            }
            out += s
        }
        return Either.Right(out)
    }

    fun filterSnapshot(snapshot: ScreenSnapshot, query: String?): ScreenSnapshot {
        if (snapshot.secure || query.isNullOrBlank()) return snapshot
        val terms = parseQueryTerms(query)
        if (terms.isEmpty()) return snapshot
        return snapshot.copy(text = filterScreenText(snapshot.text, query.trim(), terms))
    }

    fun filterScreenText(text: String, rawQuery: String, terms: List<String> = parseQueryTerms(rawQuery)): String {
        if (terms.isEmpty()) return text
        val lines = text.lines()
        if (lines.isEmpty()) return text
        val out = ArrayList<String>(lines.size)
        out += lines[0]
        var inPreamble = true
        var pendingZone: String? = null
        var pendingList: String? = null
        var totalElements = 0
        var matchedLines = 0

        for (i in 1 until lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val isZone = trimmed == "顶部" || trimmed == "中部" || trimmed == "底部" || trimmed.startsWith("弹窗")
            val isList = LIST_HEADER.containsMatchIn(line)
            val hasElement = !isList && trimmed.contains('[') && trimmed.contains(']')
            if (inPreamble && !isZone && !isList && !hasElement) {
                out += line
                continue
            }
            inPreamble = false
            if (isZone) {
                pendingZone = line
                pendingList = null
                continue
            }
            if (isList) {
                if (line.startsWith("列表")) pendingZone = null
                pendingList = line
                continue
            }
            if (!hasElement) continue
            totalElements++
            if (terms.any { term -> line.contains(term, ignoreCase = true) }) {
                pendingZone?.let { out += it; pendingZone = null }
                pendingList?.let { out += it; pendingList = null }
                out += line
                matchedLines++
            }
        }
        if (matchedLines == 0) {
            // l10n:keep model screen format
            out += "（已按「$rawQuery」筛选，命中 0/$totalElements 行；本屏共有 $totalElements 行元素，省略 query 可读全屏）"
        } else if (matchedLines < totalElements) {
            // l10n:keep model screen format
            out += "（已按「$rawQuery」筛选，命中 $matchedLines/$totalElements 行；省略 query 可读全屏）"
        }
        return out.joinToString("\n")
    }

    fun parseElements(screenText: String): List<ParsedElement> {
        val result = ArrayList<ParsedElement>()
        for (line in screenText.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("屏幕 ") || trimmed.startsWith("提示：") ||
                trimmed.startsWith("…") || trimmed.startsWith("（") || LIST_HEADER.containsMatchIn(line)) continue
            for (chunk in trimmed.split(ROW_SPLIT)) {
                val m = ELEMENT_LINE.matchEntire(chunk.trim()) ?: continue
                val id = m.groupValues[1].toIntOrNull() ?: continue
                var body = DETAIL_COORD.replace(m.groupValues[2].trim(), "").trim()
                val disabled = body.contains(" · 不可用") || body == "不可用"
                var role = ""
                for (known in KNOWN_ROLES) {
                    if (body == known) {
                        role = known
                        body = ""
                        break
                    } else if (body.startsWith("$known ")) {
                        role = known
                        body = body.substring(known.length + 1).trim()
                        break
                    } else if (body.startsWith("$known(")) {
                        role = known
                        val close = body.indexOf(')')
                        body = if (close >= 0) body.substring(close + 1).trim() else ""
                        break
                    }
                }
                var changed = true
                while (changed) {
                    changed = false
                    for (suffix in STATE_SUFFIXES) {
                        if (body.endsWith(suffix)) {
                            body = body.removeSuffix(suffix).trim()
                            changed = true
                        }
                    }
                }
                if (body == "(无文字)") body = ""
                val interactive = role !in NON_INTERACTIVE_ROLES
                result += ParsedElement(id, role, body, interactive, disabled)
            }
        }
        return result
    }

    fun findElementByText(screenText: String, target: String): MatchResult {
        val raw = target.trim()
        if (raw.isEmpty()) return MatchResult.NotFound
        val active = parseElements(screenText).filter { !it.disabled && it.label.isNotEmpty() }
        if (active.isEmpty()) return MatchResult.NotFound

        fun candidatesFor(needle: String): List<ParsedElement> {
            val tiers = listOf(
                active.filter { it.label.equals(needle, ignoreCase = true) },
                active.filter { it.label.split(" · ").any { seg -> seg.trim().equals(needle, ignoreCase = true) } },
                active.filter { it.label.contains(needle, ignoreCase = true) },
            )
            val hits = tiers.firstOrNull { it.isNotEmpty() } ?: return emptyList()
            return hits.filter { it.interactive }.ifEmpty { hits }
        }

        // First try the literal target (supports labels that genuinely contain "#1").
        val literal = candidatesFor(raw)
        if (literal.isNotEmpty()) {
            return if (literal.size == 1) MatchResult.Found(literal[0]) else MatchResult.Ambiguous(literal)
        }

        // Optional disambiguation suffix: "查看物流#1" (1-based) or "查看物流#-1" (from end).
        val ordMatch = ORDINAL_SUFFIX.matchEntire(raw) ?: return MatchResult.NotFound
        val base = ordMatch.groupValues[1].trim()
        val ordinal = ordMatch.groupValues[2].toIntOrNull() ?: return MatchResult.NotFound
        if (base.isEmpty()) return MatchResult.NotFound
        val hits = candidatesFor(base)
        if (hits.isEmpty()) return MatchResult.NotFound
        val idx = if (ordinal > 0) ordinal - 1 else hits.size + ordinal
        return hits.getOrNull(idx)?.let { MatchResult.Found(it) } ?: MatchResult.NotFound
    }
}
