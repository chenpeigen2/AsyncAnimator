package com.asyncanimator.core

/**
 * 持续订阅式帧调度契约，隔离动画内核与具体时钟实现。
 * 公开 Choreographer 实现可用于运行，受控实现可用于 JVM 测试；不提供跨线程 View 写入能力。
 */
internal interface TickScheduler {

    /**
     * 注册持续接收后续帧的回调，不是单次 Choreographer 订阅。
     * null 应作为无操作；是否自动启动及重复注册策略由具体帧源实现规定。
     */
    fun postFrameCallback(callback: FrameCallback?)

    /**
     * 取消指定持续订阅，使后续取得的派发快照不再包含相应条目。
     * 不要求撤回已经复制或正在执行的当前帧回调；重复注册的移除策略由实现定义。
     */
    fun removeFrameCallback(callback: FrameCallback?)

    /**
     * 最近一次已发布帧的纳秒时间戳；时间原点由具体帧源确定。
     */
    val frameTimeNanos: Long

    /**
     * 启动或恢复该帧源的调度循环。
     * 具体实现负责保证重复启动不会产生多条帧循环；此操作不承诺同步产生第一帧。
     */
    fun start()

    /**
     * 暂停后续帧调度而保留现有订阅，以便再次 start 恢复。
     * 不代表线程退出，也不保证撤回已进入执行阶段的当前帧派发。
     */
    fun stop()

    /**
     * 帧源累计发布的帧数；实现从零开始，每次实际派发递增。
     */
    val frameCount: Long

    /**
     * 接收纳秒级帧时间的订阅接口，由具体帧源决定派发线程和异常隔离策略。
     */
    fun interface FrameCallback {

        /**
         * 处理帧源所属线程派发的一帧。
         * @param frameTimeNanos 帧源提供的纳秒时间戳；计算间隔应使用相邻时间戳之差而非固定帧率假设。
         */
        fun doFrame(frameTimeNanos: Long)
    }
}
