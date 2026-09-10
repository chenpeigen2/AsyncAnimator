package com.asyncanimator.control

import android.content.Intent
import android.os.Looper
import com.android.launcher3.LauncherAnimationRunner
import com.asyncanimator.anim.CustomRectFSpringAnim
import com.asyncanimator.api.PublicApi

/**
 * 功能关闭时使用的默认控制器，状态查询/转场写入为常量或空实现，状态监听容器仍真实有效。
 * 无参构造创建空监听列表，不获取系统动画资源；只有监听/显式通知等入口检查主线程，空实现不隐式切线程。
 * 延后启动返回false且不执行action，宿主负责即时启动；子类可以选择性覆盖能力。
 */
@PublicApi
open class DefaultAnimationController {

    /**
     * 委托共享入口检查控制器状态/监听操作的主线程约束。
     * 真实主Looper存在时异线程调用抛出状态异常；无主Looper的JVM降级环境允许返回。
     */
    protected fun checkMainThread() = checkControllerMainThread()

    private val animStateChangeListeners = mutableListOf<OnAnimStateChangeListener>()

    /**
     * 在主线程把非空监听追加到列表，null忽略，重复实例不会去重。
     * 注册本身不回放当前状态；相同实例多次登记会在后续快照中多次收到通知。
     */
    @PublicApi
    fun addOnAnimStateChangeListener(listener: OnAnimStateChangeListener?) {
        checkMainThread()
        if (listener != null) animStateChangeListeners.add(listener)
    }

    /**
     * 在主线程移除第一个匹配的非空监听，null或不存在时无操作。
     * 不会撤回已生成的本次派发快照，多次重复注册需要相应移除。
     */
    @PublicApi
    fun removeOnAnimStateChangeListener(listener: OnAnimStateChangeListener?) {
        checkMainThread()
        if (listener != null) animStateChangeListeners.remove(listener)
    }

    /**
     * 在主线程清空后续状态通知的注册列表，不调用监听器或改写动画状态。
     * 当前正在遍历的快照仍可继续交付，供派生控制器销毁时使用。
     */
    protected fun clearOnAnimStateChangeListeners() {
        checkMainThread()
        animStateChangeListeners.clear()
    }

    /**
     * 在主线程按注册快照同步广播旧状态、新状态和可空任务信息，不在基类中存储新状态。
     * 监听可重入增删而不破坏遍历；异常直接传播并中断剩余通知，不逐项吞掉。
     */
    @PublicApi
    open fun onAnimStateChanged(oldState: AnimationState, newState: AnimationState, runningTask: Any?) {
        checkMainThread()

        for (l in animStateChangeListeners.toList()) {
            l.onAnimStateChanged(oldState, newState, runningTask)
        }
    }

