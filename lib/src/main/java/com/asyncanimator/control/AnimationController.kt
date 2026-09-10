package com.asyncanimator.control

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.android.launcher3.LauncherAnimationRunner
import com.asyncanimator.anim.CustomRectFSpringAnim
import com.asyncanimator.manager.OplusAnimManager
import com.asyncanimator.core.LogUtils

private const val RELEASE_TOUCH_DELAY = 600L
private const val APP_TO_OVERVIEW_CONTINUATION_TIME_OUT_DURATION = 100L
// OPPO IndicatorEntry / BranchSearchHelper constants; no dependency on their UI modules.
private const val ACTION_DOMESTIC_SEARCH_APP = "android.search.action.DOCK_SEARCH"
private const val ACTION_EXP_SEARCH_APP = "com.oppo.quicksearchbox.action.Dispatch"
private const val SEARCH_INTENT_SOURCE = "drawer_search"

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
 *
 * 注册、状态变更、运行态查询及销毁均由主线程调用，直接回调在调用方线程执行。
 * Timeout 的 Handler 兜底在主线程；状态修改/事件入口/owned listener 消费会在修改前检查主线程。
 * [setOnAppExit] / [setOnceGestureProcessing] 接收可移植的场景快照，不猜测 ROM 配置。
 * [isTablet] 默认用传入 Context 的 sw600dp 配置作可移植判断；集成方可注入设备特性判断。
 * [isOverviewContinuationRunning] 可注入真实动画运行态；未注入时由
 * [setAppToOverviewContinuationState] 显式维护。注册 timeout 本身不表示动画在运行。
 */
