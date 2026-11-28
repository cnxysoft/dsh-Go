package io.github.ccch1mneyyy.dsh_app.phone

import org.junit.Assert.*
import org.junit.Test

class PhoneFastActionsTest {
    private val sampleScreen = """
        屏幕 s7 · 拼多多 · 竖屏 · 键盘收起 · 共 12 项
        顶部
          [1] 按钮 返回   [2] 输入框 "搜索商品"
        列表 [4] 可下滑
          [5] 店铺A · 4.9分
            [6] 按钮 客服   [7] 按钮 查看物流
          [8] 店铺B · 4.8分
            [9] 按钮 再次购买   ⚠[10] 按钮 删除订单
        底部
          [11] 标签 首页 · 已选   [12] 标签 个人中心
    """.trimIndent()

    @Test fun filterScreenTextKeepsMatchedLinesAndEnclosingHeaders() {
        val filtered = PhoneFastActions.filterScreenText(sampleScreen, "查看物流|个人中心")
        assertTrue(filtered.startsWith("屏幕 s7 · 拼多多"))
        assertTrue(filtered.contains("列表 [4] 可下滑"))
        assertTrue(filtered.contains("[7] 按钮 查看物流"))
        assertTrue(filtered.contains("底部"))
        assertTrue(filtered.contains("[12] 标签 个人中心"))
        assertFalse(filtered.contains("顶部"))
        assertFalse(filtered.contains("[1] 按钮 返回"))
        assertFalse(filtered.contains("店铺B"))
        assertTrue(filtered.contains("命中 2/6 行"))
    }

    @Test fun filterScreenTextReportsMissClearlyWithoutDroppingHeader() {
        val filtered = PhoneFastActions.filterScreenText(sampleScreen, "不存在的按钮")
        assertTrue(filtered.startsWith("屏幕 s7 · 拼多多"))
        assertTrue(filtered.contains("命中 0/6 行"))
        assertTrue(filtered.contains("共有 6 行元素"))
    }

    @Test fun filterSnapshotSkipsSecureScreens() {
        val secure = ScreenSnapshot("s1", sampleScreen, "Bank", "com.bank", secure = true)
        assertSame(secure, PhoneFastActions.filterSnapshot(secure, "返回"))
    }

    @Test fun findElementByTextHandlesMergedRowsExactSegmentAndAmbiguity() {
        val center = PhoneFastActions.findElementByText(sampleScreen, "个人中心")
        assertTrue(center is PhoneFastActions.MatchResult.Found)
        assertEquals(12, (center as PhoneFastActions.MatchResult.Found).element.id)

        val home = PhoneFastActions.findElementByText(sampleScreen, "首页")
        assertTrue(home is PhoneFastActions.MatchResult.Found)
        assertEquals(11, (home as PhoneFastActions.MatchResult.Found).element.id)

        val logistics = PhoneFastActions.findElementByText(sampleScreen, "查看物流")
        assertTrue(logistics is PhoneFastActions.MatchResult.Found)
        assertEquals(7, (logistics as PhoneFastActions.MatchResult.Found).element.id)

        val shopA = PhoneFastActions.findElementByText(sampleScreen, "店铺A")
        assertTrue(shopA is PhoneFastActions.MatchResult.Found)
        assertEquals(5, (shopA as PhoneFastActions.MatchResult.Found).element.id)

        val ambiguous = PhoneFastActions.findElementByText(sampleScreen, "店铺")
        assertTrue(ambiguous is PhoneFastActions.MatchResult.Ambiguous)
        assertEquals(listOf(5, 8), (ambiguous as PhoneFastActions.MatchResult.Ambiguous).candidates.map { it.id })

        val firstShop = PhoneFastActions.findElementByText(sampleScreen, "店铺#1")
        assertTrue(firstShop is PhoneFastActions.MatchResult.Found)
        assertEquals(5, (firstShop as PhoneFastActions.MatchResult.Found).element.id)

        val lastShop = PhoneFastActions.findElementByText(sampleScreen, "店铺#-1")
        assertTrue(lastShop is PhoneFastActions.MatchResult.Found)
        assertEquals(8, (lastShop as PhoneFastActions.MatchResult.Found).element.id)

        assertEquals(PhoneFastActions.MatchResult.NotFound, PhoneFastActions.findElementByText(sampleScreen, "店铺#3"))
        assertEquals(PhoneFastActions.MatchResult.NotFound, PhoneFastActions.findElementByText(sampleScreen, "不存在"))
    }

    @Test fun findElementByTextIgnoresDisabledControlsAndPrefersInteractive() {
        val text = """
            屏幕 s2 · 测试 · 竖屏 · 键盘收起 · 共 3 项
            中部
              [1] 文本 待收货
              [2] 按钮 待收货
              [3] 按钮 提交 · 不可用
        """.trimIndent()
        val match = PhoneFastActions.findElementByText(text, "待收货")
        assertTrue(match is PhoneFastActions.MatchResult.Found)
        assertEquals(2, (match as PhoneFastActions.MatchResult.Found).element.id)
        assertEquals(PhoneFastActions.MatchResult.NotFound, PhoneFastActions.findElementByText(text, "提交"))
    }

    @Test fun parseThenStepsValidatesShapeAndBounds() {
        assertEquals(emptyList<String>(), (PhoneFastActions.parseThenSteps(null) as Either.Right).value)
        assertEquals(listOf("待收货", "查看物流"), (PhoneFastActions.parseThenSteps(listOf(" 待收货 ", "查看物流")) as Either.Right).value)
        assertTrue(PhoneFastActions.parseThenSteps(emptyList<String>()) is Either.Left)
        assertTrue(PhoneFastActions.parseThenSteps("待收货") is Either.Left)
        assertTrue(PhoneFastActions.parseThenSteps(listOf("1", "2", "3", "4", "5", "6")) is Either.Left)
        assertTrue(PhoneFastActions.parseThenSteps(listOf("   ")) is Either.Left)
        assertTrue(PhoneFastActions.parseThenSteps(listOf("a".repeat(61))) is Either.Left)
    }

    @Test fun matchesWaitForChecksBodyLinesAndIgnoresHeaderAppName() {
        assertFalse(PhoneFastActions.matchesWaitFor(sampleScreen, "拼多多"))
        assertTrue(PhoneFastActions.matchesWaitFor(sampleScreen, "查看物流"))
        assertTrue(PhoneFastActions.matchesWaitFor(sampleScreen, "不存在|个人中心"))
        assertFalse(PhoneFastActions.matchesWaitFor(sampleScreen, "物流详情|签收"))
    }
}
