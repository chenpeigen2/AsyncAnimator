package com.asyncanimator.launcher.controller

import android.content.Intent
import com.android.launcher3.LauncherAnimationRunner
import com.asyncanimator.launcher.async.CustomRectFSpringAnim

/**
 * AnimationController — 转场状态机。
 *
 * 对应分析文档 §6.8。12 个 AnimationState + 3 种独立超时 listener。
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

    private val recentsAnims = ArrayList<CustomRectFSpringAnim>()
    private val appLaunchAnims = ArrayList<RemoteAnimationFactory>()
    private val removeTasksMaps = LinkedHashMap<Any, Any>()
    private var currentAnim: CustomRectFSpringAnim? = null

    @Volatile
    private var runningTaskInfo: Any? = null

    private var isLandScapeGesture = false
    private var isSplitScreenGesture = false
    private var isNavModeLandScapeOnAppExit = false
    private var isBetweenAppExitTransitionEndAndFinish = false
    private var isBetweenTransitionEndAndFinish = false
    private var mIsCloseWidgetRemoteAnim = false
    private var onceGestureProcessingFlag = false
    private var mForbidSwipeUpWhileStartingLandApp = false
    private var mSwipingUpActivityPkg: String? = null

    var specialSceneExitTimeOutListener: TaskStateChangeTimeOutListener? = null
        private set
    var transitionFinishTimeOutListener: TaskStateChangeTimeOutListener? = null
        private set
    var overviewContinuationTimeOutListener: TaskStateChangeTimeOutListener? = null
        private set

    private var specialSceneExitTimeOutMaxTime = -1L
    private var overviewContinuationTimeOutMaxTime = -1L

    override var recentsAnimFinishCallback: Runnable? = null
    override var appLaunchAnimFinishCallback: Runnable? = null

    private var startActivityRunnable: Runnable? = null

    // ──── 状态机更新（唯一入口） ────────────────────────────────

    private fun updateAnimState(animationState: AnimationState) {
        val old = animState
        animState = animationState
        onAnimStateChanged(old, animationState, runningTaskInfo)
    }

    // ──── Recents 动画管理 ────────────────────────────────

    override fun addRecentsAnim(anim: CustomRectFSpringAnim, recentsController: Any?, targets: Array<out Any?>?) {
        currentAnim = null
        recentsAnims.add(anim)
        when (animState) {
            AnimationState.NONE, AnimationState.REVERSE_OPEN,
            AnimationState.WAITING, AnimationState.UNKNOWN ->
                updateAnimState(AnimationState.CLOSE)
            AnimationState.OPEN, AnimationState.MULTI_WAITING, AnimationState.MULTI_REVERSE_OPEN ->
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
        return !hasOpeningAnim
    }

    override fun appLaunchAnimStartOrEnd(isEnd: Boolean, factory: RemoteAnimationFactory?,
                                         targets: Array<LauncherAnimationRunner.RemoteAnimationTarget>?) {
        if (!isEnd) {
            when (animState) {
                AnimationState.NONE -> updateAnimState(AnimationState.OPEN)
                AnimationState.CLOSE, AnimationState.MULTI_CLOSE ->
                    updateAnimState(AnimationState.MULTI_OPEN)
                else -> updateAnimState(AnimationState.UNKNOWN)
            }
        }
    }

    override fun reset() {
        updateAnimState(AnimationState.NONE)
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

    fun registerSpecialSceneExitTimeOutListener(timeoutMs: Long) {
        specialSceneExitTimeOutMaxTime = System.currentTimeMillis() + timeoutMs
        specialSceneExitTimeOutListener = TaskStateChangeTimeOutListener { type, _ ->
            if (type == TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT) {
                startActivityRunnable?.run()
                isLandScapeGesture = false
                isSplitScreenGesture = false
                isNavModeLandScapeOnAppExit = false
                isBetweenAppExitTransitionEndAndFinish = false
                startActivityRunnable = null
            }
        }
    }

    fun registerTransitionFinishTimeOutListener(timeoutMs: Long) {
        transitionFinishTimeOutListener = TaskStateChangeTimeOutListener { type, _ ->
            if (type == TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH) {
                startActivityRunnable?.run()
                isBetweenTransitionEndAndFinish = false
                startActivityRunnable = null
            }
        }
    }

    fun registerOverviewContinuationTimeOutListener(timeoutMs: Long) {
        overviewContinuationTimeOutMaxTime = System.currentTimeMillis() + timeoutMs
        overviewContinuationTimeOutListener = TaskStateChangeTimeOutListener { type, _ ->
            if (type == TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION) {
                startActivityRunnable?.run()
                startActivityRunnable = null
            }
        }
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
                                          call: (() -> Boolean)?, runnable: Runnable?): Boolean {
        startActivityRunnable = null
        // 第一层：横屏/分屏退出
        if (specialSceneExitTimeOutListener != null) {
            if (System.currentTimeMillis() > specialSceneExitTimeOutMaxTime) return false
            if (isLandScapeGesture || isSplitScreenGesture
                || (isNavModeLandScapeOnAppExit && isBetweenAppExitTransitionEndAndFinish)) {
                startActivityRunnable = runnable
                return true
            }
        }
        // 第二层：特殊应用
        if (transitionFinishTimeOutListener != null) {
            if (isSplitScreenGesture || call?.invoke() == true) {
                startActivityRunnable = runnable
                return true
            }
        }
        // 第三层：swipe-to-recent 续行
        if (overviewContinuationTimeOutListener != null) {
            if (System.currentTimeMillis() < overviewContinuationTimeOutMaxTime) {
                startActivityRunnable = runnable
                return true
            }
        }
        return false
    }

    override val onceGestureProcessing: Boolean
        get() = onceGestureProcessingFlag

    override val isStartActivityBetweenTransitionEndAndFinish: Boolean
        get() = isBetweenTransitionEndAndFinish

    companion object {
        const val APP_TO_OVERVIEW_CONTINUATION_TIME_OUT_DURATION = 100L
        const val LAND_SPACE_RECENT_ANIM_TIME_OUT_DURATION = 2500L
        const val RECENT_ANIM_FINISH_TIME_OUT_DURATION = 1500L
        const val REMOTE_ANIM_CONFIG_CHANGE_TIME_OUT_DURATION = 1500L
        const val SPLIT_SCREEN_RECENT_ANIM_TIME_OUT_DURATION = 1500L
        const val RELEASE_TOUCH_DELAY = 600L
    }
}
