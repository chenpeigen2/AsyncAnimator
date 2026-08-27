package com.asyncanimator.launcher.async;

import com.asyncanimator.core.anim.Animator;
import com.asyncanimator.core.anim.AnimatorListenerAdapter;
import com.asyncanimator.core.anim.ValueAnimator;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AsyncValueAnimator — 跨 Looper 安全的 ValueAnimator。
 *
 * <p>对应分析文档 §6.3。start/cancel/end 先判断"当前线程 vs 目标 Looper"，
 * 不一致时通过 LooperExecutor marshal 过去。
 *
 * <p>listener 跨线程派发（mAsyncAnimCallbacks）：listener fire 时再 marshal 回主线程。
 */
public class AsyncValueAnimator extends ValueAnimator {

    private LooperExecutor mAnimLooperExecutor;
    private AsyncAnimCallbacks mAsyncAnimCallbacks;
    private final AtomicBoolean mIsEnd = new AtomicBoolean(false);

    public AsyncValueAnimator() {
        mAnimLooperExecutor = Executors.MAIN_EXECUTOR;
        mAsyncAnimCallbacks = new AsyncAnimCallbacks();
        addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator a) {
                if (mIsEnd.get()) return;
                mAsyncAnimCallbacks.onAnimationCancel(null);
            }
            @Override public void onAnimationEnd(Animator a) {
                if (mIsEnd.compareAndSet(false, true)) {
                    mAsyncAnimCallbacks.onAnimationEnd(null);
                }
            }
            @Override public void onAnimationStart(Animator a) {
                if (mIsEnd.get()) return;
                mAsyncAnimCallbacks.onAnimationStart(null);
            }
        });
    }

    public void setExecutor(LooperExecutor exec) {
        mAnimLooperExecutor = exec;
    }

    public AsyncAnimCallbacks getAsyncAnimCallbacks() {
        return mAsyncAnimCallbacks;
    }

    @Override
    public void start() {
        if (isCurrentExecutor()) super.start();
        else mAnimLooperExecutor.execute(super::start);
    }

    @Override
    public void cancel() {
        if (isCurrentExecutor()) super.cancel();
        else mAnimLooperExecutor.execute(super::cancel);
    }

    @Override
    public void end() {
        if (isCurrentExecutor()) super.end();
        else mAnimLooperExecutor.execute(super::end);
    }

    private boolean isCurrentExecutor() {
        return mAnimLooperExecutor.isCurrentThread();
    }
}