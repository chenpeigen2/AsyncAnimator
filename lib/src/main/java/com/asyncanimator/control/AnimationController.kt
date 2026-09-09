package com.asyncanimator.control

import android.content.Intent
import android.os.SystemClock
import com.android.launcher3.LauncherAnimationRunner
import com.asyncanimator.anim.CustomRectFSpringAnim

private const val APP_TO_OVERVIEW_CONTINUATION_TIME_OUT_DURATION = 100L

/**
 * AnimationController — 转场状态机。
 *
 * 对应 `docs/review/03-controller-manager-seq.md`。12 个 AnimationState + 3 种独立超时 listener。
 *
 * 关键设计：
 *
 *  - 所有状态变更走 [updateAnimState]（private）
 *  - `if (mXxxListener != null)` = 当前场景
 *  - [delayStartActivityIfNeed] 三层决策树（横屏→特殊 app→swipe-to-recent 续行）
 */
class AnimationController : DefaultAnimationController() {

    override var animState: AnimationState = AnimationState.NONE
        private set

    private val recentsAnims = mutableListOf<CustomRectFSpringAnim>()
    private val appLaunchAnims = mutableListOf<RemoteAnimationFactory>()
    private val removeTasksMaps = linkedMapOf<Any, Any>()

    @Volatile
    private var runningTaskInfo: Any? = null

    private var isLandScapeGesture = false
    private var isSplitScreenGesture = false
    private var isNavModeLandScapeOnAppExit = false
    private var isBetweenAppExitTransitionEndAndFinish = false
    private var isBetweenTransitionEndAndFinish = false
    private var onceGestureProcessingFlag = false

    var specialSceneExitTimeOutListener: TaskStateChangeTimeOutListener? = null
        private set
    var transitionFinishTimeOutListener: TaskStateChangeTimeOutListener? = null
        private set
    var overviewContinuationTimeOutListener: TaskStateChangeTimeOutListener? = null
        private set

    private var specialSceneExitTimeOutMaxTime = -1L
    private var overviewContinuationTimeOutMaxTime = -1L

    override var recentsAnimFinishCallback: (() -> Unit)? = null
    override var appLaunchAnimFinishCallback: (() -> Unit)? = null

    private var startActivityAction: (() -> Unit)? = null

    // ──── 状态机更新（唯一入口） ────────────────────────────────

    private fun updateAnimState(animationState: AnimationState) {
        val old = animState
        animState = animationState
        onAnimStateChanged(old, animationState, runningTaskInfo)
    }

    // ──── Recents 动画管理 ────────────────────────────────

    override fun addRecentsAnim(anim: CustomRectFSpringAnim, recentsController: Any?, targets: Array<out Any?>?) {
        recentsAnims.add(anim)
        when (animState) {
            AnimationState.NONE, AnimationState.OPEN,
            AnimationState.REVERSE_OPEN, AnimationState.WAITING ->
                updateAnimState(AnimationState.CLOSE)
            AnimationState.MULTI_OPEN, AnimationState.MULTI_WAITING,
            AnimationState.MULTI_REVERSE_OPEN ->
                updateAnimState(AnimationState.MULTI_CLOSE)
            else ->
                updateAnimState(AnimationState.UNKNOWN)
        }
    }

    override val allRecentsAnimationEnd: Boolean
        get() = recentsAnims.isEmpty()

    override fun cleanUpRecentsAnim(): Boolean {
        val hasOpeningAnim = appLaunchAnims.isNotEmpty()
        recentsAnims.clear()
        removeTasksMaps.clear()
        onceGestureProcessingFlag = false
        if (hasOpeningAnim) return false
        checkAllAnimationFinished()
        return true
    }

    override fun revertRecentsAnimation(anim: CustomRectFSpringAnim) {
        when (animState) {
            AnimationState.CLOSE -> updateAnimState(AnimationState.REVERSE_OPEN)
            AnimationState.MULTI_CLOSE -> updateAnimState(AnimationState.MULTI_REVERSE_OPEN)
            else -> updateAnimState(AnimationState.UNKNOWN)
        }
    }

    override fun appLaunchAnimStartOrEnd(isEnd: Boolean, factory: RemoteAnimationFactory?,
                                         targets: Array<LauncherAnimationRunner.RemoteAnimationTarget>?) {
        if (isEnd) {
            factory?.let { appLaunchAnims.remove(it) }
            if (appLaunchAnims.isEmpty()) {
                if (onceGestureProcessingFlag) {
                    when (animState) {
                        AnimationState.OPEN -> updateAnimState(AnimationState.WAITING)
                        AnimationState.MULTI_OPEN -> updateAnimState(AnimationState.MULTI_WAITING)
                        else -> Unit
                    }
                } else {
                    checkAllAnimationFinished()
                }
            }
        } else {
            factory?.let { appLaunchAnims.add(it) }
            when (animState) {
                AnimationState.NONE -> updateAnimState(AnimationState.OPEN)
                AnimationState.CLOSE, AnimationState.MULTI_CLOSE ->
                    updateAnimState(AnimationState.MULTI_OPEN)
                else -> updateAnimState(AnimationState.UNKNOWN)
            }
        }
    }

    private fun checkAllAnimationFinished() {
        if (appLaunchAnims.isEmpty() && recentsAnims.isEmpty()) {
            recentsAnimFinishCallback?.invoke()
            appLaunchAnimFinishCallback?.invoke()
            reset()
        }
    }

