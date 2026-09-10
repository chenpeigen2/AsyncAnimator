package com.asyncanimator.control

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.android.launcher3.LauncherAnimationRunner
import com.asyncanimator.anim.CustomRectFSpringAnim
import com.asyncanimator.api.PublicApi
import com.asyncanimator.core.LogUtils
import com.asyncanimator.manager.OplusAnimManager

private const val RELEASE_TOUCH_DELAY = 600L
private const val APP_TO_OVERVIEW_CONTINUATION_TIME_OUT_DURATION = 100L

private const val ACTION_DOMESTIC_SEARCH_APP = "android.search.action.DOCK_SEARCH"
private const val ACTION_EXP_SEARCH_APP = "com.oppo.quicksearchbox.action.Dispatch"
private const val SEARCH_INTENT_SOURCE = "drawer_search"

/**
 * 主线程转场状态机，登记最近任务/打开动画，管理三个独立超时场景及延后启动决策。
 * 构造可注入平板判定、真实概览续行状态及最近任务完成策略；默认平板判定读取Context的最小宽度是否达到600dp。
 * 状态与直接回调由主线程维护，运行期查询也应在主线程；这是调度/策略控制，不代替远程窗口或几何驱动。
 * 未覆盖的基类入口仍是空实现，不能据接口名称推断已有平台集成。
 */