    /**
     * 基类恒返回NONE表示不维护动画状态，显式广播状态不会改写该值。
     * 派生控制器可覆盖为真实状态，不应据基类常量推断外部动画已经完成。
     */
    @PublicApi
    open val animState: AnimationState get() = AnimationState.NONE
    /**
     * 基类恒返回false，不提供所有最近任务动画已结束的证明。
     * 即使基类没有动画列表也保持此默认值，宿主需自行确认真实完成。
     */
    @PublicApi
    open val allRecentsAnimationEnd: Boolean get() = false
    /**
     * 基类恒返回false，表示没有在本实现登记最近任务动画。
     * 不查询系统最近任务控制器或其他组件运行状态。
     */
    @PublicApi
    open val hasRecentsAnim: Boolean get() = false
    /**
     * 基类恒返回false，不对打开或反转打开状态进行分类。
     * 这是本实现的默认查询，不代表系统没有正在打开的窗口。
     */
    @PublicApi
    open val isOpeningAnim: Boolean get() = false
    /**
     * 基类恒返回false，不记录重叠打开场景。
     * 读取无副作用，不会根据当前平台Animator自动判断。
     */
    @PublicApi
    open val isMultiOpen: Boolean get() = false
    /**
     * 基类恒返回false，不记录重叠关闭场景。
     * 真实并行转场关系需要具体控制器维护。
     */
    @PublicApi
    open val isMultiClose: Boolean get() = false
    /**
     * 基类恒返回false，不提供关闭状态的分类结果。
     * 该查询不等待动画，也不是最后一帧提交屏障。
     */
    @PublicApi
    open val isClosingAnimAndAnimClosed: Boolean get() = false
    /**
     * 基类恒返回false，不跟踪应用窗口转场运行状态。
     * 不读取平台帧循环或系统窗口服务。
     */
    @PublicApi
    open val isAppWindowAnimRunning: Boolean get() = false
    /**
     * 基类恒返回false，因为没有挂起启动动作或转场间隔标记。
     * 读取不消费任何任务，不代替宿主的启动时序决策。
     */
    @PublicApi
    open val isStartActivityBetweenTransitionEndAndFinish: Boolean get() = false
    /**
     * 基类恒返回0f，不维护打开动画进度或插值状态。
     * 该值只是默认值，不表示外部动画刚开始。
     */
    @PublicApi
    open val openingProgress: Float get() = 0f
    /**
     * 基类恒返回null，不持有点击视图；setClickAppView不会改变此结果。
     * 读取不创建View或查找界面节点。
     */
    @PublicApi
    open val clickAppView: Any? get() = null
    /**
     * 基类恒返回null，updateRunningTask也是空实现。
     * 不保留任务对象或向系统查询当前任务。
     */
    @PublicApi
    open val runningTask: Any? get() = null
    /**
     * 基类恒返回null，未记录手势上滑来源包名。
     * 不通过当前任务自动猜测调用应用。
     */
    @PublicApi
    open val swipingUpActivityPkg: String? get() = null
    /**
     * 基类恒返回-1作为未提供小部件标识的默认值。
     * 对应setter空实现，不触发资源查找或标识分配。
     */
    @PublicApi
    open val openingRemoteAnimWidgetId: Int get() = -1
    /**
     * 基类恒返回false，不提出横屏应用启动期间禁用上滑的策略。
     * 不直接修改输入系统或手势识别器。
     */
    @PublicApi
    open val forbidSwipeUpWhileStartingLandApp: Boolean get() = false
    /**
     * 基类恒返回false，对应写入入口不会存储值。
     * 不判断远程目标是否属于小部件。
     */
    @PublicApi
    open val isCloseWidgetRemoteAnim: Boolean get() = false
    /**
     * 基类恒返回false，不记录一次手势是否处于处理阶段。
     * 对应场景入口不影响此值，不表示宿主输入链路为空闲。
     */
    @PublicApi
    open val onceGestureProcessing: Boolean get() = false

