package com.android.launcher3

import com.asyncanimator.api.PublicApi

/**
 * 本地转场目标描述符的类型容器，没有启动动画或接收远程消息的方法。
 * 保留现有包名和嵌套类型供控制器签名使用，不提供 Binder 通道、系统窗口事务或平台目标数组适配。
 */
@PublicApi
abstract class LauncherAnimationRunner {

    /**
     * 本地任务目标的可变描述符，不实现 Parcelable，也不是平台的远程动画目标类型。
     * @property taskId 调用方提供的任务标识，默认值为零；本容器不校验标识是否对应真实任务。
     * @property leash 调用方携带的不透明对象，默认为 null；不能仅凭该字段类型推断可执行窗口事务。
     */
    @PublicApi
    class RemoteAnimationTarget @PublicApi constructor(
        /** 调用方提供的任务标识，默认值为零；本容器不校验标识是否对应真实任务。 */
        @property:PublicApi
        var taskId: Int = 0,
        /** 调用方携带的不透明对象，默认为 null；不能仅凭该字段类型推断可执行窗口事务。 */
        @property:PublicApi
        var leash: Any? = null
    )
}