    override fun reset() {
        recentsAnims.clear()
        appLaunchAnims.clear()
        removeTasksMaps.clear()
        recentsAnimFinishCallback = null
        appLaunchAnimFinishCallback = null
        startActivityAction = null
        onceGestureProcessingFlag = false
        isLandScapeGesture = false
        isSplitScreenGesture = false
        isNavModeLandScapeOnAppExit = false
        isBetweenAppExitTransitionEndAndFinish = false
        isBetweenTransitionEndAndFinish = false
        updateAnimState(AnimationState.NONE)
    }

    /**
     * Full lifecycle teardown. Disposes timeout listeners, clears all state.
     * Call from Activity/Fragment onDestroy to prevent leaks.
     */
    fun destroy() {
        specialSceneExitTimeOutListener?.dispose()
        specialSceneExitTimeOutListener = null
        transitionFinishTimeOutListener?.dispose()
        transitionFinishTimeOutListener = null
        overviewContinuationTimeOutListener?.dispose()
        overviewContinuationTimeOutListener = null
        runningTaskInfo = null
        reset()
    }

    override val isOpeningAnim: Boolean
        get() = animState == AnimationState.REVERSE_OPEN
                || animState == AnimationState.OPEN
                || animState == AnimationState.MULTI_OPEN
                || animState == AnimationState.MULTI_REVERSE_OPEN

    override val hasRecentsAnim: Boolean
        get() = recentsAnims.isNotEmpty()

    override val isMultiOpen: Boolean
        get() = animState == AnimationState.MULTI_OPEN

    override val isMultiClose: Boolean
        get() = animState == AnimationState.MULTI_CLOSE

    override val isClosingAnimAndAnimClosed: Boolean
        get() = animState == AnimationState.CLOSE || animState == AnimationState.MULTI_CLOSE

    // ──── 三种超时 listener（独立运行） ─────────────────────────

    /** 三种超时 listener 的公共骨架：匹配 type → 执行挂起的 startActivity → 清理本场景状态。 */
    private fun timeoutListener(expected: TaskStateChangeTimeOutListener.Type,
                                timeoutMs: Long,
                                clearState: () -> Unit = {}): TaskStateChangeTimeOutListener =
        TaskStateChangeTimeOutListener(expected, timeoutMs) {
            startActivityAction?.invoke()
            clearState()
            startActivityAction = null
        }

    fun registerSpecialSceneExitTimeOutListener(timeoutMs: Long) {
        specialSceneExitTimeOutMaxTime = SystemClock.uptimeMillis() + timeoutMs
        specialSceneExitTimeOutListener =
            timeoutListener(TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT, timeoutMs) {
                isLandScapeGesture = false
                isSplitScreenGesture = false
                isNavModeLandScapeOnAppExit = false
                isBetweenAppExitTransitionEndAndFinish = false
            }
    }

    fun registerTransitionFinishTimeOutListener(timeoutMs: Long) {
        transitionFinishTimeOutListener =
            timeoutListener(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, timeoutMs) {
                isBetweenTransitionEndAndFinish = false
            }
    }

    fun registerOverviewContinuationTimeOutListener(timeoutMs: Long) {
        overviewContinuationTimeOutMaxTime = SystemClock.uptimeMillis() + timeoutMs
        overviewContinuationTimeOutListener =
            timeoutListener(TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION, timeoutMs)
    }

    override fun setOnAppExit(context: Any?) {
        isLandScapeGesture = true
        isNavModeLandScapeOnAppExit = true
        isBetweenAppExitTransitionEndAndFinish = true
    }

    override fun setOnceGestureProcessing(state: Any?) {
        onceGestureProcessingFlag = true
    }

    override fun setAppToOverviewContinuationState(v: Boolean) {
        if (v) registerOverviewContinuationTimeOutListener(APP_TO_OVERVIEW_CONTINUATION_TIME_OUT_DURATION)
    }

    // ──── 三层决策树 ────────────────────────────────

    override fun delayStartActivityIfNeed(context: Any?, intent: Intent?,
                                          call: (() -> Boolean)?, action: (() -> Unit)?): Boolean {
        startActivityAction = null
        // 第一层：横屏/分屏退出
        if (specialSceneExitTimeOutListener != null) {
            if (SystemClock.uptimeMillis() > specialSceneExitTimeOutMaxTime) return false
            if (isLandScapeGesture || isSplitScreenGesture
                || (isNavModeLandScapeOnAppExit && isBetweenAppExitTransitionEndAndFinish)) {
                startActivityAction = action
                return true
            }
        } else if (transitionFinishTimeOutListener != null) {
            if (isSplitScreenGesture || call?.invoke() == true) {
                startActivityAction = action
                return true
            }
        } else if (overviewContinuationTimeOutListener != null) {
            if (SystemClock.uptimeMillis() < overviewContinuationTimeOutMaxTime) {
                startActivityAction = action
                return true
            }
        }
        isBetweenAppExitTransitionEndAndFinish = false
        isBetweenTransitionEndAndFinish = false
        specialSceneExitTimeOutListener?.dispose()
        specialSceneExitTimeOutListener = null
        transitionFinishTimeOutListener?.dispose()
        transitionFinishTimeOutListener = null
        overviewContinuationTimeOutListener?.dispose()
        overviewContinuationTimeOutListener = null
        return false
    }

    override val onceGestureProcessing: Boolean
        get() = onceGestureProcessingFlag

    override val isStartActivityBetweenTransitionEndAndFinish: Boolean
        get() = isBetweenTransitionEndAndFinish
}
