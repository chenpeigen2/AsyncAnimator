package com.asyncanimator.launcher.pending;

import com.asyncanimator.core.anim.Animator;
import com.asyncanimator.core.anim.AnimatorListenerAdapter;

import java.util.function.Consumer;

/**
 * AnimatorListeners — 工厂方法集合。
 *
 * <p>对应分析文档 §6.3.7。提供三种 listener 工厂：
 * <ul>
 *   <li>{@link #forEndCallback(Runnable)} — 任何 end 都触发</li>
 *   <li>{@link #forEndCallback(Consumer)} — 区分 success (true) / cancel (false)</li>
 *   <li>{@link #forSuccessCallback(Runnable)} — 仅 success 触发</li>
 * </ul>
 */
public final class AnimatorListeners {

    private AnimatorListeners() {}

    public static com.asyncanimator.core.anim.Animator.AnimatorListener forEndCallback(Runnable r) {
        return new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) {
                if (r != null) r.run();
            }
        };
    }

    public static com.asyncanimator.core.anim.Animator.AnimatorListener forEndCallback(Consumer<Boolean> c) {
        return new AnimatorListenerAdapter() {
            private boolean listenerCalled = false;
            @Override public void onAnimationEnd(Animator animator) {
                if (listenerCalled) return;
                listenerCalled = true;
                if (c != null) c.accept(Boolean.TRUE);
            }
            @Override public void onAnimationCancel(Animator animator) {
                if (listenerCalled) return;
                listenerCalled = true;
                if (c != null) c.accept(Boolean.FALSE);
            }
        };
    }

    public static com.asyncanimator.core.anim.Animator.AnimatorListener forSuccessCallback(Runnable r) {
        return new AnimationSuccessListener() {
            @Override public void onAnimationSuccess(Animator animator) {
                if (r != null) r.run();
            }
        };
    }
}