package com.asyncanimator.anim

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.RectF
import com.asyncanimator.thread.LooperExecutor

/**
 * 矩形过渡的生命周期句柄，提供主线程通知、固定驱动线程和逻辑/实际结束分离。
 * 构造时保存动画类型、可空驱动及测试/宿主可选执行器，生命周期协调器延迟创建。
 * 无驱动时仅用于控制器登记，播放必须提供真实驱动；六轴几何可使用RectSpringDriver，本类不负责远程窗口或SurfaceControl事务。
 */
class CustomRectFSpringAnim internal constructor(
    internal var animType: AnimType,
    internal val driver: Driver?,
    mainExecutor: LooperExecutor?,
    animExecutor: LooperExecutor?
) {
    /**
     * 使用默认主/动画执行器创建句柄，driver为空时允许登记但start会失败。
     * 实际帧源及属性写入属于驱动；构造本身不启动动画，也不复制或释放传入驱动。
     */
    constructor(animType: AnimType, driver: Driver? = null) : this(animType, driver, null, null)

    private val lifecycle by lazy { RectAnimationLifecycle(this, driver, mainExecutor, animExecutor) }

    /**
     * 通过协调器读取或配置动画标识，初始为-1；每轮启动将标识复制到不可变事件。
     * 写入要求主线程、未释放且没有等待实际结束的运行；读取不推进动画。
     */
    var animationId: Int
        get() = lifecycle.animationId
        set(value) { lifecycle.animationId = value }
    /**
     * 是否仍有未通过实际结束屏障的运行；逻辑结束后也可能返回true。
     * 只读委托协调器，不意味着驱动正在产帧，释放时会先清除此运行状态。
     */
    val isRunning: Boolean get() = lifecycle.isRunning
    /**
     * 最近一轮是否已收到反转到打开的主线程确认；下一轮启动重置为false。
     * 只读，不等于反转动画完成，也不根据animType的初始值自动推导。
     */
    val isReverseToOpen: Boolean get() = lifecycle.isReverseToOpen
    /**
     * 本轮是否由仅通知接口首次标记逻辑结束，下一轮启动清零。
     * 只读标记不停止帧循环，普通取消或跳到终点不会自动将其置true。
     */
    val isJustNotifyEndCallback: Boolean get() = lifecycle.isJustNotifyEndCallback

    /**
     * 配置下一轮是否使用动画执行器，必须在主线程、未释放且没有活动运行时调用。
     * 驱动未声明支持动画线程却要求启用时抛出参数异常；不会切换运行中驱动的所属线程。
     */
    fun setAsyncStart(enabled: Boolean) = lifecycle.setAsyncStart(enabled)
    /**
     * 在主线程添加去重的矩形生命周期监听器，已释放时拒绝添加。
     * 监听接收包含动画标识和运行代次的非空事件；不会为新监听补发已经发生的启动或结束。
     */
    fun addListener(listener: Listener) = lifecycle.addListener(listener)
    /**
     * 在主线程移除生命周期监听器，不存在时无操作。
     * 可以在回调内移除以阻止后续事件，不影响驱动推进，也不清空start附带的结束钩子。
     */
    fun removeListener(listener: Listener) = lifecycle.removeListener(listener)
    /**
     * 请求启动并在主线程派发启动事件，再由选定执行器驱动几何；可空钩子仅在实际结束后执行。
     * 无驱动或已释放时失败，已有活动运行时忽略；只有实际结束清理完成后才允许下一轮启动。
     */
    @JvmOverloads fun start(onActualEnd: (() -> Unit)? = null) = lifecycle.start(onActualEnd)
    /**
     * 请求取消，尚未逻辑结束时主线程先通知取消和逻辑结束，再在本轮固定线程停止驱动。
     * 无活动运行或已有停止请求时忽略；实际结束由驱动另行报告，不以逻辑回调替代物理完成。
     */
    fun cancel() = lifecycle.cancel()
    /**
     * 请求驱动完成到终点，并先在主线程发送逻辑结束而非取消事件。
     * 停止请求仅第一次有效，物理完成仍需等待驱动回报；调用返回不保证最后一帧已经提交。
     */
    fun skipToEnd() = lifecycle.skipToEnd()
    /**
     * 只在主线程发送当前运行的逻辑结束，驱动继续推进且isRunning仍可能为true。
     * 重复调用或无活动运行时忽略；适用于业务先结束、几何稍后收敛的双阶段生命周期。
     */
    fun justNotifyEndCallback() = lifecycle.justNotifyEndCallback()
    /**
     * 复制目标矩形后请求可反转驱动在其所有者线程重设目标及圆角。
     * 有效确认回到主线程后更新类型和反转标记，再调用afterReverse；无运行/已停止时忽略，不支持时抛出异常。
     * 圆角与几何合法性由具体驱动校验，目标副本避免调用方随后修改RectF影响异步任务。
     */
    fun reverseToOpen(target: RectF, endRadius: Float, afterReverse: () -> Unit) =
        lifecycle.reverseToOpen(RectF(target), endRadius, afterReverse)
    /**
     * 幂等释放句柄、监听器和结束钩子，使旧运行事件失效；活动驱动在原所有者上清理。
     * 不主动通知取消/结束，释放后不能重新启动；跨线程调用返回时清理可能仍在排队。
     */
    fun dispose() = lifecycle.dispose()
    /**
     * 仅在主线程清除当前start附带的实际结束钩子，无运行时无操作。
     * 生命周期监听和驱动物理结束上报仍保留，不会停止动画。
     */
    internal fun clearEndCallback() = lifecycle.clearEndCallback()

    /**
     * 不可变生命周期载荷：animationId为启动时动画标识，runId为句柄内运行代次，cancelled为通知时取消状态。
     * 构造不校验数值，数据类copy生成独立值对象；载荷不携带伪造的平台Animator，也不随后继运行改变。
     */
    data class Event(val animationId: Int, val runId: Long, val cancelled: Boolean)
    /**
     * 矩形动画主线程观察接口，区分启动、取消、逻辑结束和实际结束，所有默认方法均无操作。
     * 协调器按快照派发，回调允许重入；普通Exception会被记录后继续通知，Error等不在保护范围。
     */
    interface Listener {
        /**
         * 在主线程接收本轮启动通知，此时驱动可能尚未真正开始帧循环。
         * 默认无操作；event标识固定运行，回调可重入取消或释放，由协调器在启动交接处处理。
         */
        fun onStart(animation: CustomRectFSpringAnim, event: Event) {}
        /**
         * 在主线程接收本轮取消通知，通常随后仍有逻辑结束和实际结束。
         * 默认无操作；不能据此认定帧订阅已经移除，event.cancelled反映取消状态。
         */
        fun onCancel(animation: CustomRectFSpringAnim, event: Event) {}
        /**
         * 在主线程接收逻辑结束，可能发生在驱动物理停止之前。
         * 默认无操作；需要安全释放依赖最后一帧的资源时应等待onActualEnd，而不是仅依赖此通知。
         */
        fun onEnd(animation: CustomRectFSpringAnim, event: Event) {}
        /**
         * 在主线程接收驱动完成并清除结束回调后的实际结束通知。
         * 默认无操作；事件属于固定运行，回调可以开始下一轮，不能把旧事件标识解释为新运行。
         */
        fun onActualEnd(animation: CustomRectFSpringAnim, event: Event) {}
    }

    /**
     * 将传入平台Animator包装成主线程驱动，不接管其几何计算和已有监听器。
     * 默认不支持动画线程与反转目标能力；构造不启动Animator，复用时仍需遵守其自身约束。
     */
    constructor(animType: AnimType, animator: Animator) : this(animType, AnimatorDriver(animator))

    /**
     * 动画场景分类，供参数选择、控制器反转决策和诊断使用。
     * 包含桌面打开、远程关闭、手势拖拽、滑回桌面及反转打开；类型本身不提供窗口/表面操作。
     */
    enum class AnimType {
        OPEN_FROM_HOME,
        REMOTE_CLOSE_TO_HOME,
        REMOTE_CLOSE_TO_HOME_ASSISTANT,
        GESTURE_TO_DRAG,
        SWIPE_TO_HOME,
        SWIPE_TO_HOME_ASSISTANT,
        REVERSE_TO_OPEN
    }

    /**
     * 几何引擎与生命周期之间的接口，所有运行操作固定到start选定的所有者线程。
     * 实现负责真实帧推进、属性写入和物理结束上报；上层负责逻辑事件及主线程监听派发。
     */
    interface Driver {

        /**
         * 驱动是否支持在动画执行器进行帧调度与属性写入，默认false。
         * 只有帧源和消费者均满足该线程约束时才应返回true；声明能力本身不会切换线程。
         */
        val supportsAnimationThread: Boolean get() = false
        /**
         * 在调用线程开始驱动并替换此前结束钩子，驱动应在帧循环真正停止后调用onActualEnd。
         * 同一轮其他操作会固定在此所有者执行；实现不得只因发出逻辑取消就提前报告物理完成。
         */
        fun start(onActualEnd: () -> Unit)
        /**
         * 在本轮驱动所有者上请求取消推进，是否同步停帧由实现决定。
         * 实现仍须在物理停止后报告实际结束，以便上层释放运行屏障；不负责上层主线程逻辑通知。
         */
        fun cancel()
        /**
         * 在驱动所有者上请求完成到目标状态，必要时提交最终帧。
         * 完成后须通过start注册的钩子报告物理结束，不应伪装为取消或仅发出逻辑结束。
         */
        fun skipToEnd()
        /**
         * 在驱动所有者上解除此前start注册的结束钩子，供完成清理或静默释放使用。
         * 实现须避免旧钩子泄漏到后继运行；此入口本身不代表取消或停止帧源。
         */
        fun clearEndCallback()
    }

    /**
     * 可选的最终释放能力，适用于持有帧订阅或底层监听注册的驱动。
     * 句柄有活动运行时优先调用此接口，避免仅cancel还必须等待后续帧才能清理。
     */
    interface DisposableDriver : Driver {
        /**
         * 在驱动所有者上执行最终释放，解除帧订阅、回调和实现持有的运行资源。
         * 此可选能力用于不等待未来帧的清理；句柄释放时不应再依赖业务完成通知。
         */
        fun dispose()
    }

    /**
     * 可选的几何重定向能力，支持运行中反转到打开目标而不重新创建句柄。
     * 未实现此接口的驱动在反转请求时明确失败，而不是静默忽略目标变化。
     */
    interface ReversibleDriver : Driver {
        /**
         * 在驱动所有者上把当前几何运行重定向到打开目标和结束圆角。
         * 实现负责输入校验及位置/速度连续性；返回表示重定向已应用，并不表示动画已到达新目标。
         */
        fun reverseToOpen(target: RectF, endRadius: Float)
    }

    /**
     * 平台Animator的最小生命周期适配器，构造时保留传入实例并独立维护一个结束监听。
     * 使用Driver默认的主线程能力，不提供反转；外部仍负责Animator自身属性目标及其他监听的生命周期。
     */
    private class AnimatorDriver(private val animator: Animator) : Driver {
        private var listener: AnimatorListenerAdapter? = null

        /**
         * 先解绑旧结束监听，再给平台Animator安装本次专用监听并调用start。
         * 默认只支持主线程驱动，几何和数值更新由传入Animator负责；启动异常原样传播。
         */
        override fun start(onActualEnd: () -> Unit) {
            clearEndCallback()
            listener = object : AnimatorListenerAdapter() {
                /**
                 * 平台Animator结束时先移除此适配器的监听，再调用本轮实际结束钩子。
                 * 先清理允许钩子重入启动后继运行；不额外区分取消，取消状态由外层生命周期维护。
                 */
                override fun onAnimationEnd(animation: Animator) {
                    clearEndCallback()
                    onActualEnd()
                }
            }.also(animator::addListener)
            animator.start()
        }

        /**
         * 在所属线程直接调用平台Animator.cancel，使用平台的取消/结束行为驱动后续上报。
         * 不在适配层切线程、吞异常或另行伪造结束事件。
         */
        override fun cancel() = animator.cancel()
        /**
         * 在所属线程直接调用平台Animator.end，让平台提交终值并触发结束监听。
         * 不额外计算几何或手动调用实际结束钩子，避免重复结束。
         */
        override fun skipToEnd() = animator.end()
        /**
         * 只解绑本适配器持有的平台结束监听并清空引用，重复调用安全。
         * 不清除外部注册的监听，也不停止平台Animator。
         */
        override fun clearEndCallback() {
            listener?.let(animator::removeListener)
            listener = null
        }
    }
}
