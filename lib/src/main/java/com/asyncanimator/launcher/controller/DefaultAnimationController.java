package com.asyncanimator.launcher.controller;

import com.asyncanimator.launcher.async.CustomRectFSpringAnim;

import java.util.ArrayList;

/**
 * DefaultAnimationController — AnimationController 的 no-op 基类。
 *
 * <p>对应分析文档 §6.10。当 feature off 时，OplusAnimManager 返回此基类实例，
 * 所有方法都是 no-op，业务调用没有副作用。
 */
public class DefaultAnimationController {

    private final ArrayList<OnAnimStateChangeListener> animStateChangeListeners = new ArrayList<>();

    public final void addOnAnimStateChangeListener(OnAnimStateChangeListener listener) {
        if (listener != null) animStateChangeListeners.add(listener);
    }

    public final void removeOnAnimStateChangeListener(OnAnimStateChangeListener listener) {
        if (listener != null) animStateChangeListeners.remove(listener);
    }

    public void onAnimStateChanged(AnimationState oldState, AnimationState newState, Object runningTask) {
        for (OnAnimStateChangeListener l : new ArrayList<>(animStateChangeListeners)) {
            l.onAnimStateChanged(oldState, newState, runningTask);
        }
    }

    // ──── 全部 no-op 实现（feature off 时业务调用安全）───────────────

    public void addRecentsAnim(CustomRectFSpringAnim anim, Object recentsController, Object[] targets) {}
    public boolean allRecentsAnimationEnd() { return false; }
    public void appLaunchAnimStartOrEnd(boolean isEnd, RemoteAnimationFactory factory,
                                         com.android.launcher3.LauncherAnimationRunner.RemoteAnimationTarget[] targets) {}
    public boolean canFinishRecentsAnim(CustomRectFSpringAnim anim, int animationId) { return true; }
    public boolean cleanUpRecentsAnim() { return true; }
    public boolean delayStartActivityIfNeed(Object context, android.content.Intent intent,
                                            java.util.function.Supplier<Boolean> call, Runnable runnable) { return false; }
    public void enableSwipeUp() {}
    public boolean getMForbidSwipeUpWhileStartingLandApp() { return false; }
    public boolean forbidTouch() { return false; }
    public void forceStopAllRecentAnim() {}
    public AnimationState getAnimState() { return AnimationState.NONE; }
    public Object getMClickAppView() { return null; }
    public float getOpeningProgress() { return 0f; }
    public int getMOpeningRemoteAnimWidgetId() { return -1; }
    public Object getMRunningTask() { return null; }
    public String getMSwipingUpActivityPkg() { return null; }
    public boolean hasRecentsAnim() { return false; }
    public boolean isAppWindowAnimRunning() { return false; }
    public boolean getMIsCloseWidgetRemoteAnim() { return false; }
    public boolean isClosingAnimAndAnimClosed() { return false; }
    public boolean isMultiClose() { return false; }
    public boolean isMultiOpen() { return false; }
    public boolean getMOnceGestureProcessing() { return false; }
    public boolean isOpeningAnim() { return false; }
    public boolean isStartActivityBetweenTransitionEndAndFinish() { return false; }
    public void removeTasksOnRealStart() {}
    public void reset() {}
    public void revertRecentsAnimation(CustomRectFSpringAnim anim) {}
    public void setAppLaunchAnimFinishCallback(Runnable runnable) {}
    public void setAppToOverviewContinuationState(boolean v) {}
    public void setBetweenAppExitTransitionEndAndFinish(boolean v) {}
    public void setBetweenTransitionEndAndFinish(boolean v) {}
    public void setClickAppView(Object view) {}
    public void setCloseWidgetRemoteAnim(boolean v) {}
    public void setOnAppExit(Object context) {}
    public void setOnceGestureProcessing(Object state) {}
    public void setOpeningRemoteAnimWidgetId(int id) {}
    public void setRecentsAnimFinishCallback(Runnable runnable) {}
    public void updateRunningRemoteTarget(Object[] targets) {}
    public void updateRunningTask(Object taskInfo) {}
}