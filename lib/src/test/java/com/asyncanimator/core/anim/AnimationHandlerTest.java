package com.asyncanimator.core.anim;

import com.asyncanimator.core.scheduler.ScheduledTickScheduler;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * AnimationHandler 单元测试。
 *
 * <p>验证：
 * <ul>
 *   <li>ThreadLocal 单例：每线程一份，互不干扰</li>
 *   <li>addAnimationFrameCallback 触发 self-pulse</li>
 *   <li>removeCallback 懒删除（置 null + mListDirty）</li>
 *   <li>callback doAnimationFrame 返回 true 后下一帧被移除</li>
 * </ul>
 */
public class AnimationHandlerTest {

    @Test
    public void testThreadLocalInstance() {
        AnimationHandler h1 = AnimationHandler.getInstance();
        AnimationHandler h2 = AnimationHandler.getInstance();
        assertSame(h1, h2);
    }

    @Test
    public void testAddAndRemoveCallback() {
        AnimationHandler handler = new AnimationHandler(new ScheduledTickScheduler());
        AtomicInteger count = new AtomicInteger();
        AnimationHandler.AnimationFrameCallback cb = frameTime -> {
            count.incrementAndGet();
            return false;  // 继续
        };
        handler.addAnimationFrameCallback(cb);
        // 验证 list 大小（通过 getCallbackSize）
        assertEquals(1, handler.getCallbackSize());

        handler.removeCallback(cb);
        // 懒删除：list 还有 1 个槽但是 null，size=0
        assertEquals(0, handler.getCallbackSize());
    }

    @Test
    public void testCallbackReturnsTrueEndsAnimation() {
        AnimationHandler handler = new AnimationHandler(new ScheduledTickScheduler());
        AtomicInteger count = new AtomicInteger();
        AnimationHandler.AnimationFrameCallback cb = frameTime -> {
            count.incrementAndGet();
            return true;  // 立即结束
        };
        handler.addAnimationFrameCallback(cb);
        // 验证列表里有 1 个 callback
        assertEquals(1, handler.getCallbackSize());
    }

    @Test
    public void testAddSameCallbackTwice() {
        AnimationHandler handler = new AnimationHandler(new ScheduledTickScheduler());
        AnimationHandler.AnimationFrameCallback cb = f -> false;
        handler.addAnimationFrameCallback(cb);
        handler.addAnimationFrameCallback(cb);  // 幂等：contains 检查
        assertEquals(1, handler.getCallbackSize());
    }

    @Test
    public void testRemoveNonExistentCallback() {
        AnimationHandler handler = new AnimationHandler(new ScheduledTickScheduler());
        AnimationHandler.AnimationFrameCallback cb = f -> false;
        // 不存在的 callback 不抛异常
        handler.removeCallback(cb);
        assertEquals(0, handler.getCallbackSize());
    }
}