class AnimationController(
    private val isTablet: (Any?) -> Boolean = { context ->
        (context as? Context)?.resources?.configuration?.smallestScreenWidthDp?.let { it >= 600 } ?: false
    },
    private val isOverviewContinuationRunning: (() -> Boolean)? = null,
    private val canFinishRecent: () -> Boolean = { OplusAnimManager.animationSeqHelper.canFinishRecent }
) : DefaultAnimationController() {

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
    private var lastGestureScene: GestureScene? = null
    override var swipingUpActivityPkg: String? = null
        private set

    var specialSceneExitTimeOutListener: TaskStateChangeTimeOutListener? = null
        private set
    var transitionFinishTimeOutListener: TaskStateChangeTimeOutListener? = null
        private set
    var overviewContinuationTimeOutListener: TaskStateChangeTimeOutListener? = null
        private set

    private var specialSceneExitTimeOutMaxTime = -1L
    private var overviewContinuationRunning = false
    private var openWindowAnimRunning = false
    private val touchHandler: Handler? by lazy { Looper.getMainLooper()?.let(::Handler) }
    private val releaseTouch = Runnable { openWindowAnimRunning = false }

    private fun clearTouchGate() {
        touchHandler?.removeCallbacks(releaseTouch)
        openWindowAnimRunning = false
    }

    private fun armTouchGate() {
        touchHandler?.removeCallbacks(releaseTouch)
        openWindowAnimRunning = true
        touchHandler?.postDelayed(releaseTouch, RELEASE_TOUCH_DELAY)
    }

    /** Main-thread input policy, not automatic event interception or a frame-completion query. */
    override fun forbidTouch(): Boolean {
        checkMainThread()
        return openWindowAnimRunning || animState == AnimationState.MULTI_WAITING ||
            animState == AnimationState.REVERSE_OPEN || startActivityAction != null
    }

    override var recentsAnimFinishCallback: (() -> Unit)? = null
        set(value) { checkMainThread(); field = value }
    override var appLaunchAnimFinishCallback: (() -> Unit)? = null
        set(value) { checkMainThread(); field = value }

    private var startActivityAction: (() -> Unit)? = null
    private var startActivityWaitType: TaskStateChangeTimeOutListener.Type? = null
    private var startDecisionVersion = 0L

    private fun clearPendingStart() {
        startDecisionVersion++
        startActivityAction = null
        startActivityWaitType = null
    }

    private fun deferStart(type: TaskStateChangeTimeOutListener.Type, action: (() -> Unit)?): Boolean {
        startActivityWaitType = type
        startActivityAction = action
        return true
    }

    // ──── 状态机更新（唯一入口） ────────────────────────────────

    private fun updateAnimState(animationState: AnimationState) {
        val old = animState
        animState = animationState
        onAnimStateChanged(old, animationState, runningTaskInfo)
    }

    // ──── Recents 动画管理 ────────────────────────────────

    override fun addRecentsAnim(anim: CustomRectFSpringAnim, recentsController: Any?, targets: Array<out Any?>?) {
        checkMainThread()
        recentsAnims.add(anim)
        // A new recents animation must not inherit the previous run's finish request.
        // Clear before notifying state observers so they can register the replacement.
        recentsAnimFinishCallback = null
        when (animState) {
            AnimationState.NONE, AnimationState.OPEN,
            AnimationState.REVERSE_OPEN, AnimationState.WAITING ->
                updateAnimState(AnimationState.CLOSE)
            AnimationState.MULTI_OPEN, AnimationState.MULTI_WAITING,
            AnimationState.MULTI_REVERSE_OPEN ->
                updateAnimState(AnimationState.MULTI_CLOSE)
            else -> {
                LogUtils.i("AnimationController", "Error animation state: $animState, add recents anim.")
                updateAnimState(AnimationState.UNKNOWN)
            }
        }
    }

    override val allRecentsAnimationEnd: Boolean
        get() = recentsAnims.isEmpty()

    override fun cleanUpRecentsAnim(): Boolean {
        checkMainThread()
        val hasOpeningAnim = appLaunchAnims.isNotEmpty()
        recentsAnims.clear()
        removeTasksMaps.clear()
        onceGestureProcessingFlag = false
        if (hasOpeningAnim) return false
        checkAllAnimationFinished()
        return true
    }

    override fun canFinishRecentsAnim(anim: CustomRectFSpringAnim, animationId: Int): Boolean {
        checkMainThread()
        if (appLaunchAnims.isNotEmpty() || !canFinishRecent() || anim.isJustNotifyEndCallback) return false
        if (recentsAnims.isEmpty()) return true
        // OPPO matchAnimationId treats -1 as an unassigned ID (permissive fallback).
        return recentsAnims.size == 1 &&
            (animationId == -1 || anim.animationId == -1 || animationId == anim.animationId)
    }

    override fun revertRecentsAnimation(anim: CustomRectFSpringAnim) {
        checkMainThread()
        when (animState) {
            AnimationState.CLOSE -> updateAnimState(AnimationState.REVERSE_OPEN)
            AnimationState.MULTI_CLOSE -> updateAnimState(AnimationState.MULTI_REVERSE_OPEN)
            else -> updateAnimState(AnimationState.UNKNOWN)
        }
    }

    override fun appLaunchAnimStartOrEnd(isEnd: Boolean, factory: RemoteAnimationFactory?,
                                         targets: Array<LauncherAnimationRunner.RemoteAnimationTarget>?) {
        checkMainThread()
        if (isEnd) {
            clearTouchGate()
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
            // A new launch supersedes any queued finish belonging to the previous recents run.
            OplusAnimManager.animationSeqHelper.clearFinishRecentsRunnable()
            armTouchGate()
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
        if (appLaunchAnims.isNotEmpty() || recentsAnims.isNotEmpty()) return
        val recentsFinished = recentsAnimFinishCallback
        val launchFinished = appLaunchAnimFinishCallback
        if (animState == AnimationState.NONE && recentsFinished == null && launchFinished == null) {
            // Even an idle animation state may hold gesture/pending-launch flags. Clear them,
            // but do not emit another NONE event and recurse through a completion observer.
            resetState(notifyState = false)
            return
        }

        // Complete internal state and consume this cycle BEFORE calling external code.
        // A callback/observer may start a successor or register its next completion callbacks.
        // Never reset that successor after callback delivery, even when an old callback throws.
        var failure: Throwable? = null
        fun complete(action: () -> Unit) {
            try { action() }
            catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        complete { reset() }
        complete { recentsFinished?.invoke() }
        complete { launchFinished?.invoke() }
        failure?.let { throw it }
    }

    override fun reset() {
        checkMainThread()
        resetState(notifyState = true)
    }

    private fun resetState(notifyState: Boolean) {
        clearTouchGate()
        recentsAnims.clear()
        appLaunchAnims.clear()
        removeTasksMaps.clear()
        recentsAnimFinishCallback = null
        appLaunchAnimFinishCallback = null
        clearPendingStart()
        overviewContinuationRunning = false
        lastGestureScene = null
        swipingUpActivityPkg = null
        onceGestureProcessingFlag = false
        isLandScapeGesture = false
        isSplitScreenGesture = false
        isNavModeLandScapeOnAppExit = false
        isBetweenAppExitTransitionEndAndFinish = false
        isBetweenTransitionEndAndFinish = false
        if (notifyState) updateAnimState(AnimationState.NONE) else animState = AnimationState.NONE
    }

    /**
     * Full lifecycle teardown. Disposes timeout listeners, clears all state.
     * Call from Activity/Fragment onDestroy to prevent leaks.
     */
    fun destroy() {
        checkMainThread()
        clearOnAnimStateChangeListeners()
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

    /** State classification, not the 600ms input gate or a physical-frame completion signal. */
    override val isAppWindowAnimRunning: Boolean
        get() = animState != AnimationState.NONE && animState != AnimationState.UNKNOWN

    override val hasRecentsAnim: Boolean
        get() = recentsAnims.isNotEmpty()

    override val isMultiOpen: Boolean
        get() = animState == AnimationState.MULTI_OPEN

    override val isMultiClose: Boolean
        get() = animState == AnimationState.MULTI_CLOSE

    override val isClosingAnimAndAnimClosed: Boolean
        get() = animState == AnimationState.CLOSE || animState == AnimationState.MULTI_CLOSE

    /** Host event bridge, not an OEM global bus. Deliver qualified matching task events on main.
     * The host must filter raw OEM transition booleans (e.g. onTransitionFinish(false) is not a
     * finish). This API does not implicitly translate task-listener release into overview end.
     */
    override fun dispatchTaskStateChange(type: TaskStateChangeTimeOutListener.Type) {
        checkMainThread()
        val listener = when (type) {
            TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT -> specialSceneExitTimeOutListener
            TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH -> transitionFinishTimeOutListener
            TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION -> overviewContinuationTimeOutListener
        }
        listener?.onTimeOut(type, 0L)
    }

    // ──── 三种超时 listener（独立运行） ─────────────────────────

    /** 三种超时 listener 的公共骨架：匹配 type → 执行挂起的 startActivity → 清理本场景状态。 */
    private fun timeoutListener(expected: TaskStateChangeTimeOutListener.Type,
                                timeoutMs: Long,
                                clearState: () -> Unit = {}): TaskStateChangeTimeOutListener {
        lateinit var listener: TaskStateChangeTimeOutListener
        listener = TaskStateChangeTimeOutListener(expected, timeoutMs, {
            // Only the scenario selected by delayStartActivityIfNeed owns this launch.
            val action = if (startActivityWaitType == expected) {
                startActivityAction.also { clearPendingStart() }
            } else null
            clearState()
            action?.invoke()
        }, {
            // The three slots ARE this controller's registry; no global bus/extra Set is needed.
            // Identity checks protect a replacement installed by the just-completed action.
            val removed = when (expected) {
                TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT ->
                    (specialSceneExitTimeOutListener === listener).also {
                        if (it) specialSceneExitTimeOutListener = null
                    }
                TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH ->
                    (transitionFinishTimeOutListener === listener).also {
                        if (it) transitionFinishTimeOutListener = null
                    }
                TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION ->
                    (overviewContinuationTimeOutListener === listener).also {
                        if (it) overviewContinuationTimeOutListener = null
                    }
            }
            if (removed && startActivityWaitType == expected) clearPendingStart()
        }, ::checkControllerMainThread)
        return listener
    }

    fun registerSpecialSceneExitTimeOutListener(timeoutMs: Long) {
        checkMainThread()
        specialSceneExitTimeOutListener?.dispose()
        specialSceneExitTimeOutMaxTime = SystemClock.uptimeMillis() + timeoutMs
        specialSceneExitTimeOutListener =
            timeoutListener(TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT, timeoutMs) {
                specialSceneExitTimeOutListener = null
                isLandScapeGesture = false
                isSplitScreenGesture = false
                isNavModeLandScapeOnAppExit = false
                isBetweenAppExitTransitionEndAndFinish = false
            }
    }

    fun registerTransitionFinishTimeOutListener(timeoutMs: Long) {
        checkMainThread()
        transitionFinishTimeOutListener?.dispose()
        transitionFinishTimeOutListener =
            timeoutListener(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, timeoutMs) {
                transitionFinishTimeOutListener = null
                isBetweenTransitionEndAndFinish = false
            }
    }

    /** Arms only the fallback timer; use setAppToOverviewContinuationState or a live provider for running state. */
    fun registerOverviewContinuationTimeOutListener(timeoutMs: Long) {
        checkMainThread()
        overviewContinuationTimeOutListener?.dispose()
        overviewContinuationTimeOutListener =
            timeoutListener(TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION, timeoutMs) {
                overviewContinuationTimeOutListener = null
                overviewContinuationRunning = false
            }
    }

    /** Pass an AppExitScene snapshot; null is no event, not a synthetic landscape exit. */
    override fun setOnAppExit(context: Any?) {
        checkMainThread()
        if (context == null) return
        val scene = requireNotNull(context as? AppExitScene) { "setOnAppExit requires AppExitScene" }
        isBetweenAppExitTransitionEndAndFinish = false
        if (scene.threeButtonNavigation && scene.landscape && scene.adaptiveLowAnimation &&
            !scene.largeDisplayInLargeMode) {
            isNavModeLandScapeOnAppExit = true
            registerSpecialSceneExitTimeOutListener(1500L)
        }
    }

    override fun setBetweenAppExitTransitionEndAndFinish(v: Boolean) {
        checkMainThread()
        if (isNavModeLandScapeOnAppExit) isBetweenAppExitTransitionEndAndFinish = v
    }

    override fun setBetweenTransitionEndAndFinish(v: Boolean) {
        checkMainThread()
        isBetweenTransitionEndAndFinish = v
    }

    /** GestureScene begins/updates a gesture; null ends it using the last device snapshot. */
    override fun setOnceGestureProcessing(state: Any?) {
        checkMainThread()
        val scene = if (state == null) null else
            requireNotNull(state as? GestureScene) { "setOnceGestureProcessing requires GestureScene or null" }
        swipingUpActivityPkg = null
        if (scene != null) {
            lastGestureScene = scene
            swipingUpActivityPkg = scene.baseActivityPackage ?: scene.topActivityPackage
            specialSceneExitTimeOutListener?.dispose()
            specialSceneExitTimeOutListener = null
            isLandScapeGesture = scene.landscape
            isSplitScreenGesture = scene.splitScreen
            isBetweenTransitionEndAndFinish = false
            if (scene.splitScreen || scene.recentContinuation) {
                if (scene.landscape) registerSpecialSceneExitTimeOutListener(1500L)
                else registerTransitionFinishTimeOutListener(1500L)
            } else if (!scene.landscape || scene.tablet) {
                registerTransitionFinishTimeOutListener(1500L)
            }
        } else {
            val previous = lastGestureScene
            if (!isSplitScreenGesture && isLandScapeGesture && previous != null &&
                !previous.tablet && previous.homeAndOverviewSame) {
                registerSpecialSceneExitTimeOutListener(2500L)
            }
        }
        onceGestureProcessingFlag = scene != null
    }

    /** false unregisters/cancels this scenario; it does not execute a deferred launch. */
    override fun setAppToOverviewContinuationState(v: Boolean) {
        checkMainThread()
        overviewContinuationRunning = v
        if (v) {
            registerOverviewContinuationTimeOutListener(APP_TO_OVERVIEW_CONTINUATION_TIME_OUT_DURATION)
        } else {
            overviewContinuationTimeOutListener?.dispose()
            overviewContinuationTimeOutListener = null
            if (startActivityWaitType == TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION) {
                clearPendingStart()
            }
        }
    }

    private fun isSpecialAppScene(intent: Intent?): Boolean =
        intent?.action == ACTION_DOMESTIC_SEARCH_APP || intent?.action == ACTION_EXP_SEARCH_APP ||
            intent?.getStringExtra("source") == SEARCH_INTENT_SOURCE

    // ──── 三层决策树 ────────────────────────────────

    override fun delayStartActivityIfNeed(context: Any?, intent: Intent?,
                                          call: (() -> Boolean)?, action: (() -> Unit)?): Boolean {
        checkMainThread()
        clearPendingStart()
        val decision = startDecisionVersion
        // Providers are external code: reset, nested decisions or listener replacement invalidate
        // this evaluation. Never install an ownerless action or clean up a newer decision's slots.
        val special = specialSceneExitTimeOutListener
        val transition = transitionFinishTimeOutListener
        val overview = overviewContinuationTimeOutListener
        if (special != null) {
            val expired = SystemClock.uptimeMillis() > specialSceneExitTimeOutMaxTime
            if (expired) {
                LogUtils.i("AnimationController", "launch decision special expired=true defer=false")
                return false
            }
            val wait = (isLandScapeGesture && !isTablet(context)) || isSplitScreenGesture ||
                (isNavModeLandScapeOnAppExit && isBetweenAppExitTransitionEndAndFinish)
            if (decision != startDecisionVersion || specialSceneExitTimeOutListener !== special) return false
            if (LogUtils.isLogOpen()) LogUtils.i("AnimationController",
                "launch decision special landscape=$isLandScapeGesture split=$isSplitScreenGesture " +
                    "nav=$isNavModeLandScapeOnAppExit between=$isBetweenAppExitTransitionEndAndFinish defer=$wait")
            if (wait) return deferStart(TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT, action)
        } else if (transition != null) {
            // OPPO evaluates Supplier once even when the search/split predicate is true.
            val specialAppScene = isSpecialAppScene(intent)
            val requestedByCaller = call?.invoke() == true
            if (decision != startDecisionVersion || transitionFinishTimeOutListener !== transition) return false
            val wait = specialAppScene || isSplitScreenGesture || requestedByCaller
            if (LogUtils.isLogOpen()) LogUtils.i("AnimationController",
                "launch decision transition search=$specialAppScene between=$isBetweenTransitionEndAndFinish " +
                    "split=$isSplitScreenGesture caller=$requestedByCaller defer=$wait")
            if (wait) return deferStart(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, action)
        } else if (overview != null) {
            val wait = isOverviewContinuationRunning?.invoke() ?: overviewContinuationRunning
            if (decision != startDecisionVersion || overviewContinuationTimeOutListener !== overview) return false
            if (LogUtils.isLogOpen()) LogUtils.i("AnimationController", "launch decision overview running=$wait defer=$wait")
            if (wait) return deferStart(TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION, action)
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
        get() = isBetweenTransitionEndAndFinish && startActivityAction != null
}
