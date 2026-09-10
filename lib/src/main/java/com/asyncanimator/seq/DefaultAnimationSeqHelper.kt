package com.asyncanimator.seq

import com.asyncanimator.api.PublicApi

/**
 * 序列协调的功能关闭实现，查询默认放行，序号写入和状态更新不生效。
 * 延迟完成入口不是空操作：它会同步执行调用方动作，但不会创建消息队列任务。
 */
@PublicApi
open class DefaultAnimationSeqHelper {

    /**
     * 降级实现不分配序号，也不修改传入 Bundle 的任何字段。
     * 允许 null；即使容器已有序号也原样保留，由调用方决定如何处理既有数据。
     */
    @PublicApi
    open fun addSeqId(bundle: android.os.Bundle?) {}
    /**
     * 降级模式始终允许完成 Recents，不查询时间窗口或全局配置。
     */
    @PublicApi
    open val canFinishRecent: Boolean get() = true
    /**
     * 降级模式始终允许拦截手势，不读取最近启动时间戳。
     */
    @PublicApi
    open val canInterceptGesture: Boolean get() = true
    /**
     * 立即在调用线程执行非空 action，并返回 false 表示没有延后。
     * 不创建 Handler 或缓存任务；支持回调重入，业务异常直接传播，null 不产生副作用。
     */
    @PublicApi
    open fun delayFinishRecents(action: (() -> Unit)?): Boolean {
        action?.invoke()
        return false
    }
    /**
     * 降级实现没有排队任务，因此无需清理，重复调用不产生副作用。
     * 不会执行任何完成回调，也不影响其他 helper 实例的队列。
     */
    @PublicApi
    open fun clearFinishRecentsRunnable() {}
    /**
     * 降级实现不维护手势拦截状态，因此调用后仍保持允许拦截。
     * 不会清除进程共享时间戳，也不改变实际实现持有的状态。
     */
    @PublicApi
    open fun resetInterceptState() {}
    /**
     * 忽略传入的 Recents 控制器，不建立控制器与序号的配对。
     * 允许 null 或重复对象；此降级入口不会消耗序号。
     */
    @PublicApi
    open fun updateNextFinishSeqIdIfNeed(recentsController: Any?) {}
    /**
     * 始终返回零，表示降级实现没有为控制器登记完成序号。
     * 不区分对象身份、相等对象或 null，也不产生惰性注册副作用。
     */
    @PublicApi
    open fun getNextFinishSeqId(recentsController: Any?): Long = 0L
}
