package com.asyncanimator.launcher.async;

import android.animation.Animator;
import com.asyncanimator.launcher.pending.NullableAnimatorListener;
import com.asyncanimator.launcher.pending.NullableAnimatorListenerAdapter;
import com.asyncanimator.util.Trace;

import java.util.ArrayList;

/**
 * AsyncAnimCallbacks — listener 容器 + 跨线程派发器。
 *
 * <p>对应分析文档 §6.3.3。业务 listener 在主线程 fire，避免业务代码自己处理线程切换。
 *
 * <p>配合 {@link AsyncValueAnimator}：
 * <ol>
 *   <li>AsyncValueAnimator.start/cancel/end marshal 到目标 Looper</li>
 *   <li>ValueAnimator 在目标 Looper 上 fire listener</li>
 *   <li>本类在 fire listener 时再 marshal 回主线程（兜底）</li>
 * </ol>
 */
public class AsyncAnimCallbacks {

    private final ArrayList<NullableAnimatorListener> mAnimListeners = new ArrayList<>();
    private int mAnimationId = -1;

    public void addListener(NullableAnimatorListener l) {
        if (l == null || mAnimListeners.contains(l)) return;
        mAnimListeners.add(l);
    }

    public void removeListener(NullableAnimatorListener l) {
        int idx = mAnimListeners.indexOf(l);
        if (idx >= 0) mAnimListeners.set(idx, null);
    }

    public void clearListeners() {
        mAnimListeners.clear();
    }

    public void setAnimationId(int id) {
        mAnimationId = id;
    }

    public int getAnimationId() {
        return mAnimationId;
    }

    public void onAnimationStart(Animator animator) {
        Trace.traceBegin(8L, "AsyncAnimStart-" + mAnimationId);
        runOnMainThread(() -> {
            for (NullableAnimatorListener l : mAnimListeners) {
                if (l == null) continue;
                if (l instanceof NullableAnimatorListenerAdapter) {
                    ((NullableAnimatorListenerAdapter) l).setAnimationId(mAnimationId);
                }
                l.onAnimationStart(animator);
            }
        });
        Trace.traceEnd(8L);
    }

    public void onAnimationEnd(Animator animator) {
        Trace.traceBegin(8L, "AsyncAnimEnd-" + mAnimationId);
        runOnMainThread(() -> {
            for (NullableAnimatorListener l : mAnimListeners) {
                if (l == null) continue;
                if (l instanceof NullableAnimatorListenerAdapter) {
                    ((NullableAnimatorListenerAdapter) l).setAnimationId(mAnimationId);
                }
                l.onAnimationEnd(animator);
            }
        });
        Trace.traceEnd(8L);
    }

    public void onAnimationCancel(Animator animator) {
        Trace.traceBegin(8L, "AsyncAnimCancel-" + mAnimationId);
        runOnMainThread(() -> {
            for (NullableAnimatorListener l : mAnimListeners) {
                if (l == null) continue;
                if (l instanceof NullableAnimatorListenerAdapter) {
                    ((NullableAnimatorListenerAdapter) l).setAnimationId(mAnimationId);
                }
                l.onAnimationCancel(animator);
            }
        });
        Trace.traceEnd(8L);
    }

    /** listener 跨线程派发：marshal 到主线程（兜底）。 */
    private void runOnMainThread(Runnable r) {
        LooperExecutor exec = Executors.MAIN_EXECUTOR;
        if (exec.isCurrentThread()) r.run();
        else exec.post(r);
    }
}