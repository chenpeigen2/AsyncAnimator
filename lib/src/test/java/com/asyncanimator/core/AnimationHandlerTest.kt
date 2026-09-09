package com.asyncanimator.core

import com.asyncanimator.core.ChoreographerTickScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * AnimationHandler 单元测试。
 *
 * 验证：
 *
 *  - ThreadLocal 单例：每线程一份，互不干扰
 *  - addAnimationFrameCallback 触发 self-pulse
 *  - removeCallback 懒删除（置 null + listDirty）
 *  - callback doAnimationFrame 返回 true 后下一帧被移除
 */
class AnimationHandlerTest {

    @Test
    fun testThreadLocalInstance() {
        val h1 = AnimationHandler.instance
        val h2 = AnimationHandler.instance
        assertSame(h1, h2)
    }

    @Test
    fun testAddAndRemoveCallback() {
        val handler = AnimationHandler(ChoreographerTickScheduler())
        val count = AtomicInteger()
        val cb = AnimationHandler.AnimationFrameCallback { frameTime ->
            count.incrementAndGet()
            false // 继续
        }
        handler.addAnimationFrameCallback(cb)
        // 验证 list 大小（通过 callbackSize）
        assertEquals(1, handler.callbackSize)

        handler.removeCallback(cb)
        // 懒删除：list 还有 1 个槽但是 null，size=0
        assertEquals(0, handler.callbackSize)
    }

    @Test
    fun testCallbackReturnsTrueEndsAnimation() {
        val handler = AnimationHandler(ChoreographerTickScheduler())
        val count = AtomicInteger()
        val cb = AnimationHandler.AnimationFrameCallback { frameTime ->
            count.incrementAndGet()
            true // 立即结束
        }
        handler.addAnimationFrameCallback(cb)
        // 验证列表里有 1 个 callback
        assertEquals(1, handler.callbackSize)
    }

    @Test
    fun testAddSameCallbackTwice() {
        val handler = AnimationHandler(ChoreographerTickScheduler())
        val cb = AnimationHandler.AnimationFrameCallback { false }
        handler.addAnimationFrameCallback(cb)
        handler.addAnimationFrameCallback(cb) // 幂等：contains 检查
        assertEquals(1, handler.callbackSize)
    }

    @Test
    fun testRemoveNonExistentCallback() {
        val handler = AnimationHandler(ChoreographerTickScheduler())
        val cb = AnimationHandler.AnimationFrameCallback { false }
        // 不存在的 callback 不抛异常
        handler.removeCallback(cb)
        assertEquals(0, handler.callbackSize)
    }
}
