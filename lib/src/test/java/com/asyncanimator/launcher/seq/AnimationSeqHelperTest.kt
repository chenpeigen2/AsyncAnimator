package com.asyncanimator.launcher.seq

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * AnimationSeqHelper 单元测试。
 *
 * 验证：
 *
 *  - addSeqId 单调递增 + Bundle.putLong
 *  - canFinishRecent 时间窗口
 *  - canInterceptGesture 时间窗口
 *  - delayFinishRecents 防抖
 */
class AnimationSeqHelperTest {

    @Before
    fun resetTimeStamps() {
        // AnimSeqTimeStamp 是全局静态状态，测试间必须复位，否则结果依赖执行顺序
        AnimSeqTimeStamp.resetAllForTest()
        // JVM stub: SystemClock.uptimeMillis() == 0, use monotonic nanoTime.
        AnimSeqTimeStamp.clock = { System.nanoTime() / 1_000_000 }
    }

    @Test
    fun testInitialState() {
        val helper = AnimationSeqHelper()
        // 默认没有最近 finish，可以 finish
        assertTrue(helper.canFinishRecent)
        assertTrue(helper.canInterceptGesture)
    }

    @Test
    @Ignore("android.os.Bundle 需要 Android 运行时；JVM 单测（returnDefaultValues）无法加载，须在设备上验证")
    fun testAddSeqIdWritesToBundle() {
        val helper = AnimationSeqHelper()
        val bundle = Bundle()
        helper.addSeqId(bundle)
        val id1 = bundle.getLong("interrupt.transition.startActivity.seqId")
        assertTrue(id1 > 0)

        val bundle2 = Bundle()
        helper.addSeqId(bundle2)
        val id2 = bundle2.getLong("interrupt.transition.startActivity.seqId")
        // 单调递增
        assertTrue(id2 > id1)
    }

    @Test
    fun testCanFinishRecentAfterRecentFinish() {
        val helper = AnimationSeqHelper()
        AnimSeqTimeStamp.updateLastRecentFinishTime()
        // 刚 finish → 500ms 内不能 finish
        assertFalse(helper.canFinishRecent)
    }

    @Test
    fun testCanInterceptGestureAfterStartApp() {
        val helper = AnimationSeqHelper()
        AnimSeqTimeStamp.updateLastStartAppTime()
        // 刚启动 app → 300ms 内不能拦截手势
        assertFalse(helper.canInterceptGesture)
    }

    @Test
    fun testUpdateNextFinishSeqIdIfNeed() {
        val helper = AnimationSeqHelper()
        val controller = Any()
        helper.updateNextFinishSeqIdIfNeed(controller)
        val id1 = helper.getNextFinishSeqId(controller)
        assertTrue(id1 > 0)

        // 不同 controller 返回 0
        assertEquals(0L, helper.getNextFinishSeqId(Any()))
    }

    @Test
    fun testDelayFinishRecentsImmediate() {
        val helper = AnimationSeqHelper()
        // 没有"最近 finish" → 立即执行
        val delayed = helper.delayFinishRecents {}
        assertFalse(delayed)
    }

    @Test
    fun testClearFinishRecentsRunnable() {
        val helper = AnimationSeqHelper()
        // 不抛异常
        helper.clearFinishRecentsRunnable()
    }
}
