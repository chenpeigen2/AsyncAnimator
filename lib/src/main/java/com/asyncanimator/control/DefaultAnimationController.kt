package com.asyncanimator.control

import android.content.Intent
import android.os.Looper
import com.android.launcher3.LauncherAnimationRunner
import com.asyncanimator.anim.CustomRectFSpringAnim

/**
 * DefaultAnimationController — AnimationController 的 no-op 基类。
 *
 * 对应 `docs/review/03-controller-manager-seq.md`。当 feature off 时，OplusAnimManager 返回此基类实例，
 * 状态查询与转场写入采用默认/空实现，但监听注册、移除和显式通知仍有效。
 * delayStartActivityIfNeed 返回 false 且不执行 action；调用方负责立即启动。
 */
open class DefaultAnimationController {

    /** State/observer mutations belong to main. Null Looper is only the JVM stub fallback. */
    protected fun checkMainThread() = checkControllerMainThread()

    private val animStateChangeListeners = mutableListOf<OnAnimStateChangeListener>()

    fun addOnAnimStateChangeListener(listener: OnAnimStateChangeListener?) {
        checkMainThread()
        if (listener != null) animStateChangeListeners.add(listener)
    }

    fun removeOnAnimStateChangeListener(listener: OnAnimStateChangeListener?) {
        checkMainThread()
        if (listener != null) animStateChangeListeners.remove(listener)
    }

    protected fun clearOnAnimStateChangeListeners() {
        checkMainThread()
        animStateChangeListeners.clear()
    }

    open fun onAnimStateChanged(oldState: AnimationState, newState: AnimationState, runningTask: Any?) {
        checkMainThread()
        // 快照遍历：允许回调中增删 listener
        for (l in animStateChangeListeners.toList()) {
            l.onAnimStateChanged(oldState, newState, runningTask)
        }
    }

    // ──── 默认查询与转场空实现（不包含上方监听容器）───────────────

    open val animState: AnimationState get() = AnimationState.NONE
    open val allRecentsAnimationEnd: Boolean get() = false
    open val hasRecentsAnim: Boolean get() = false
    open val isOpeningAnim: Boolean get() = false
    open val isMultiOpen: Boolean get() = false
    open val isMultiClose: Boolean get() = false
    open val isClosingAnimAndAnimClosed: Boolean get() = false
    open val isAppWindowAnimRunning: Boolean get() = false
    open val isStartActivityBetweenTransitionEndAndFinish: Boolean get() = false
    open val openingProgress: Float get() = 0f
    open val clickAppView: Any? get() = null
    open val runningTask: Any? get() = null
    open val swipingUpActivityPkg: String? get() = null
    open val openingRemoteAnimWidgetId: Int get() = -1
    open val forbidSwipeUpWhileStartingLandApp: Boolean get() = false
    open val isCloseWidgetRemoteAnim: Boolean get() = false
    open val onceGestureProcessing: Boolean get() = false

    open fun addRecentsAnim(anim: CustomRectFSpringAnim, recentsController: Any?, targets: Array<out Any?>?) {}
    open fun appLaunchAnimStartOrEnd(isEnd: Boolean, factory: RemoteAnimationFactory?,
                                     targets: Array<LauncherAnimationRunner.RemoteAnimationTarget>?) {}
    open fun canFinishRecentsAnim(anim: CustomRectFSpringAnim, animationId: Int): Boolean = true
    open fun cleanUpRecentsAnim(): Boolean = true
    open fun delayStartActivityIfNeed(context: Any?, intent: Intent?,
                                      call: (() -> Boolean)?, action: (() -> Unit)?): Boolean = false
    open fun dispatchTaskStateChange(type: TaskStateChangeTimeOutListener.Type) {}
    open fun enableSwipeUp() {}
    open fun forbidTouch(): Boolean = false
    open fun forceStopAllRecentAnim() {}
    open fun removeTasksOnRealStart() {}
    open fun reset() {}
    open fun revertRecentsAnimation(anim: CustomRectFSpringAnim) {}
    open fun setAppToOverviewContinuationState(v: Boolean) {}
    open fun setBetweenAppExitTransitionEndAndFinish(v: Boolean) {}
    open fun setBetweenTransitionEndAndFinish(v: Boolean) {}
    open fun setClickAppView(view: Any?) {}
    open fun setCloseWidgetRemoteAnim(v: Boolean) {}
    open fun setOnAppExit(context: Any?) {}
    open fun setOnceGestureProcessing(state: Any?) {}
    open fun setOpeningRemoteAnimWidgetId(id: Int) {}
    open fun updateRunningRemoteTarget(targets: Array<out Any?>?) {}
    open fun updateRunningTask(taskInfo: Any?) {}

    /** no-op 存储：基类 feature off 时吞掉赋值、读取恒为 null。 */
    open var recentsAnimFinishCallback: (() -> Unit)?
        get() = null
        set(value) {}

    /** no-op 存储：同上。 */
    open var appLaunchAnimFinishCallback: (() -> Unit)?
        get() = null
        set(value) {}
}

/** Non-capturing guard: retained, disposed timeout handles must not keep their controller alive. */
internal fun checkControllerMainThread() {
    val main = Looper.getMainLooper() ?: return
    check(Looper.myLooper() === main) { "AnimationController state must be accessed on the main thread" }
}
