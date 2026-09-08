package com.asyncanimator.launcher.playback;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

/**
 * AnimatorPlaybackController — "主时钟驱动所有子动画"的统一播放控制器。
 *
 * <p>对应分析文档 §6.1，是 Launcher 转场动画的核心抽象。
 *
 * <p>关键设计：
 * <ul>
 *   <li>内部一个 LINEAR 0..1 主 ValueAnimator（mAnimationPlayer）作为唯一被 Choreographer 驱动的对象</li>
 *   <li>所有子动画通过 {@link Holder} 同步推进（每帧主时钟回调 → Holder.setProgress → 子 anim.setCurrentFraction）</li>
 *   <li>reverse / setPlayFraction 只需修改主时钟</li>
 *   <li>{@link ProgressMapper} 提供"全局进度→子动画进度"的策略钩子</li>
 * </ul>
 */
public class AnimatorPlaybackController implements ValueAnimator.AnimatorUpdateListener {

    private final ArrayList<Animator> mAnim;
    private final ValueAnimator mAnimationPlayer;
    private Holder[] mChildAnimations;
    private float mCurrentFraction = 0f;
    private long mDuration;
    private boolean mTargetCancelled;
    private boolean mIsDispatchStartPending;
    private Runnable mCancelAction;
    private final HashMap<String, Runnable> mEndActionMap = new HashMap<>();