    /**
     * 基类忽略矩形句柄、可空最近任务控制器及目标数组，不登记或启动动画。
     * 功能关闭时保留调用兼容性，实际最近任务管理由派生实现或宿主承担。
     */
    @PublicApi
    open fun addRecentsAnim(anim: CustomRectFSpringAnim, recentsController: Any?, targets: Array<out Any?>?) {}
    /**
     * 基类不处理应用打开动画的开始/结束，忽略方向标记、可空工厂及远程目标。
     * 不修改状态、不触发完成动作，也不消费平台远程动画资源。
     */
    @PublicApi
    open fun appLaunchAnimStartOrEnd(isEnd: Boolean, factory: RemoteAnimationFactory?,
                                     targets: Array<LauncherAnimationRunner.RemoteAnimationTarget>?) {}
    /**
     * 基类恒返回true，表示自身不阻止宿主完成最近任务动画。
     * 不检查句柄状态或animationId，不等于动画已经收敛或系统允许结束。
     */
    @PublicApi
    open fun canFinishRecentsAnim(anim: CustomRectFSpringAnim, animationId: Int): Boolean = true
    /**
     * 基类没有维护最近任务动画列表，因此不执行清理并恒返回true。
     * 返回值仅表示本控制器不阻止收尾，不会取消传入其他组件的动画或触发完成回调。
     */
    @PublicApi
    open fun cleanUpRecentsAnim(): Boolean = true
    /**
     * 基类恒返回false，表示不接管延后启动，不调用可空判定call也不执行action。
     * 宿主看到false应自行立即启动；本层不读取context/intent，也不登记超时兜底。
     */
    @PublicApi
    open fun delayStartActivityIfNeed(context: Any?, intent: Intent?,
                                      call: (() -> Boolean)?, action: (() -> Unit)?): Boolean = false
    /**
     * 基类忽略指定任务事件，不维护类型对应的超时监听或事件总线。
     * 宿主仍须处理系统事件，不应认为调用此入口就结束了实际转场。
     */
    @PublicApi
    open fun dispatchTaskStateChange(type: TaskStateChangeTimeOutListener.Type) {}
    /**
     * 基类空实现，不修改手势策略、不恢复输入也不调用外部系统服务。
     * 功能关闭时可安全保留调用，但实际滑动能力由宿主控制。
     */
    @PublicApi
    open fun enableSwipeUp() {}
    /**
     * 基类恒返回false，不向宿主提出禁止触摸的策略要求。
     * 仅为策略查询，不拦截事件，也不保证外部窗口或动画允许触摸。
     */
    @PublicApi
    open fun forbidTouch(): Boolean = false
    /**
     * 基类空实现，没有持有可强停的最近任务动画或系统控制器。
     * 不派发取消/结束，调用方如需实际停帧应操作真实动画所有者。
     */
    @PublicApi
    open fun forceStopAllRecentAnim() {}
    /**
     * 基类空实现，不排队或执行任务移除，也不访问系统任务列表。
     * 实际启动时的任务清理需由宿主或具备该能力的派生实现负责。
     */
    @PublicApi
    open fun removeTasksOnRealStart() {}
    /**
     * 基类不存储转场状态，reset无操作，不清除已注册的状态监听或发出NONE通知。
     * 派生控制器可实现自己的清理，调用方不要把基类reset当作动画资源释放。
     */
    @PublicApi
    open fun reset() {}
    /**
     * 基类忽略反转请求，不修改矩形句柄类型、目标或速度。
     * 实际几何反转须由支持反转的驱动完成，本入口不会静默启动替代动画。
     */
    @PublicApi
    open fun revertRecentsAnimation(anim: CustomRectFSpringAnim) {}
    /**
     * 基类忽略续行运行标记，不注册/取消超时，也不执行挂起启动。
     * 需要该状态门控时应使用具体控制器实现。
     */
    @PublicApi
    open fun setAppToOverviewContinuationState(v: Boolean) {}
    /**
     * 基类忽略应用退出的转场结束与完成之间标记，不存储状态。
     * 该调用不生成任务完成事件，也不改变后续启动决策。
     */
    @PublicApi
    open fun setBetweenAppExitTransitionEndAndFinish(v: Boolean) {}
    /**
     * 基类忽略普通转场结束与完成之间标记，不更改输入策略或启动行为。
     * 实际场景状态由派生实现维护，基类没有隐式计时或回调。
     */
    @PublicApi
    open fun setBetweenTransitionEndAndFinish(v: Boolean) {}
    /**
     * 基类不保存可空点击视图引用，clickAppView读取仍为null。
     * 不修改View属性或持有界面对象，宿主负责点击目标生命周期。
     */
    @PublicApi
    open fun setClickAppView(view: Any?) {}
    /**
     * 基类丢弃关闭小部件远程动画标记，查询仍返回false。
     * 不启动、停止或选择任何远程动画。
     */
    @PublicApi
    open fun setCloseWidgetRemoteAnim(v: Boolean) {}
    /**
     * 基类忽略可空退出上下文/场景，不解析类型或安排退出兜底。
     * 不把null推断成退出事件，实际场景处理由派生实现提供。
     */
    @PublicApi
    open fun setOnAppExit(context: Any?) {}
    /**
     * 基类不记录可空手势场景，onceGestureProcessing保持false。
     * 不影响手势输入或任务状态，也不会按场景自动注册延迟动作。
     */
    @PublicApi
    open fun setOnceGestureProcessing(state: Any?) {}
    /**
     * 基类忽略小部件标识，openingRemoteAnimWidgetId仍返回-1。
     * 不验证标识或持有小部件资源，真实映射由宿主维护。
     */
    @PublicApi
    open fun setOpeningRemoteAnimWidgetId(id: Int) {}
    /**
     * 基类忽略可空远程目标数组，不复制、保存或释放数组中的平台对象。
     * 调用方仍拥有目标生命周期，本入口不会应用任何窗口事务。
     */
    @PublicApi
    open fun updateRunningRemoteTarget(targets: Array<out Any?>?) {}
    /**
     * 基类不保存可空任务信息，runningTask查询始终为null。
     * 不会从任务对象推导动画状态，也不主动通知状态监听。
     */
    @PublicApi
    open fun updateRunningTask(taskInfo: Any?) {}

    /**
     * 基类不存储最近任务完成动作：读取恒null，写入直接丢弃且不会执行。
     * 宿主不能通过此空实现获得收尾通知，需使用具体实现或独立持有回调。
     */
    @PublicApi
    open var recentsAnimFinishCallback: (() -> Unit)?
        get() = null
        set(value) {}

    /**
     * 基类不保存应用打开完成动作，读取恒null，写入无副作用。
     * 不主动调用或释放回调捕获的业务资源，调用方负责自己的生命周期。
     */
    @PublicApi
    open var appLaunchAnimFinishCallback: (() -> Unit)?
        get() = null
        set(value) {}
}

/**
 * 获取主Looper后以实例身份校验当前Looper，异线程访问抛出IllegalStateException。
 * 无主Looper时仅作JVM降级返回；顶层函数不捕获控制器，供超时句柄使用以避免保留控制器引用。
 */
internal fun checkControllerMainThread() {
    val main = Looper.getMainLooper() ?: return
    check(Looper.myLooper() === main) { "AnimationController state must be accessed on the main thread" }
}
