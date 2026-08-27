package com.asyncanimator.launcher.controller;

import com.asyncanimator.launcher.async.CustomRectFSpringAnim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AnimationController — 转场状态机。
 *
 * <p>对应分析文档 §6.8。11 个 AnimationState + 3 种独立超时 listener。
 *
 * <p>关键设计：
 * <ul>
 *   <li>所有状态变更走 {@link #updateAnimState(AnimationState)}（private final）</li>
 *   <li>{@code if (mXxxListener != null) = 当前场景}</li>
 *   <li>{@link #delayStartActivityIfNeed} 三层决策树（横屏→特殊 app→swipe-to-re 续行）</li>
 * </ul>
 */
public class AnimationController extends DefaultAnimationController {

    private AnimationState mAnimState = AnimationState.NONE;

    private final List<CustomRectFSpringAnim> mRecentsAnims = new ArrayList<>();
    private final List<RemoteAnimationFactory> mAppLaunchAnims = new ArrayList<>();
    private final Map<Object, Object> mRemoveTasksMaps = new LinkedHashMap<>();
    private CustomRectFSpringAnim mCurrentAnim;
    private volatile Object mRunningTask;

    private boolean mIsLandScapeGesture;
    private boolean mIsSplitScreenGesture;
    private boolean mIsNavModeLandScapeOnAppExit;
    private boolean mIsBetweenAppExitTransitionEndAndFinish;
    private boolean mIsBetweenTransitionEndAndFinish;
    private boolean mIsCloseWidgetRemoteAnim;
    private boolean mOnceGestureProcessing;
    private boolean mForbidSwipeUpWhileStartingLandApp;
    private String mSwipingUpActivityPkg;

    private TaskStateChangeTimeOutListener mSpecialSceneExitTimeOutListener;
    private TaskStateChangeTimeOutListener mTransitionFinishTimeOutListener;
    private TaskStateChangeTimeOutListener mOverviewContinuationTimeOutListener;
    private long mSpecialSceneExitTimeOutMaxTime = -1;
    private long mOverviewContinuationTimeOutMaxTime = -1;

    private Runnable mRecentsMainFinishCallback;
    private Runnable mRemoteMergeFinishCallback;
    private Runnable startActivityRunnable;

    public static final long APP_TO_OVERVIEW_CONTINUATION_TIME_OUT_DURATION = 100L;
    public static final long LAND_SPACE_RECENT_ANIM_TIME_OUT_DURATION = 2500L;
    public static final long RECENT_ANIM_FINISH_TIME_OUT_DURATION = 1500L;
    public static final long REMOTE_ANIM_CONFIG_CHANGE_TIME_OUT_DURATION = 1500L;
    public static final long SPLIT_SCREEN_RECENT_ANIM_TIME_OUT_DURATION = 1500L;
    public static final long RELEASE_TOUCH_DELAY = 600L;

    // ──── 状态机更新（唯一入口） ────────────────────────────────

    private void updateAnimState(AnimationState animationState) {
        AnimationState old = mAnimState;
        mAnimState = animationState;
        onAnimStateChanged(old, animationState, mRunningTask);
    }

    public AnimationState getAnimState() {
        return mAnimState;
    }

    // ──── Recents 动画管理 ────────────────────────────────

    @Override
    public void addRecentsAnim(CustomRectFSpringAnim recentAnim, Object controller, Object[] targets) {
        mCurrentAnim = null;
        mRecentsAnims.add(recentAnim);
        switch (mAnimState) {
            case NONE: case REVERSE_OPEN: case WAITING: case UNKNOWN:
                updateAnimState(AnimationState.CLOSE);
                break;
            case OPEN: case MULTI_WAITING: case MULTI_REVERSE_OPEN:
                updateAnimState(AnimationState.MULTI_CLOSE);
                break;
            case CLOSE: case MULTI_OPEN:
            default:
                updateAnimState(AnimationState.UNKNOWN);
                break;
        }
    }

    @Override
    public boolean allRecentsAnimationEnd() {
        return mRecentsAnims.isEmpty();
    }

    @Override
    public boolean cleanUpRecentsAnim() {
        boolean hasOpeningAnim = !mAppLaunchAnims.isEmpty();
        mRecentsAnims.clear();
        mRemoveTasksMaps.clear();
        mOnceGestureProcessing = false;
        if (hasOpeningAnim) return false;
        return true;
    }

    @Override
    public void appLaunchAnimStartOrEnd(boolean isEnd, RemoteAnimationFactory factory,
                                         com.android.launcher3.LauncherAnimationRunner.RemoteAnimationTarget[] targets) {
        if (!isEnd) {
            switch (mAnimState) {
                case NONE: updateAnimState(AnimationState.OPEN); break;
                case CLOSE: case MULTI_CLOSE: updateAnimState(AnimationState.MULTI_OPEN); break;
                default: updateAnimState(AnimationState.UNKNOWN);
            }
        }
    }

    @Override
    public void reset() {
        updateAnimState(AnimationState.NONE);
    }

    @Override
    public boolean isOpeningAnim() {
        return mAnimState == AnimationState.REVERSE_OPEN
            || mAnimState == AnimationState.OPEN
            || mAnimState == AnimationState.MULTI_OPEN
            || mAnimState == AnimationState.MULTI_REVERSE_OPEN;
    }

    @Override
    public boolean hasRecentsAnim() {
        return mRecentsAnims.size() > 0;
    }

    @Override
    public boolean isMultiOpen() {
        return mAnimState == AnimationState.MULTI_OPEN;
    }

    @Override
    public boolean isMultiClose() {
        return mAnimState == AnimationState.MULTI_CLOSE;
    }

    @Override
    public boolean isClosingAnimAndAnimClosed() {
        return mAnimState == AnimationState.CLOSE || mAnimState == AnimationState.MULTI_CLOSE;
    }

    // ──── 三种超时 listener（独立运行） ─────────────────────────

    public void registerSpecialSceneExitTimeOutListener(long timeoutMs) {
        mSpecialSceneExitTimeOutMaxTime = System.currentTimeMillis() + timeoutMs;
        mSpecialSceneExitTimeOutListener = new TaskStateChangeTimeOutListener() {
            @Override public void onTimeOut(Type type, long duration) {
                if (type == Type.ON_LAND_SCAPE_SCENE_EXIT) {
                    if (startActivityRunnable != null) startActivityRunnable.run();
                    mIsLandScapeGesture = false;
                    mIsSplitScreenGesture = false;
                    mIsNavModeLandScapeOnAppExit = false;
                    mIsBetweenAppExitTransitionEndAndFinish = false;
                    startActivityRunnable = null;
                }
            }
        };
    }

    public void registerTransitionFinishTimeOutListener(long timeoutMs) {
        mTransitionFinishTimeOutListener = new TaskStateChangeTimeOutListener() {
            @Override public void onTimeOut(Type type, long duration) {
                if (type == Type.ON_TRANSITION_FINISH) {
                    if (startActivityRunnable != null) startActivityRunnable.run();
                    mIsBetweenTransitionEndAndFinish = false;
                    startActivityRunnable = null;
                }
            }
        };
    }

    public void registerOverviewContinuationTimeOutListener(long timeoutMs) {
        mOverviewContinuationTimeOutMaxTime = System.currentTimeMillis() + timeoutMs;
        mOverviewContinuationTimeOutListener = new TaskStateChangeTimeOutListener() {
            @Override public void onTimeOut(Type type, long duration) {
                if (type == Type.ON_APP_TO_OVERVIEW_CONTINUATION) {
                    if (startActivityRunnable != null) startActivityRunnable.run();
                    startActivityRunnable = null;
                }
            }
        };
    }

    public TaskStateChangeTimeOutListener getSpecialSceneExitTimeOutListener() {
        return mSpecialSceneExitTimeOutListener;
    }
    public TaskStateChangeTimeOutListener getTransitionFinishTimeOutListener() {
        return mTransitionFinishTimeOutListener;
    }
    public TaskStateChangeTimeOutListener getOverviewContinuationTimeOutListener() {
        return mOverviewContinuationTimeOutListener;
    }

    public void setOnAppExit(Object context) {
        mIsLandScapeGesture = true;
        mIsNavModeLandScapeOnAppExit = true;
        mIsBetweenAppExitTransitionEndAndFinish = true;
    }

    public void setOnceGestureProcessing(Object state) {
        mOnceGestureProcessing = true;
    }

    public void setAppToOverviewContinuationState(boolean v) {
        if (v) registerOverviewContinuationTimeOutListener(APP_TO_OVERVIEW_CONTINUATION_TIME_OUT_DURATION);
    }

    // ──── 三层决策树 ────────────────────────────────

    @Override
    public boolean delayStartActivityIfNeed(Object context, android.content.Intent intent,
                                            java.util.function.Supplier<Boolean> call, Runnable runnable) {
        startActivityRunnable = null;
        // 第一层：横屏/分屏退出
        if (mSpecialSceneExitTimeOutListener != null) {
            if (System.currentTimeMillis() > mSpecialSceneExitTimeOutMaxTime) return false;
            if (mIsLandScapeGesture || mIsSplitScreenGesture
                || (mIsNavModeLandScapeOnAppExit && mIsBetweenAppExitTransitionEndAndFinish)) {
                startActivityRunnable = runnable;
                return true;
            }
        }
        // 第二层：特殊应用
        if (mTransitionFinishTimeOutListener != null) {
            if (mIsSplitScreenGesture || (call != null && call.get())) {
                startActivityRunnable = runnable;
                return true;
            }
        }
        // 第三层：swipe-to-recent 续行
        if (mOverviewContinuationTimeOutListener != null) {
            if (System.currentTimeMillis() < mOverviewContinuationTimeOutMaxTime) {
                startActivityRunnable = runnable;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean getMOnceGestureProcessing() {
        return mOnceGestureProcessing;
    }

    @Override
    public boolean isStartActivityBetweenTransitionEndAndFinish() {
        return mIsBetweenTransitionEndAndFinish;
    }

    @Override
    public void setRecentsAnimFinishCallback(Runnable r) { mRecentsMainFinishCallback = r; }

    @Override
    public void setAppLaunchAnimFinishCallback(Runnable r) { mRemoteMergeFinishCallback = r; }
}