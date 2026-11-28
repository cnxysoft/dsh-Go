package io.github.ccch1mneyyy.dsh_app.phone

import java.time.ZoneId
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual Core parsing/gating; only Android device operations are recorded by fixtures. */
class PhoneToolSchemaTest {
    private val intents = mutableListOf<IntentSpec>()
    private val actions = mutableListOf<PhoneAction>()
    private val pages = mutableListOf<Int>()
    private val calendarQueries = mutableListOf<Triple<LocalDate, LocalDate, ZoneId>>()
    private var confirmations = 0
    private val confirmationText = mutableListOf<String>()
    private val driver = object : PhoneDriver {
        override val screen = PhoneScreen.MAIN
        override fun availability() = Availability(true)
        override fun foregroundPackage() = "example.app"
        override fun observe(detail: Boolean, image: Boolean) = ScreenSnapshot("s1", "Example", "Example", "example.app")
        override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult {
            pages += maxPages
            return CollectResult(emptyList(), 0, "done", null)
        }
        override fun target(screenId: String, elementId: Int): TargetInfo? = error("unexpected target")
        override fun perform(action: PhoneAction): ActionResult { actions += action; return ActionResult(true, "done") }
        override fun open(target: PhoneAppIdentity): ActionResult = error("unexpected open")
    }
    private val core = PhoneCore(object : PhoneEnv {
        override fun enabled() = true
        override fun policies() = PhonePolicies()
        override fun isLocked() = false
        override fun a11yState() = A11yState.CONNECTED
        override fun backgroundUnavailable() = "unavailable"
        override fun zone() = ZoneId.of("Asia/Shanghai")
        override fun notifyFix(kind: String) {}
        override fun sleep(ms: Long) {}
        override fun driver(screen: PhoneScreen) = if (screen == PhoneScreen.MAIN) driver else null
        override fun gate() = object : UserGate {
            override fun confirm(request: ConfirmRequest): Decision { confirmations++; confirmationText += request.sentence; return Decision.ALLOW_ONCE }
            override fun handoff(reason: String, timeoutMs: Long): HandoffResult = error("unexpected handoff")
            override fun status(text: String?) {}
        }
        override fun system() = object : SystemApi {
            override fun apps() = emptyList<AppEntry>()
            override fun isInstalled(pkg: String) = false
            override fun prepareLaunch(app: AppEntry): Either<String, SystemLaunch> = Either.Right(TestSystemLaunch(SystemTarget.Known(app)))
            override fun prepareUrl(url: String): Either<String, SystemLaunch> = error("unexpected URL")
            override fun prepareIntent(spec: IntentSpec, selectedApp: AppEntry?): Either<String, SystemLaunch> =
                Either.Right(TestSystemLaunch(SystemTarget.UserChoice, "intent", spec))
            override fun launch(plan: SystemLaunch, dispatch: PhoneTasks.SystemDispatch): SysResult {
                check(dispatch.commitStart())
                intents += (plan as TestSystemLaunch).spec!!
                return SysResult(true, "attempted", plan.target)
            }
            override fun notify(title: String, text: String): SysResult = error("unexpected notification")
            override fun clipboardSet(text: String): SysResult = error("unexpected clipboard")
            override fun clipboardGet(): SysResult = error("unexpected clipboard")
            override fun calendarList(start: LocalDate, until: LocalDate, zone: ZoneId): SysResult {
                calendarQueries += Triple(start, until, zone)
                return SysResult(true, """{"status":"verified","events":[]}""")
            }
        }
    }, PhoneTasks(), ActionLog())

    @After fun close() = core.worker.shutdown()

