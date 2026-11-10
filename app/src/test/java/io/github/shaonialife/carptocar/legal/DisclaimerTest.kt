package io.github.shaonialife.carptocar.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclaimerPolicyTest {
    @Test
    fun contentNotScrollableOnlyNeedsCountdown() {
        // 文案一屏放得下（无需滚动）：只等倒计时
        assertFalse(canAcceptDisclaimer(countdownDone = false, contentScrollable = false, scrolledToBottom = false))
        assertTrue(canAcceptDisclaimer(countdownDone = true, contentScrollable = false, scrolledToBottom = false))
    }

    @Test
    fun scrollableContentRequiresBothCountdownAndBottom() {
        assertFalse(canAcceptDisclaimer(countdownDone = true, contentScrollable = true, scrolledToBottom = false))
        assertFalse(canAcceptDisclaimer(countdownDone = false, contentScrollable = true, scrolledToBottom = true))
        assertTrue(canAcceptDisclaimer(countdownDone = true, contentScrollable = true, scrolledToBottom = true))
    }

    @Test
    fun shorterElapsedButAtBottomStillBlocked() {
        // 滑到底了但没等够 10 秒：仍不可接受（两个条件是并列的）
        assertFalse(canAcceptDisclaimer(countdownDone = false, contentScrollable = true, scrolledToBottom = true))
    }
}

class DisclaimerVersionTest {
    @Test
    fun firstRunNeedsDisclaimer() {
        assertTrue(needsDisclaimerAtLaunch(acceptedVersion = 0, currentVersion = 1))
    }

    @Test
    fun acceptedCurrentVersionNeedsNothing() {
        assertFalse(needsDisclaimerAtLaunch(acceptedVersion = 1, currentVersion = 1))
        // 老版本接受过（1）但当前是 2：文案变了必须重新确认
        assertTrue(needsDisclaimerAtLaunch(acceptedVersion = 1, currentVersion = 2))
        // 已接受更高版本（回退安装旧 APK）：不再打扰
        assertFalse(needsDisclaimerAtLaunch(acceptedVersion = 3, currentVersion = 2))
    }
}

class DisclaimerHintTest {
    @Test
    fun hintsExplainWhyAcceptIsBlocked() {
        // 两者都没满足：提示同时给出倒计时与滑动要求
        assertEquals(
            "请滑动阅读至底部，并等待 7 秒",
            disclaimerBlockReason(
                countdownDone = false,
                contentScrollable = true,
                scrolledToBottom = false,
                remainingSeconds = 7,
            ),
        )
        // 只有倒计时
        assertEquals(
            "请等待 3 秒后接受",
            disclaimerBlockReason(
                countdownDone = false,
                contentScrollable = false,
                scrolledToBottom = false,
                remainingSeconds = 3,
            ),
        )
        // 只有滚动
        assertEquals(
            "请滑动阅读至底部",
            disclaimerBlockReason(
                countdownDone = true,
                contentScrollable = true,
                scrolledToBottom = false,
                remainingSeconds = 0,
            ),
        )
        // 已可接受：无提示
        assertNull(
            disclaimerBlockReason(
                countdownDone = true,
                contentScrollable = true,
                scrolledToBottom = true,
                remainingSeconds = 0,
            ),
        )
    }
}

class DisclaimerContentTest {
    @Test
    fun contentCoversMandatoryTopics() {
        val text = DisclaimerContent.sections.joinToString("\n") { it.title + "\n" + it.body }
        // 分享出去必须讲清的三类内容：测试范围、驾驶风险、系统权限
        assertTrue(text.contains("已经测试过的组合只有"))
        assertTrue(text.contains("一加Ace7Pro"))
        assertTrue(text.contains("ColorOS 17"))
        assertTrue(text.contains("Mac版本的AllPlay 测试台"))
        assertTrue(text.contains("驾驶安全"))
        assertTrue(text.contains("蓝牙权限"))
        assertTrue(text.contains("CarPToCar+ICCOA"))
        assertFalse(text.contains("CarPToCarL"))
        // 非官方声明（避免被误认为与 Apple 有关联）
        assertTrue(text.contains("非官方"))
        assertTrue(text.contains("商标"))
    }

    @Test
    fun contentHasNoMarkdownMarkers() {
        // 正文直接进 TextView：`**`/`#` 会原样显示，属排版事故
        val text = DisclaimerContent.sections.joinToString("") { it.body }
        assertFalse(text.contains("**"))
        assertFalse(text.contains("##"))
        assertFalse(text.contains("`"))
    }
}
