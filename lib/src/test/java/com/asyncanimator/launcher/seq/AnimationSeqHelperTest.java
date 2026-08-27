package com.asyncanimator.launcher.seq;

import android.os.Bundle;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * AnimationSeqHelper 单元测试。
 *
 * <p>验证：
 * <ul>
 *   <li>addSeqId 单调递增 + Bundle.putLong</li>
 *   <li>canFinishRecent 时间窗口</li>
 *   <li>canInterceptGesture 时间窗口</li>
 *   <li>delayFinishRecents 防抖</li>
 * </ul>
 */
public class AnimationSeqHelperTest {

    @Test
    public void testInitialState() {
        AnimationSeqHelper helper = new AnimationSeqHelper();
        // 默认没有最近 finish，可以 finish
        assertTrue(helper.canFinishRecent());
        assertTrue(helper.canInterceptGesture());
    }

    @Test
    public void testAddSeqIdWritesToBundle() {
        AnimationSeqHelper helper = new AnimationSeqHelper();
        Bundle bundle = new Bundle();
        helper.addSeqId(bundle);
        long id1 = bundle.getLong("interrupt.transition.startActivity.seqId");
        assertTrue(id1 > 0);

        Bundle bundle2 = new Bundle();
        helper.addSeqId(bundle2);
        long id2 = bundle2.getLong("interrupt.transition.startActivity.seqId");
        // 单调递增
        assertTrue(id2 > id1);
    }

    @Test
    public void testCanFinishRecentAfterRecentFinish() {
        AnimationSeqHelper helper = new AnimationSeqHelper();
        AnimSeqTimeStamp.updateLastRecentFinishTime();
        // 刚 finish → 500ms 内不能 finish
        assertFalse(helper.canFinishRecent());
    }

    @Test
    public void testCanInterceptGestureAfterStartApp() {
        AnimationSeqHelper helper = new AnimationSeqHelper();
        AnimSeqTimeStamp.updateLastStartAppTime();
        // 刚启动 app → 300ms 内不能拦截手势
        assertFalse(helper.canInterceptGesture());
    }

    @Test
    public void testUpdateNextFinishSeqIdIfNeed() {
        AnimationSeqHelper helper = new AnimationSeqHelper();
        Object controller = new Object();
        helper.updateNextFinishSeqIdIfNeed(controller);
        long id1 = helper.getNextFinishSeqId(controller);
        assertTrue(id1 > 0);

        // 不同 controller 返回 0
        assertEquals(0L, helper.getNextFinishSeqId(new Object()));
    }

    @Test
    public void testDelayFinishRecentsImmediate() {
        AnimationSeqHelper helper = new AnimationSeqHelper();
        // 没有"最近 finish" → 立即执行
        boolean delayed = helper.delayFinishRecents(() -> {});
        assertFalse(delayed);
    }

    @Test
    public void testClearFinishRecentsRunnable() {
        AnimationSeqHelper helper = new AnimationSeqHelper();
        // 不抛异常
        helper.clearFinishRecentsRunnable();
    }
}