    @Test fun sdkSchemaKeepsIntegerFieldsAndMovesBoundsIntoDescriptions() {
        val server = McpServer(PhoneToolCatalog.listJson(), { _, _, _ -> error("tools/list must not dispatch a tool") })
        val json = checkNotNull(server.handle(Json.write(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list"))))
        val listed = ((Json.parse(json) as Map<*, *>)["result"] as Map<*, *>)["tools"] as List<*>
        assertEquals(PhoneToolCatalog.listJson(), listed)
        assertEquals(listOf("android_shell", "apps", "open", "intent", "notify", "clipboard", "screen", "collect", "tap", "type", "scroll", "key", "tap_xy", "handoff", "diagnostics", "calendar_list"),
            listed.map { (it as Map<*, *>)["name"] })
        for (tool in listed) {
            val schema = (tool as Map<*, *>)["inputSchema"] as Map<*, *>
            assertEquals(false, schema["additionalProperties"])
        }
        assertFalse(json.contains("\"minimum\""))
        assertFalse(json.contains("\"maximum\""))
        assertEquals(16, PhoneToolCatalog.tools.size)
        val tap = PhoneToolCatalog.tools.single { it.name == PhoneToolCatalog.TAP_XY }
        assertEquals(listOf("observation", "x", "y"), tap.required)
        assertEquals(listOf("observation", "x", "y", "action", "x2", "y2", "duration_ms"), tap.properties.keys.toList())
        val gesture = tap.properties["action"] as Map<*, *>
        assertEquals("string", gesture["type"])
        assertEquals(listOf("tap", "long", "swipe"), gesture["enum"])
        for (key in listOf("x", "y")) {
            val field = tap.properties[key] as Map<*, *>
            assertEquals("integer", field["type"])
            assertTrue((field["description"] as String).contains("从 0 开始"))
        }
        for (key in listOf("x2", "y2", "duration_ms")) assertEquals("integer", (tap.properties[key] as Map<*, *>)["type"])
        val duration = (tap.properties["duration_ms"] as Map<*, *>)["description"] as String
        assertTrue(duration.contains("100–2000"))
        assertTrue(duration.contains("400"))
        val collect = PhoneToolCatalog.tools.single { it.name == PhoneToolCatalog.COLLECT }
        val count = collect.properties["max_pages"] as Map<*, *>
        assertEquals("integer", count["type"])
        assertTrue((count["description"] as String).contains("1–30"))
        val intent = PhoneToolCatalog.tools.single { it.name == PhoneToolCatalog.INTENT }
        val days = intent.properties["days"] as Map<*, *>
        assertEquals(mapOf("type" to "integer"), days["items"])
        assertTrue((days["description"] as String).contains("1=周一 … 7=周日"))
    }

    @Test fun calendarQueryUsesPhoneZoneAndDoesNotNeedAScreen() {
        val name = PhoneToolCatalog.CALENDAR_LIST
        val args = mapOf("start_date" to "2026-11-23", "end_date" to "2026-11-24")
        assertEquals(emptyList<PhoneScreen>(), (core.requiredScreens(name, args) as Either.Right).value)
        for (end in listOf("2026-11-23", "2027-01-01", "tomorrow")) {
            assertTrue(core.call(name, args + ("end_date" to end)).isError)
        }
        assertTrue(calendarQueries.isEmpty())
        assertFalse(core.call(name, args).isError)
        assertEquals(listOf(Triple(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 24),
            ZoneId.of("Asia/Shanghai"))), calendarQueries)
        assertTrue(intents.isEmpty())
        assertTrue(actions.isEmpty())
    }

    @Test fun alarmTimeAndWeekdayBoundsStillRejectBeforeSystemDispatch() {
        for (args in listOf(
            mapOf("hour" to -1), mapOf("hour" to 24),
            mapOf("hour" to 1, "minute" to -1), mapOf("hour" to 1, "minute" to 60),
            mapOf("hour" to 1, "days" to listOf(0)), mapOf("hour" to 1, "days" to listOf(8)),
        )) assertTrue(core.call(PhoneToolCatalog.INTENT, args + ("kind" to "alarm")).isError)
        assertTrue(intents.isEmpty())
        assertFalse(core.call(PhoneToolCatalog.INTENT, mapOf("kind" to "alarm", "hour" to 0, "minute" to 0, "days" to listOf(7, 1, 7))).isError)
        assertFalse(core.call(PhoneToolCatalog.INTENT, mapOf("kind" to "alarm", "hour" to 23, "minute" to 59)).isError)
        assertEquals(listOf(IntentSpec.Alarm(0, 0, null, listOf(1, 7)), IntentSpec.Alarm(23, 59, null, emptyList())), intents)
    }

    @Test fun timerBoundsStillRejectAndAcceptBothEndpoints() {
        for (seconds in listOf(0, 86401)) {
            assertTrue(core.call(PhoneToolCatalog.INTENT, mapOf("kind" to "timer", "seconds" to seconds)).isError)
        }
        assertTrue(intents.isEmpty())
        for (seconds in listOf(1, 86400)) {
            assertFalse(core.call(PhoneToolCatalog.INTENT, mapOf("kind" to "timer", "seconds" to seconds)).isError)
        }
        assertEquals(listOf(IntentSpec.Timer(1, null), IntentSpec.Timer(86400, null)), intents)
    }

    @Test fun collectKeepsDefaultAndClampsInsteadOfWideningItsBudget() {
        val args = mapOf("screen" to "s1", "list" to 1)
        assertFalse(core.call(PhoneToolCatalog.COLLECT, args).isError)
        for (count in listOf(0, 1, 30, 31)) {
            assertFalse(core.call(PhoneToolCatalog.COLLECT, args + ("max_pages" to count)).isError)
        }
        assertEquals(listOf(10, 1, 1, 30, 30), pages)
    }

    @Test fun tapXYKeepsNonnegativeCoordinatesAndUserConfirmation() {
        for ((x, y) in listOf(-1 to 0, 0 to -1)) {
            assertTrue(core.call(PhoneToolCatalog.TAP_XY, mapOf("observation" to "s1", "x" to x, "y" to y)).isError)
        }
        assertTrue(actions.isEmpty())
        assertEquals(0, confirmations)
        assertFalse(core.call(PhoneToolCatalog.TAP_XY, mapOf("observation" to "s1", "x" to 0, "y" to 0)).isError)
        assertEquals(listOf(PhoneAction.TapXY("s1", 0, 0)), actions)
        assertEquals(1, confirmations)
    }
    @Test fun coordinateGesturesValidateEndpointsAndDescribeTheActualConfirmedOperation() {
        val base = mapOf("observation" to "s1", "x" to 10, "y" to 20)
        for (extra in listOf(
            mapOf("action" to "unknown"), mapOf("action" to "swipe"),
            mapOf("action" to 1),
            mapOf("action" to "swipe", "x2" to 30),
            mapOf("action" to "swipe", "y2" to 40),
            mapOf("action" to "swipe", "x2" to -1, "y2" to 40),
            mapOf("action" to "swipe", "x2" to 30, "y2" to -1),
            mapOf("action" to "swipe", "x2" to 30.5, "y2" to 40),
            mapOf("action" to "swipe", "x2" to 30, "y2" to 40, "duration_ms" to "fast"),
            mapOf("action" to "swipe", "x2" to 30, "y2" to 40, "duration_ms" to 99),
            mapOf("action" to "swipe", "x2" to 30, "y2" to 40, "duration_ms" to 2001),
            mapOf("action" to "long", "x2" to 30),
            mapOf("action" to "long", "duration_ms" to 400),
            mapOf("action" to "tap", "y2" to 40),
            mapOf("action" to "tap", "duration_ms" to 400),
        )) assertTrue(core.call(PhoneToolCatalog.TAP_XY, base + extra).isError)
        assertTrue(actions.isEmpty())
        assertEquals(0, confirmations)
        assertFalse(core.call(PhoneToolCatalog.TAP_XY, base + ("action" to "long")).isError)
        assertEquals(PhoneAction.CoordinateGesture.LONG, (actions.last() as PhoneAction.TapXY).gesture)
        assertTrue(confirmationText.last().contains("长按"))
        assertFalse(core.call(PhoneToolCatalog.TAP_XY, base + mapOf("action" to "swipe", "x2" to 30, "y2" to 40, "duration_ms" to 2000)).isError)
        assertEquals(PhoneAction.TapXY("s1", 10, 20, PhoneAction.CoordinateGesture.SWIPE, 30, 40, 2000), actions.last())
        assertTrue(confirmationText.last().contains("(30,40)"))
        assertTrue(confirmationText.last().contains("2000"))
        for (extra in listOf(emptyMap(), mapOf("duration_ms" to 100))) {
            assertFalse(core.call(PhoneToolCatalog.TAP_XY, base + mapOf("action" to "swipe", "x2" to 30, "y2" to 40) + extra).isError)
            val expected = (extra["duration_ms"] ?: 400).toLong()
            assertEquals(PhoneAction.TapXY("s1", 10, 20, PhoneAction.CoordinateGesture.SWIPE, 30, 40, expected), actions.last())
            assertTrue(confirmationText.last().contains(expected.toString()))
        }
        assertEquals(4, confirmations)
    }

    @Test fun allFixedIntentKindsUseThePreparedLaunchConsumer() {
        val calls = listOf(
            mapOf("kind" to "alarm", "hour" to 9, "minute" to 30),
            mapOf("kind" to "timer", "seconds" to 60),
            mapOf("kind" to "calendar", "title" to "Synthetic", "begin" to "2030-01-02T10:00:00Z", "end" to "2030-01-02T11:00:00Z"),
            mapOf("kind" to "dial", "number" to "123"),
            mapOf("kind" to "sms_draft", "text" to "Synthetic"),
            mapOf("kind" to "share", "text" to "Synthetic"),
            mapOf("kind" to "navigate", "destination" to "Synthetic"),
            mapOf("kind" to "settings", "page" to "wifi"),
        )
        for (args in calls) {
            val out = core.call(PhoneToolCatalog.INTENT, args)
            assertFalse(out.text, out.isError)
        }
        assertEquals(8, intents.size)
        assertEquals(listOf(IntentSpec.Alarm::class, IntentSpec.Timer::class, IntentSpec.Calendar::class,
            IntentSpec.Dial::class, IntentSpec.SmsDraft::class, IntentSpec.Share::class,
            IntentSpec.Navigate::class, IntentSpec.Settings::class), intents.map { it::class })
    }

    @Test fun screenQueryAndTapChainFilterAndExecuteSafelyInProcess() {
        val page1 = ScreenSnapshot(
            "s1",
            "屏幕 s1 · 拼多多 · 竖屏 · 键盘收起 · 共 3 项\n顶部\n  [1] 按钮 搜索\n底部\n  [2] 标签 首页   [3] 标签 个人中心",
            "拼多多", "com.xunmeng.pinduoduo",
        )
        val page2 = ScreenSnapshot(
            "s2",
            "屏幕 s2 · 拼多多 · 竖屏 · 键盘收起 · 共 2 项\n中部\n  [4] 按钮 待收货   [5] 按钮 待评价",
            "拼多多", "com.xunmeng.pinduoduo",
        )
        val page3 = ScreenSnapshot(
            "s3",
            "屏幕 s3 · 拼多多 · 竖屏 · 键盘收起 · 共 2 项\n中部\n  [6] 按钮 查看物流\n  [7] 文本 快递已签收",
            "拼多多", "com.xunmeng.pinduoduo",
        )
        val launcherPage = ScreenSnapshot(
            "s0",
            "屏幕 s0 · 桌面 · 竖屏 · 键盘收起 · 共 1 项\n中部\n  [1] 按钮 拼多多",
            "桌面", "com.miui.home",
        )
        val dialogPage = ScreenSnapshot(
            "s9",
            "屏幕 s9 · 权限管理 · 竖屏 · 键盘收起 · 共 2 项\n弹窗\n  [1] 文本 允许拼多多获取通知权限？\n  [2] 按钮 允许",
            "权限管理", "com.android.permissioncontroller",
        )
        var current = page1
        var nextLaunchScreen = page1
        var promoteToPage1OnObserve = false
        val taps = mutableListOf<PhoneAction.Tap>()
        val chainDriver = object : PhoneDriver {
            override val screen = PhoneScreen.MAIN
            override fun availability() = Availability(true)
            override fun foregroundPackage() = current.packageName
            override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot {
                val snap = current
                if (promoteToPage1OnObserve && snap === launcherPage) {
                    current = page1
                    promoteToPage1OnObserve = false
                }
                return snap
            }
            override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?) = error("unused")
            override fun target(screenId: String, elementId: Int): TargetInfo? {
                val label = when (screenId to elementId) {
                    "s1" to 3 -> "个人中心"
                    "s2" to 4 -> "待收货"
                    else -> return null
                }
                return TargetInfo(screenId, elementId, "按钮", label, "com.xunmeng.pinduoduo", "拼多多", false, false, false)
            }
            override fun perform(action: PhoneAction): ActionResult {
                val tap = action as PhoneAction.Tap
                taps += tap
                current = when (tap.screenId to tap.elementId) {
                    "s1" to 3 -> page2
                    "s2" to 4 -> page3
                    else -> current
                }
                return ActionResult(true, "点了 ${tap.screenId}#${tap.elementId}", current)
            }
            override fun open(target: PhoneAppIdentity) = error("unused")
        }
        val chainCore = PhoneCore(object : PhoneEnv {
            override fun enabled() = true
            override fun policies() = PhonePolicies(confirmMode = ConfirmMode.ALWAYS)
            override fun isLocked() = false
            override fun a11yState() = A11yState.CONNECTED
            override fun backgroundUnavailable() = "unavailable"
            override fun zone() = ZoneId.of("Asia/Shanghai")
            override fun notifyFix(kind: String) {}
            override fun sleep(ms: Long) {}
            override fun driver(screen: PhoneScreen) = if (screen == PhoneScreen.MAIN) chainDriver else null
            override fun gate() = object : UserGate {
                override fun confirm(request: ConfirmRequest) = Decision.ALLOW_APP_FOR_TASK
                override fun handoff(reason: String, timeoutMs: Long) = error("unused")
                override fun status(text: String?) {}
            }
            override fun system() = object : SystemApi {
                private val pdd = AppEntry("拼多多", PhoneAppIdentity("com.xunmeng.pinduoduo", 10123))
                override fun apps() = listOf(pdd)
                override fun isInstalled(pkg: String) = pkg == pdd.packageName
                override fun prepareLaunch(app: AppEntry): Either<String, SystemLaunch> = Either.Right(TestSystemLaunch(SystemTarget.Known(app)))
                override fun prepareUrl(url: String): Either<String, SystemLaunch> = error("unused")
                override fun prepareIntent(spec: IntentSpec, selectedApp: AppEntry?): Either<String, SystemLaunch> = error("unused")
                override fun launch(plan: SystemLaunch, dispatch: PhoneTasks.SystemDispatch): SysResult {
                    check(dispatch.commitStart())
                    current = nextLaunchScreen
                    return SysResult(true, "已尝试打开 拼多多。请核对实际界面。", plan.target)
                }
                override fun notify(title: String, text: String) = error("unused")
                override fun clipboardSet(text: String) = error("unused")
                override fun clipboardGet() = error("unused")
                override fun calendarList(start: LocalDate, until: LocalDate, zone: ZoneId) = error("unused")
            }
        }, PhoneTasks(), ActionLog())
        try {
            val filtered = chainCore.call(PhoneToolCatalog.SCREEN, mapOf("query" to "个人中心"))
            assertFalse(filtered.isError)
            assertTrue(filtered.text.contains("[3] 标签 个人中心"))
            assertFalse(filtered.text.contains("[1] 按钮 搜索"))

            assertTrue(chainCore.call(PhoneToolCatalog.TAP, mapOf("screen" to "s1", "id" to 3, "long" to true, "then" to listOf("待收货"))).isError)

            val chained = chainCore.call(PhoneToolCatalog.TAP, mapOf("screen" to "s1", "id" to 3, "then" to listOf("待收货"), "query" to "签收"))
            assertFalse(chained.isError)
            assertEquals(listOf(PhoneAction.Tap("s1", 3), PhoneAction.Tap("s2", 4)), taps)
            assertTrue(chained.text.contains("连点完成（2/2 步）"))
            assertTrue(chained.text.contains("2.✓ 点「待收货」(s2#4)"))
            assertTrue(chained.text.contains("快递已签收"))
            assertFalse(chained.text.contains("查看物流"))

            // Early stop must return the unfiltered intermediate screen and warn that executed steps are not undone.
            current = page1
            taps.clear()
            val partial = chainCore.call(PhoneToolCatalog.TAP, mapOf("screen" to "s1", "id" to 3, "then" to listOf("待收货", "不存在"), "query" to "永远不会匹配"))
            assertFalse(partial.isError)
            assertTrue(partial.text.contains("连点中途停止（已完成 2/3 步；已执行的步骤不会撤销"))
            assertTrue(partial.text.contains("2.✓ 点「待收货」(s2#4)"))
            assertTrue(partial.text.contains("3.✗ 未找到「不存在」"))
            assertTrue(partial.text.contains("[6] 按钮 查看物流"))
            assertFalse(partial.text.contains("命中 0/"))

            // open(app, then, wait_for, query) runs end-to-end in a single call and compresses step 1
            // once the final foreground package confirms the target app (even after a cold-start delay).
            taps.clear()
            nextLaunchScreen = launcherPage
            promoteToPage1OnObserve = true
            val opened = chainCore.call(PhoneToolCatalog.OPEN, mapOf(
                "app" to "拼多多",
                "then" to listOf("个人中心", "待收货"),
                "wait_for" to "签收",
                "query" to "签收",
            ))
            assertFalse(opened.isError)
            assertEquals(listOf(PhoneAction.Tap("s1", 3), PhoneAction.Tap("s2", 4)), taps)
            assertTrue(opened.text.contains("连点完成（3/3 步）：1.✓ 已打开「拼多多」"))
            assertFalse(opened.text.contains("未确认原处理应用位于前台"))
            assertTrue(opened.text.contains("快递已签收"))
            assertFalse(opened.text.contains("查看物流"))

            // When a system/permission dialog blocks foregrounding, step 1 keeps the unconfirmed warning
            // and returns the unfiltered dialog screen for recovery.
            taps.clear()
            nextLaunchScreen = dialogPage
            promoteToPage1OnObserve = false
            val blocked = chainCore.call(PhoneToolCatalog.OPEN, mapOf(
                "app" to "拼多多",
                "then" to listOf("个人中心"),
                "query" to "签收",
            ))
            assertFalse(blocked.isError)
            assertTrue(taps.isEmpty())
            assertFalse(blocked.text.contains("1.✓ 已打开「拼多多」"))
            assertTrue(blocked.text.contains("后续读屏未确认原处理应用位于前台"))
            assertTrue(blocked.text.contains("2.✗ 未找到「个人中心」"))
            assertTrue(blocked.text.contains("允许拼多多获取通知权限"))

            // wait_for timeout reports clearly and returns unfiltered screen.
            current = page1
            val timedOut = chainCore.call(PhoneToolCatalog.SCREEN, mapOf("wait_for" to "永远不出现", "query" to "个人中心"))
            assertFalse(timedOut.isError)
            assertTrue(timedOut.text.contains("等待「永远不出现」超时（5 秒），返回当前屏"))
            assertTrue(timedOut.text.contains("[1] 按钮 搜索"))
        } finally {
            chainCore.worker.shutdown()
        }
    }

}
