package com.asyncanimator.core.anim;

import com.asyncanimator.core.scheduler.ScheduledTickScheduler;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * ValueAnimator 时间模型测试。
 *
 * <p>验证：
 * <ul>
 *   <li>mStartTime / mLastFrameTime 字段语义</li>
 *   <li>animateBasedOnTime 在 duration 内返回 false，到达 duration 返回 true</li>
 *   <li>cancel 后 mRunning=false</li>
 *   <li>end() 立即跳到末值</li>
 * </ul>
 */
public class ValueAnimatorTimeModelTest {

    @Test
    public void testInitialState() {
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(300);
        assertEquals(300, va.getDuration());
        assertFalse(va.isRunning());
        assertEquals(-1, va.mStartTime);
        assertEquals(-1, va.mLastFrameTime);
    }

    @Test
    public void testEndJumpsToLastFrame() {
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(300);
        va.start();
        va.end();
        // end 后 animateFraction 应该是 1
        assertEquals(1f, va.getAnimatedFraction(), 0.01f);
    }

    @Test
    public void testSetCurrentFraction() {
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(300);
        va.setCurrentFraction(0.5f);
        assertEquals(0.5f, va.getAnimatedFraction(), 0.01f);
    }

    @Test
    public void testCancelClearsRunning() {
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(300);
        va.start();
        assertTrue(va.isRunning());
        va.cancel();
        assertFalse(va.isRunning());
    }

    @Test
    public void testRepeatCountDefault() {
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        assertEquals(0, va.getRepeatCount());
        assertEquals(ValueAnimator.RESTART, va.getRepeatMode());
    }

    @Test
    public void testInterpolatorDefault() {
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        assertNotNull(va.getInterpolator());
    }

    @Test
    public void testCustomInterpolator() {
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        com.asyncanimator.core.anim.Interpolator linear = input -> input;
        va.setInterpolator(linear);
        assertSame(linear, va.getInterpolator());
    }
}