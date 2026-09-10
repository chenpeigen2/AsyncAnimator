package com.asyncanimator.thread

import android.os.HandlerThread
import android.os.Process
import com.asyncanimator.core.AnimationHandler
import com.asyncanimator.core.ChoreographerTickScheduler

/**
 * 独立动画线程（"launcher.anim"），由 [Executors.ANIM_CONTROL_EXECUTOR] 首次访问启动。
 *
 * OPPO 参考链：
 * - `OplusExecutors.java:95` 创建同名、优先级 -19 的线程，包装 OplusLooperExecutor；
 * - `Executors.java:94-99` start 后还报告私有 UAF 线程事件；
 * - `OplusLooperExecutor.java:83-87` 排入初始化任务，随后
 *   `OplusExecutors.java:169-171` 为平台隐藏 AnimationHandler 设置 SF-VSYNC provider
 *   并调用 LauncherBooster 注册 UX 线程。
 *
 * 本库只在自己的 ThreadLocal [AnimationHandler] 中安装 [ChoreographerTickScheduler]。
 * 公开 Choreographer 的 app VSYNC 不等价于 SF-VSYNC；这里不会替换平台 ValueAnimator
 * 或 AndroidX 动画的帧源，也不注册 UX/UAF、绑核或模拟私有系统服务。
 * -19 数值等于 `Process.THREAD_PRIORITY_URGENT_AUDIO`；保留字面量是为对照原厂，
 * 不是因为 SDK 缺少同值常量，也不是设备上的实际调度/性能保证。
 *
 * HandlerThread 可在 [onLooperPrepared] 完成前发布 Looper；此时调用方可以入队，
 * 但普通 Handler 消息要等初始化返回、Looper.loop 开始后才执行。
 * 因此应通过执行器提交 owner-thread 工作，不把“取得执行器”视为初始化完成通知。
 * 无可变的全局首跑 hook；需要的业务初始化可以作为普通任务提交。
 *
 * AsyncValueAnimator 与 Rect 生命周期桥各自处理 owner 转发；这里不保证任意动画参数
 * 线程安全。View 更新必须回主线程，Rect 桥也不等于 OEM 六轴物理/SurfaceControl 引擎。
 */
class AnimationControlThread private constructor() : HandlerThread(THREAD_NAME, PRIORITY) {

    init {
        start()
    }

    /**
     * 在本线程、Looper.prepare 之后且 Looper.loop 之前安装自有调度器。
     * 安装失败明确抛出；不静默退回其他帧源。Choreographer 在首次请求帧时才取得，
     * 不是在构造 ChoreographerTickScheduler 时立即创建。
     */
    override fun onLooperPrepared() {
        AnimationHandler.installThreadScheduler(ChoreographerTickScheduler())
        // HandlerThread 已在调用本方法前设置构造优先级；保留一次带日志的尽力重申。
        // 这不是 UX 注册，也不能捕获 HandlerThread.run 内更早的设置失败，
        // 更不阻止调用方通过 LooperExecutor.setThreadPriority 在之后修改优先级。
        runCatching { Process.setThreadPriority(Process.myTid(), PRIORITY) }
            .onFailure { android.util.Log.w(THREAD_NAME, "setThreadPriority($PRIORITY) failed: ${it.message}") }
    }

    companion object {
        /** 原厂线程名，便于在 trace / logcat 上对照；同名不代表已经注册 UAF。 */
        const val THREAD_NAME = "launcher.anim"

        /** 原厂字面值 -19（URGENT_AUDIO），不是 URGENT_DISPLAY 的 -8。 */
        private const val PRIORITY = -19

        /**
         * 首次访问才创建并 start，之后随进程常驻。仅加载类/读取常量不会启动线程。
         * 构造包含 start 副作用，不可改为 PUBLICATION（可能创建多实例）或 NONE。
         */
        internal val instance: AnimationControlThread by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            AnimationControlThread()
        }
    }
}