@PublicApi
class AnimationController @PublicApi constructor(
    private val isTablet: (Any?) -> Boolean = { context ->
        (context as? Context)?.resources?.configuration?.smallestScreenWidthDp?.let { it >= 600 } ?: false
    },
    private val isOverviewContinuationRunning: (() -> Boolean)? = null,
    private val canFinishRecent: () -> Boolean = { OplusAnimManager.animationSeqHelper.canFinishRecent }
) : DefaultAnimationController() {

    /**
     * 当前转场状态，初始NONE，外部只读；内部通常先写入再通知观察者。
     * 应在主线程查询，状态分类不等同于物理帧完成屏障。
     */
    @PublicApi
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
    /**
     * 当前手势来源包名，优先使用基础Activity包名，其次顶部Activity；结束手势或重置时清空。
     * 主线程只读，不通过系统动态补查，值来自宿主传入场景。
     */
    @PublicApi
    override var swipingUpActivityPkg: String? = null
        private set

    /**
     * 当前特殊场景退出的一次性监听，外部只读，可为空；公开事件/释放要求主线程。
     * 对象存在表示已注册兜底，不保证横屏或分屏条件仍有效，释放按身份保护替代注册。
     */
    @PublicApi
    var specialSceneExitTimeOutListener: TaskStateChangeTimeOutListener? = null
        private set
    /**
     * 当前转场完成兜底监听，外部只读且可能为空。
     * 主线程注册、替换和消费；宿主应通过合格事件桥接，不把槽位存在视为动画正在运行。
     */
    @PublicApi
    var transitionFinishTimeOutListener: TaskStateChangeTimeOutListener? = null
        private set
    /**
     * 当前概览续行事件/超时监听，外部只读；存在本身不代表续行动画处于运行态。
     * 运行态另由显式字段或注入查询提供，取消此监听不会执行挂起启动。
     */
    @PublicApi
    var overviewContinuationTimeOutListener: TaskStateChangeTimeOutListener? = null
        private set

    private var specialSceneExitTimeOutMaxTime = -1L
    private var overviewContinuationRunning = false
    private var openWindowAnimRunning = false
    private val touchHandler: Handler? by lazy { Looper.getMainLooper()?.let(::Handler) }
    private val releaseTouch = Runnable { openWindowAnimRunning = false }

    /**
     * 移除待执行的600毫秒触摸释放任务并立即清除打开窗口输入门控。
     * 由主线程状态路径调用，不改变动画状态或取消真实窗口动画。
     */
    private fun clearTouchGate() {
        touchHandler?.removeCallbacks(releaseTouch)
        openWindowAnimRunning = false
    }

    /**
     * 移除旧释放消息、置打开窗口输入门控并重新安排600毫秒后释放。
     * 主线程调用；无主Handler时只能由其他状态清理入口解除门控，不代表物理动画时长。
     */
    private fun armTouchGate() {
        touchHandler?.removeCallbacks(releaseTouch)
        openWindowAnimRunning = true
        touchHandler?.postDelayed(releaseTouch, RELEASE_TOUCH_DELAY)
    }

    /**
     * 在主线程判断短时打开门控、MULTI_WAITING、REVERSE_OPEN或挂起启动动作是否要求禁用触摸。
     * 只返回策略不拦截事件，MULTI_REVERSE_OPEN并未在此条件中单独列出，不等同于帧运行查询。
     */
    @PublicApi
    override fun forbidTouch(): Boolean {
        checkMainThread()
        return openWindowAnimRunning || animState == AnimationState.MULTI_WAITING ||
            animState == AnimationState.REVERSE_OPEN || startActivityAction != null
    }

    /**
     * 可空最近任务完成动作，写入先检查主线程，读取应同样遵守主线程约束。
     * 新最近任务登记会清旧动作，整体完成时先快照并消费本轮状态再执行，重置只丢弃不执行。
     */
    @PublicApi
    override var recentsAnimFinishCallback: (() -> Unit)? = null
        set(value) {
            checkMainThread()
            field = value
        }
    /**
     * 可空应用打开完成动作，主线程写入和查询，整体动画登记均为空时参与一次收尾。
     * 回调在内部状态重置后执行，重入安装的后继动作不会被旧收尾再次清除。
     */
    @PublicApi
    override var appLaunchAnimFinishCallback: (() -> Unit)? = null
        set(value) {
            checkMainThread()
            field = value
        }

    private var startActivityAction: (() -> Unit)? = null
    private var startActivityWaitType: TaskStateChangeTimeOutListener.Type? = null
    private var startDecisionVersion = 0L

    /**
     * 递增启动决策版本并清空挂起动作及其等待类型，使正在计算的旧决策失效。
     * 内部主线程调用，不执行被丢弃动作，也不自动撤销三个场景超时监听。
     */
    private fun clearPendingStart() {
        startDecisionVersion++
        startActivityAction = null
        startActivityWaitType = null
    }

    /**
     * 记录所选等待类型及可空启动动作，并返回true表示本次决策要求延后。
     * 不重新注册计时器，不执行动作；即使action为null也保持延后结果，调用方应保证对应场景监听存在。
     */
    private fun deferStart(type: TaskStateChangeTimeOutListener.Type, action: (() -> Unit)?): Boolean {
        startActivityWaitType = type
        startActivityAction = action
        return true
    }

    /**
     * 保存新状态后同步广播旧/新状态及内部任务信息，是常规状态变更的统一通知入口。
     * 由主线程调用；监听异常向外传播，字段在通知前已更新，不因回调失败回滚。
     */
    private fun updateAnimState(animationState: AnimationState) {
        val old = animState
        animState = animationState
        onAnimStateChanged(old, animationState, runningTaskInfo)
    }

    /**
     * 在主线程追加矩形句柄并先清除上一轮最近任务完成动作，再按当前状态进入CLOSE或MULTI_CLOSE。
     * 不支持的前态记录诊断并转UNKNOWN；不去重、不启动几何，recentsController和targets当前只保留接口形状。
     */
    @PublicApi
    override fun addRecentsAnim(anim: CustomRectFSpringAnim, recentsController: Any?, targets: Array<out Any?>?) {
        checkMainThread()
        recentsAnims.add(anim)

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

    /**
     * 主线程查询最近任务登记列表是否为空，不检查列表中句柄的真实物理运行。
     * 清理登记即可变true，因此宿主必须另行保证底层动画已完成。
     */
    @PublicApi
    override val allRecentsAnimationEnd: Boolean
        get() = recentsAnims.isEmpty()

    /**
     * 在主线程清空最近任务句柄、待移除任务及一次手势标记，不直接取消底层动画。
     * 仍有打开动画时返回false；否则检查整体完成并返回true，完成回调异常仍会向外传播。
     */
    @PublicApi
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

    /**
     * 在主线程检查是否可收尾：有打开动画、序列策略拒绝或句柄仅逻辑结束时返回false。
     * 最近任务列表为空则允许，否则仅一项且传入ID与句柄ID相同或任一为-1时允许；不核验列表唯一项的对象身份。
     */
    @PublicApi
    override fun canFinishRecentsAnim(anim: CustomRectFSpringAnim, animationId: Int): Boolean {
        checkMainThread()
        if (appLaunchAnims.isNotEmpty() || !canFinishRecent() || anim.isJustNotifyEndCallback) return false
        if (recentsAnims.isEmpty()) return true

        return recentsAnims.size == 1 &&
            (animationId == -1 || anim.animationId == -1 || animationId == anim.animationId)
    }

    /**
     * 在主线程把CLOSE转为REVERSE_OPEN、MULTI_CLOSE转为MULTI_REVERSE_OPEN，其他状态转UNKNOWN。
     * 此入口仅更新状态，传入anim不参与几何重定向，宿主仍需调用实际驱动反转。
     */
    @PublicApi
    override fun revertRecentsAnimation(anim: CustomRectFSpringAnim) {
        checkMainThread()
        when (animState) {
            AnimationState.CLOSE -> updateAnimState(AnimationState.REVERSE_OPEN)
            AnimationState.MULTI_CLOSE -> updateAnimState(AnimationState.MULTI_REVERSE_OPEN)
            else -> updateAnimState(AnimationState.UNKNOWN)
        }
    }

    /**
     * 在主线程登记或移除可空打开工厂，并维护600毫秒输入门控及打开/等待状态。
     * 开始时清除旧序列收尾请求，再从NONE进入OPEN或关闭态进入MULTI_OPEN；结束最后工厂时按手势标记进入等待或检查整体完成。
     * targets不在本实现消费；不去重工厂，不直接启动/结束平台远程动画。
     */
    @PublicApi
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

    /**
     * 仅当打开和最近任务列表均为空时收尾，先快照两个完成动作，再清理本轮内部状态后逐一调用。
     * 已是NONE且无动作时静默清理，不重复发NONE防止观察者递归；重入创建的后继状态不会在旧动作后再次重置。
     * 状态清理或回调失败仍尝试后续步骤，最后抛首个Throwable并附加其他不同异常。
     */
    private fun checkAllAnimationFinished() {
        if (appLaunchAnims.isNotEmpty() || recentsAnims.isNotEmpty()) return
        val recentsFinished = recentsAnimFinishCallback
        val launchFinished = appLaunchAnimFinishCallback
        if (animState == AnimationState.NONE && recentsFinished == null && launchFinished == null) {

            resetState(notifyState = false)
            return
        }

        var failure: Throwable? = null
        /**
         * 执行一个整体收尾步骤并将失败累积到外层failure，保证后续完成动作仍有机会执行。
         * 首个Throwable保留为主异常，后续不同实例作为suppressed；本局部函数不立即重抛。
         */
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

    /**
     * 在主线程重置动画登记、手势/启动门控和完成动作，并广播新状态NONE。
     * 不释放三个超时监听，也不停止底层动画或清空状态观察者；完整销毁请使用destroy。
     */
    @PublicApi
    override fun reset() {
        checkMainThread()
        resetState(notifyState = true)
    }

    /**
     * 清触摸门控、两类动画列表、完成动作及挂起启动，重置续行/手势/转场标记和包名。
     * notifyState为true时通过统一入口广播NONE，否则直接赋值避免空闲递归；不重置超时注册或内部任务引用。
     */
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
     * 在主线程先清除状态观察者，再逐个dispose三个超时监听，清任务引用并reset剩余状态。
     * 用于宿主销毁时解除回调持有；不直接停止已登记底层动画，也不设置永久禁用标志。
     */
    @PublicApi
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

    /**
     * 主线程判断状态是否属于OPEN、REVERSE_OPEN、MULTI_OPEN或MULTI_REVERSE_OPEN。
     * 只作状态分类，不根据实际窗口值或600毫秒触摸门控推导。
     */
    @PublicApi
    override val isOpeningAnim: Boolean
        get() = animState == AnimationState.REVERSE_OPEN
                || animState == AnimationState.OPEN
                || animState == AnimationState.MULTI_OPEN
                || animState == AnimationState.MULTI_REVERSE_OPEN

    /**
     * 主线程判断状态既非NONE也非UNKNOWN，包括等待类状态。
     * 不是600毫秒输入门控或真实帧源运行查询，不提供物理完成保证。
     */
    @PublicApi
    override val isAppWindowAnimRunning: Boolean
        get() = animState != AnimationState.NONE && animState != AnimationState.UNKNOWN

    /**
     * 主线程读取最近任务登记是否非空，列表允许重复且不主动检查句柄是否仍运行。
     * 该属性只表示控制器尚持有登记项。
     */
    @PublicApi
    override val hasRecentsAnim: Boolean
        get() = recentsAnims.isNotEmpty()

    /**
     * 主线程判断当前状态是否恰为MULTI_OPEN，其他打开或反转状态返回false。
     * 不直接统计底层并发动画数量。
     */
    @PublicApi
    override val isMultiOpen: Boolean
        get() = animState == AnimationState.MULTI_OPEN

    /**
     * 主线程判断当前状态是否恰为MULTI_CLOSE，不包括普通CLOSE。
     * 纯状态读取，不触发清理或结束通知。
     */
    @PublicApi
    override val isMultiClose: Boolean
        get() = animState == AnimationState.MULTI_CLOSE

    /**
     * 主线程判断状态为CLOSE或MULTI_CLOSE，名称不意味着几何已停帧。
     * 只用于关闭类状态分支，实际完成仍由动画所有者确认。
     */
    @PublicApi
    override val isClosingAnimAndAnimClosed: Boolean
        get() = animState == AnimationState.CLOSE || animState == AnimationState.MULTI_CLOSE

    /**
     * 在主线程把已确认有效的任务事件交给同类型当前监听，以事件途径提前消费兜底动作。
     * 无对应监听时无操作；宿主须过滤未完成/无关系统通知，本入口不自动推导另一种完成事件。
     */
    @PublicApi
    override fun dispatchTaskStateChange(type: TaskStateChangeTimeOutListener.Type) {
        checkMainThread()
        val listener = when (type) {
            TaskStateChangeTimeOutListener.Type.ON_LAND_SCAPE_SCENE_EXIT -> specialSceneExitTimeOutListener
            TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH -> transitionFinishTimeOutListener
            TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION -> overviewContinuationTimeOutListener
        }
        listener?.onTimeOut(type, 0L)
    }

    /**
     * 创建控制器拥有的一次性监听：仅匹配挂起启动的等待类型时先消费动作，再清场景状态并执行动作。
     * 释放时按实例身份清理对应注册槽，保护回调重入安装的替代监听；必要时清除仍归该场景的挂起启动。
     * 毫秒超时交给主Handler，公开消费/释放使用不捕获控制器的主线程检查。
     */
    private fun timeoutListener(expected: TaskStateChangeTimeOutListener.Type,
                                timeoutMs: Long,
                                clearState: () -> Unit = {}): TaskStateChangeTimeOutListener {
        lateinit var listener: TaskStateChangeTimeOutListener
        listener = TaskStateChangeTimeOutListener(expected, timeoutMs, {

            val action = if (startActivityWaitType == expected) {
                startActivityAction.also { clearPendingStart() }
            } else null
            clearState()
            action?.invoke()
        }, {

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

    /**
     * 在主线程释放旧特殊场景监听，记录基于uptime的截止时间并注册新的毫秒超时。
     * 触发后清横屏、分屏、导航退出及退出间隔标记；替换旧监听可能丢弃其所属挂起动作，不执行它。
     */
    @PublicApi
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

    /**
     * 在主线程dispose旧转场完成监听并安装新兜底，触发后清除转场结束/完成间隔标记。
     * 替换会释放旧监听所有权及其挂起动作；timeoutMs传给Handler，不在此处验证正负。
     */
    @PublicApi
    fun registerTransitionFinishTimeOutListener(timeoutMs: Long) {
        checkMainThread()
        transitionFinishTimeOutListener?.dispose()
        transitionFinishTimeOutListener =
            timeoutListener(TaskStateChangeTimeOutListener.Type.ON_TRANSITION_FINISH, timeoutMs) {
                transitionFinishTimeOutListener = null
                isBetweenTransitionEndAndFinish = false
            }
    }

    /**
     * 在主线程替换概览续行兜底，触发后清空监听槽并置续行运行标记为false。
     * 仅注册计时器并不将续行标为正在运行，真实状态由显式setter或注入查询提供。
     */
    @PublicApi
    fun registerOverviewContinuationTimeOutListener(timeoutMs: Long) {
        checkMainThread()
        overviewContinuationTimeOutListener?.dispose()
        overviewContinuationTimeOutListener =
            timeoutListener(TaskStateChangeTimeOutListener.Type.ON_APP_TO_OVERVIEW_CONTINUATION, timeoutMs) {
                overviewContinuationTimeOutListener = null
                overviewContinuationRunning = false
            }
    }

    /**
     * 在主线程接收AppExitScene快照，null表示无事件，非空错误类型抛参数异常。
     * 先清退出间隔标记；仅三键导航、横屏、低动画且非大屏大模式时开启特殊退出标记和1500毫秒兜底。
     */
    @PublicApi
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

    /**
     * 在主线程更新导航横屏退出的结束/完成间隔标记，仅已进入该特殊退出模式时接受。
     * 不自动注册计时器或执行启动；模式未启用时忽略v。
     */
    @PublicApi
    override fun setBetweenAppExitTransitionEndAndFinish(v: Boolean) {
        checkMainThread()
        if (isNavModeLandScapeOnAppExit) isBetweenAppExitTransitionEndAndFinish = v
    }

    /**
     * 在主线程保存普通转场结束与完成之间的标记。
     * 仅更新状态，不代表收到完成事件，也不自动消费挂起启动或重置超时。
     */
    @PublicApi
    override fun setBetweenTransitionEndAndFinish(v: Boolean) {
        checkMainThread()
        isBetweenTransitionEndAndFinish = v
    }

    /**
     * 在主线程接收GestureScene开始/更新手势，null按上次设备快照结束手势，错误类型拒绝。
     * 非空时记录来源包名和横屏/分屏状态，按分屏、续行、横竖屏及平板条件选择1500毫秒场景兜底。
     * 结束时特定手机横屏且桌面/概览同页场景改用2500毫秒特殊退出兜底；最后按输入是否非空记录手势标记。
     */
    @PublicApi
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

    /**
     * 在主线程保存续行运行状态；true安装100毫秒兜底，false释放监听并丢弃归此场景的挂起启动。
     * false是撤销而非完成事件，不执行被挂起动作；如注入真实运行查询，启动决策优先使用该查询。
     */
    @PublicApi
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

    /**
     * 检查Intent是否属于两种搜索动作或携带source=drawer_search的来源标记。
     * null返回false；只做协议识别，不解析目标包名、不修改Intent或启动组件。
     */
    private fun isSpecialAppScene(intent: Intent?): Boolean =
        intent?.action == ACTION_DOMESTIC_SEARCH_APP || intent?.action == ACTION_EXP_SEARCH_APP ||
            intent?.getStringExtra("source") == SEARCH_INTENT_SOURCE

    /**
     * 在主线程先废弃旧挂起启动，再按特殊退出、转场完成、概览续行的互斥优先级决定是否延后。
     * 特殊退出检查截止时间与设备条件；转场分支即使搜索/分屏已命中也调用call一次；概览分支读取注入状态或显式标记。
     * 外部查询重入改变版本/监听时返回false而不破坏新决策；命中只保存action并返回true，不立即执行。
     * 普通未命中会清理三个监听及间隔标记；特殊场景已超时的早退不走该尾部清理。返回false时宿主负责即时启动。
     */
    @PublicApi
    override fun delayStartActivityIfNeed(context: Any?, intent: Intent?,
                                          call: (() -> Boolean)?, action: (() -> Unit)?): Boolean {
        checkMainThread()
        clearPendingStart()
        val decision = startDecisionVersion

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
            if (LogUtils.isLogOpen()) {
                LogUtils.i("AnimationController", "launch decision overview running=$wait defer=$wait")
            }
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

    /**
     * 主线程返回最近一次手势场景入口维护的处理标记，非空场景置true，结束/清理置false。
     * 不查询输入设备或系统手势识别器。
     */
    @PublicApi
    override val onceGestureProcessing: Boolean
        get() = onceGestureProcessingFlag

    /**
     * 主线程检查普通转场间隔标记为true且确有非空挂起启动动作。
     * 不检查等待类型或执行动作，null动作即使决策延后也返回false。
     */
    @PublicApi
    override val isStartActivityBetweenTransitionEndAndFinish: Boolean
        get() = isBetweenTransitionEndAndFinish && startActivityAction != null
}
