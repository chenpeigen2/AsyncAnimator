package com.asyncanimator.launcher.seq;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.asyncanimator.util.Trace;

import java.util.HashMap;
import java.util.Map;

/**
 * AnimationSeqHelper — Recents 动画 SeqId 防抖。
 *
 * <p>对应分析文档 §6.9。
 *
 * <p>核心机制：
 * <ul>
 *   <li>全局单调 seqId 写入 Bundle（跨进程同步）</li>
 *   <li>{@code canFinishRecent()} 检查 500ms 内是否刚结束过</li>
 *   <li>{@code canInterceptGesture()} 检查 300ms 内是否刚启动过 app</li>
 *   <li>{@code delayFinishRecents(Runnable)} 500ms 内必须 finish</li>
 * </ul>
 */
public class AnimationSeqHelper extends DefaultAnimationSeqHelper {

    public static final long MAX_DELAY_TIME = 500L;
    public static final long MAX_GO_NORMAL_DELAY_TIME = 200L;
    public static final long MAX_INTERCEPT_GESTURE_DELAY_TIME = 300L;

    private static final int MSG_EXC_RUNNABLE = 1;
    private static final String KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID =
        "interrupt.transition.startActivity.seqId";

    private long seqId = 0;
    private Runnable delayRunnable;
    /** 懒创建：JVM 单测环境没有主 Looper，构造期不触碰 android.os.Handler。 */
    private Handler handler;

    private Handler getOrCreateHandler() {
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper(), msg -> {
                if (msg.what == MSG_EXC_RUNNABLE) {
                    Trace.traceBegin(8L, "exc delayRunnable");
                    if (delayRunnable != null) delayRunnable.run();
                    delayRunnable = null;
                    Trace.traceEnd(8L);
                }
                return true;
            });
        }
        return handler;
    }

    /** (controller, seqId) Pair，记录当前 recents controller 对应的 seqId。 */
    private Object nextFinishSeqId;

    private long updateSeqId() {
        return ++seqId;
    }

    @Override
    public void addSeqId(Bundle bundle) {
        if (bundle == null) return;
        long id = updateSeqId();
        bundle.putLong(KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID, id);
    }

    @Override
    public boolean canFinishRecent() {
        return AnimSeqTimeStamp.getTimeGapToLastRecentFinishTime() > MAX_DELAY_TIME;
    }

    @Override
    public boolean canInterceptGesture() {
        return AnimSeqTimeStamp.getTimeGapToLastStartAppTime() > MAX_INTERCEPT_GESTURE_DELAY_TIME;
    }

    @Override
    public boolean delayFinishRecents(Runnable r) {
        if (canFinishRecent()) {
            if (r != null) r.run();
            return false;
        }
        Trace.traceBegin(8L, "delayFinishRecents");
        clearFinishRecentsRunnable();
        delayRunnable = r;
        long delay = MAX_DELAY_TIME - AnimSeqTimeStamp.getTimeGapToLastRecentFinishTime();
        getOrCreateHandler().sendEmptyMessageDelayed(MSG_EXC_RUNNABLE, Math.max(0L, delay));
        Trace.traceEnd(8L);
        return true;
    }

    @Override
    public void clearFinishRecentsRunnable() {
        if (handler != null) handler.removeMessages(MSG_EXC_RUNNABLE);
        delayRunnable = null;
    }

    @Override
    public void updateNextFinishSeqIdIfNeed(Object recentsController) {
        long id = updateSeqId();
        nextFinishSeqId = new SeqIdPair(recentsController, id);
    }

    @Override
    public long getNextFinishSeqId(Object recentsController) {
        if (nextFinishSeqId instanceof SeqIdPair) {
            SeqIdPair p = (SeqIdPair) nextFinishSeqId;
            if (p.controller == recentsController) return p.seqId;
        }
        return 0L;
    }

    /** 简化的 Pair（避免 Kotlin Pair 依赖）。 */
    private static class SeqIdPair {
        final Object controller;
        final long seqId;
        SeqIdPair(Object c, long s) { this.controller = c; this.seqId = s; }
    }
}