    public AnimatorPlaybackController(Animator anim, long duration, ArrayList<Holder> holders) {
        mAnim = new ArrayList<>();
        if (anim instanceof AnimatorSet) {
            mAnim.addAll(((AnimatorSet) anim).getChildAnimations());
        } else {
            mAnim.add(anim);
        }
        mDuration = duration;
        mAnimationPlayer = ValueAnimator.ofFloat(0f, 1f);
        mAnimationPlayer.setInterpolator(Interpolators.LINEAR);  // 强制 LINEAR
        mAnimationPlayer.addUpdateListener(this);
        mAnimationPlayer.addListener(new OnAnimationEndDispatcher());
        mChildAnimations = holders.toArray(new Holder[0]);
        // 跟踪 mAnim cancel 状态
        mAnim.get(0).addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator a) {
                mTargetCancelled = true;
            }
            @Override public void onAnimationEnd(Animator a) {
                mTargetCancelled = false;
            }
            @Override public void onAnimationStart(Animator a) {
                mTargetCancelled = false;
            }
        });
    }

    // ──── Holder（子动画代理） ────────────────────────────────

    public static class Holder {
        public final ValueAnimator anim;
        public final float globalEndProgress;
        public final TimeInterpolator interpolator;
        public ProgressMapper mapper = ProgressMapper.DEFAULT;
        public final Object springProperty = null; // 保留字段（弹簧场景用，本 demo 简化）

        public Holder(Animator animator, float totalDuration) {
            ValueAnimator va = (ValueAnimator) animator;
            this.anim = va;
            this.interpolator = va.getInterpolator();
            this.globalEndProgress = animator.getDuration() / totalDuration;
        }

        public void setProgress(float f) {
            float local = mapper.getProgress(f, globalEndProgress);
            anim.setCurrentFraction(local);
        }

        public void reset() {
            anim.setInterpolator(interpolator);
            mapper = ProgressMapper.DEFAULT;
        }
    }

    // ──── ProgressMapper ────────────────────────────────

    public interface ProgressMapper {
        ProgressMapper DEFAULT = (f, g) -> f > g ? 1f : f / g;
        float getProgress(float globalFraction, float globalEndProgress);
    }

    // ──── 帧回调 ────────────────────────────────

    @Override
    public void onAnimationUpdate(ValueAnimator animator) {
        Float v = (Float) animator.getAnimatedValue();
        if (v != null) setPlayFraction(v.floatValue());
    }

    public void setPlayFraction(float f) {
        mCurrentFraction = f;
        if (mTargetCancelled) return;
        f = Math.max(0f, Math.min(1f, f));
        for (Holder holder : mChildAnimations) holder.setProgress(f);
    }

    // ──── 播放控制 ────────────────────────────────

    public void start() {
        mAnimationPlayer.setFloatValues(mCurrentFraction, 1f);
        mAnimationPlayer.setDuration(clampDuration(1f - mCurrentFraction));
        mAnimationPlayer.start();
        mIsDispatchStartPending = true;
    }

    public void reverse() {
        mAnimationPlayer.setFloatValues(mCurrentFraction, 0f);
        mAnimationPlayer.setDuration(clampDuration(mCurrentFraction));
        mAnimationPlayer.start();
        mIsDispatchStartPending = false;
    }

    public void pause() {
        for (Holder holder : mChildAnimations) holder.reset();
        mAnimationPlayer.cancel();
    }

    public long clampDuration(float f) {
        long d = (long) (mDuration * f);
        return Math.min(Math.max(d, 0L), mDuration);
    }

    public void forceFinishIfCloseToEnd() {
        if (mAnimationPlayer.isRunning() && mAnimationPlayer.getAnimatedFraction() <= 0.95f) return;
        mAnimationPlayer.end();
    }

    public void forceFinishIfNeed() {
        if (mAnimationPlayer.isRunning()) mAnimationPlayer.end();
    }

    // ──── End Dispatcher ────────────────────────────────

    private class OnAnimationEndDispatcher extends com.asyncanimator.launcher.pending.AnimationSuccessListener {
        private boolean mDispatched;

        @Override
        public void onAnimationStart(Animator animator) {
            this.mCancelled = false;
            this.mDispatched = false;
        }

        @Override
        public void onAnimationSuccess(Animator animator) {
            if (mDispatched) return;
            dispatchOnEnd();
            if (!mEndActionMap.isEmpty()) {
                for (Runnable r : mEndActionMap.values()) r.run();
                mEndActionMap.clear();
            }
            mDispatched = true;
        }

        @Override
        public void onAnimationCancel(Animator animator) {
            super.onAnimationCancel(animator);
            if (mCancelAction != null) mCancelAction.run();
        }
    }

    public AnimatorPlaybackController dispatchOnStart() {
        for (Animator a : mAnim) {
            for (Animator.AnimatorListener l : a.getListeners()) {
                if (l != null) l.onAnimationStart(a);
            }
        }
        mIsDispatchStartPending = true;
        return this;
    }

    public AnimatorPlaybackController dispatchOnEnd() {
        for (Animator a : mAnim) {
            for (Animator.AnimatorListener l : a.getListeners()) {
                if (l != null) l.onAnimationEnd(a);
            }
        }
        return this;
    }

    public AnimatorPlaybackController dispatchOnCancel() {
        for (Animator a : mAnim) {
            for (Animator.AnimatorListener l : a.getListeners()) {
                if (l != null) l.onAnimationCancel(a);
            }
        }
        return this;
    }

    public void setCancelAction(Runnable r) { mCancelAction = r; }
    public void setEndAction(String key, Runnable r) { mEndActionMap.put(key, r); }

    public ValueAnimator getAnimationPlayer() { return mAnimationPlayer; }
    public float getProgressFraction() { return mCurrentFraction; }

    /** 静态工厂：从 AnimatorSet 构造，递归收集所有 ValueAnimator 子动画。 */
    public static AnimatorPlaybackController wrap(AnimatorSet set, long duration) {
        ArrayList<Holder> list = new ArrayList<>();
        addHoldersRecur(set, duration, list);
        return new AnimatorPlaybackController(set, duration, list);
    }

    public static void addHoldersRecur(Animator anim, long totalDuration, ArrayList<Holder> list) {
        if (anim instanceof ValueAnimator) {
            list.add(new Holder(anim, totalDuration));
            return;
        }
        if (anim instanceof AnimatorSet) {
            for (Animator child : ((AnimatorSet) anim).getChildAnimations()) {
                addHoldersRecur(child, totalDuration, list);
            }
        }
    }
}