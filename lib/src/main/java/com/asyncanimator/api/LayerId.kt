package com.asyncanimator.api

/**
 * 库内技术分层标识，自底向上严格编号，只允许依赖同层或更底层。
 * 分层由编译期校验强制：漏标 @Layer、或 import 指向更高层都会在编译期失败。
 * 新增层必须追加在末尾，禁止重排已有编号，以保持已标注声明的语义稳定。
 */
@PublicApi
enum class LayerId {

    /**
     * L0 基础层：自有帧调度、日志与 Trace、跨 Looper 执行器、协程主线程调度器及启动器动画桥接桩。
     * 不依赖库内任何其他层，thread 可引用 core，launcher3 桩仅被上层引用。
     */
    @PublicApi
    BASIC,

    /**
     * L1 播放原语层：监听器适配、待执行动画队列与统一进度播放控制。
     * 仅可依赖基础层；ActualEndAnimListener 实际结束入口位于本层以切断 anim 反向依赖。
     */
    @PublicApi
    PLAYBACK,

    /**
     * L2 动画驱动层：异步属性动画、弹簧驱动、六轴 Rect driver 与监听派发容器。
     * 仅可依赖播放原语层与基础层。
     */
    @PublicApi
    ANIM,

    /**
     * L3 序列协调层：启动序号、时间窗口与延迟去重协调。
     * 仅可向下依赖；能力查询由工厂层在接线时注入，不反向引用 manager。
     */
    @PublicApi
    SEQ,

    /**
     * L4 转场控制层：主线程状态机、超时监听与 Recents/打开动画编排。
     * 仅可向下依赖；完成策略与清理回调由工厂层注入，不反向引用 manager。
     */
    @PublicApi
    CONTROL,

    /**
     * L5 工厂开关层：能力声明、实现切换与控制器/序列 helper 的接线注入。
     * 位于依赖图顶端，只被库外使用方与 launcher 集成引用。
     */
    @PublicApi
    MANAGER
}
