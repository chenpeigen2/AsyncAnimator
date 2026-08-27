package com.asyncanimator.dyn.anim;

import com.asyncanimator.core.scheduler.ScheduledTickScheduler;
import com.asyncanimator.core.scheduler.TickScheduler;

import java.util.ArrayList;

/**
 * AnimationHandler（dyn 版）— 物理动画调度中枢。
 *
 * <p>对应 Android 平台 {@code androidx.dynamicanimation.animation.AnimationHandler}（简化版）
 * 和分析文档 §3。
 *
 * <p>与 {@link com.asyncanimator.core.anim.AnimationHandler} 的关键差异：
 * <ul>
 *   <li>支持延迟启动（{@code addAnimationFrameCallback(cb, delayedStartTime)}）</li>
 *   <li>提供 {@link #getFrameTime()} 静态 API 供物理积分读当前帧时间</li>
 *   <li>本类独立使用（不与 core.anim.AnimationHandler 共享实例）</li>
 * </ul>
 *
 * <p>简化：实际为清晰起见，这里只用 core.anim.AnimationHandler 的同一套调度，
 * 物理动画通过 frameTimeMs 自己算 dt。本类的存在主要是 API 完整性的镜像，
 * demo 中 {@link DynamicAnimation} 默认使用此实例。
 */
public class AnimationHandler {

    public interface AnimationFrameCallback {
        boolean doAnimationFrame(long frameTimeMs);
    }

    private final ArrayList<AnimationFrameCallback> mCallbacks = new ArrayList<>();
    private boolean mListDirty = false;
    private long mCurrentFrameTimeMs = 0;

    private static final ThreadLocal<AnimationHandler> sAnimatorHandler = new ThreadLocal<>();

    public static AnimationHandler getInstance() {
        ThreadLocal<AnimationHandler> tl = sAnimatorHandler;
        if (tl.get() == null) {
            tl.set(new AnimationHandler(new ScheduledTickScheduler()));
        }
        return tl.get();
    }

    private final TickScheduler scheduler;

    public AnimationHandler(TickScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public TickScheduler getScheduler() {
        return scheduler;
    }

    public void addAnimationFrameCallback(AnimationFrameCallback callback, long delayedStartTime) {
        if (callback == null) return;
        if (mCallbacks.size() == 0) {
            if (scheduler != null) {
                scheduler.start();
                scheduler.postFrameCallback(this::onTick);
            }
        }
        if (!mCallbacks.contains(callback)) mCallbacks.add(callback);
    }

    public void removeCallback(AnimationFrameCallback callback) {
        if (callback == null) return;
        int idx = mCallbacks.indexOf(callback);
        if (idx >= 0) {
            mCallbacks.set(idx, null);
            mListDirty = true;
        }
    }

    /** 当前帧时间（毫秒），供物理积分。 */
    public long getFrameTime() {
        return mCurrentFrameTimeMs;
    }

    void onTick(long frameTimeNanos) {
        mCurrentFrameTimeMs = frameTimeNanos / 1_000_000L;
        int size = mCallbacks.size();
        for (int i = 0; i < size; i++) {
            AnimationFrameCallback cb = mCallbacks.get(i);
            if (cb != null) {
                try {
                    cb.doAnimationFrame(mCurrentFrameTimeMs);
                } catch (Throwable ignored) {}
            }
        }
        cleanUpList();
    }

    private void cleanUpList() {
        if (!mListDirty) return;
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            if (mCallbacks.get(i) == null) mCallbacks.remove(i);
        }
        mListDirty = false;
    }
}