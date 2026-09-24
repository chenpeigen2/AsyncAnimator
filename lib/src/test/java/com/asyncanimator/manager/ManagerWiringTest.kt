package com.asyncanimator.manager

import com.asyncanimator.anim.CustomRectFSpringAnim
import com.asyncanimator.control.AnimationController
import com.asyncanimator.seq.AnimSeqTimeStamp
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.Shadows.shadowOf

/** 工厂层接线回归：manager 注入后控制器实时读取序列窗口，序列 helper 使用注入的能力查询。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ManagerWiringTest {
    private lateinit var originalClock: () -> Long
    private var previousEnabled = false
    private val rects = mutableListOf<CustomRectFSpringAnim>()

    @Before
    fun setup() {
        originalClock = AnimSeqTimeStamp.clock
        AnimSeqTimeStamp.resetAllForTest()
        previousEnabled = OplusAnimManager.interruptionEnabled
        if (!previousEnabled) OplusAnimManager.interruptionEnabled = true
    }

    @After
    fun cleanup() {
        AnimSeqTimeStamp.clock = originalClock
        AnimSeqTimeStamp.resetAllForTest()
        rects.forEach { it.dispose() }
        if (OplusAnimManager.interruptionEnabled != previousEnabled) {
            OplusAnimManager.interruptionEnabled = previousEnabled
        }
    }

    private fun rect(id: Int = 1) = CustomRectFSpringAnim(
        CustomRectFSpringAnim.AnimType.SWIPE_TO_HOME
    ).also {
        it.animationId = id
        rects.add(it)
    }

    @Test
    fun testWiredControllerReadsLiveSeqFinishWindow() {
        val controller = OplusAnimManager.animController as AnimationController
        AnimSeqTimeStamp.clock = { 100L }
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        assertFalse(controller.canFinishRecentsAnim(rect(), 1))
        AnimSeqTimeStamp.resetLastRecentFinishTime()
        assertTrue(controller.canFinishRecentsAnim(rect(), 1))
    }

    @Test
    fun testWiredControllerClearCallbackReachesSeqHelper() {
        val controller = OplusAnimManager.animController as AnimationController
        val seq = OplusAnimManager.animationSeqHelper
        var ran = false
        AnimSeqTimeStamp.clock = { 100L }
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        assertTrue(seq.delayFinishRecents { ran = true })
        controller.appLaunchAnimStartOrEnd(false, null, null)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(ran)
    }

    @Test
    fun testWiredSeqHelperUsesInjectedCapabilityQuery() {
        val seq = OplusAnimManager.animationSeqHelper
        AnimSeqTimeStamp.clock = { 100L }
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        assertFalse(seq.canFinishRecent)
        OplusAnimManager.interruptionEnabled = false
        try {
            val degraded = OplusAnimManager.animationSeqHelper
            assertTrue(degraded.canFinishRecent)
        } finally {
            OplusAnimManager.interruptionEnabled = true
        }
    }
}
