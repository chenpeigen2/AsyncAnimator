package com.asyncanimator.control

import com.asyncanimator.api.PublicApi

/**
 * 控制器维护的十二种业务转场状态，不等同于底层帧循环或触摸门控的运行状态。
 * 枚举名称和顺序保持稳定；构造参数描述任务栏的参与方式，而非自动执行任务栏动画。
 * @property withTaskbarAlignment 当前状态是否让任务栏参与位置对齐。
 * @property taskbarAlignmentToLauncher 参与对齐时是否朝向桌面端；未参与时应忽略此方向标记。
 */
@PublicApi
enum class AnimationState(
    /** 当前状态是否让任务栏参与位置对齐。 */
    @property:PublicApi
    val withTaskbarAlignment: Boolean,
    /** 参与对齐时是否朝向桌面端；未参与时应忽略此方向标记。 */
    @property:PublicApi
    val taskbarAlignmentToLauncher: Boolean
) {
    /** 无业务转场状态；不参与任务栏对齐，不等价于底层帧循环已停止。 */
    @PublicApi
    NONE(false, false),
    /** 单应用打开状态；不参与任务栏对齐。 */
    @PublicApi
    OPEN(false, false),
    /** 反转打开状态；参与任务栏对齐，方向为非桌面端。 */
    @PublicApi
    REVERSE_OPEN(true, false),
    /** 单应用关闭状态；参与任务栏对齐，方向为桌面端。 */
    @PublicApi
    CLOSE(true, true),
    /** 多应用打开状态；参与任务栏对齐，方向为非桌面端。 */
    @PublicApi
    MULTI_OPEN(true, false),
    /** 多应用反转打开状态；参与任务栏对齐，方向为非桌面端。 */
    @PublicApi
    MULTI_REVERSE_OPEN(true, false),
    /** 多应用关闭状态；参与任务栏对齐，方向为桌面端。 */
    @PublicApi
    MULTI_CLOSE(true, true),
    /** 单应用转场等待状态；不参与任务栏对齐，不自动完成动画。 */
    @PublicApi
    WAITING(false, false),
    /** 多应用转场等待状态；不参与任务栏对齐，不自动完成动画。 */
    @PublicApi
    MULTI_WAITING(false, false),
    /** 未知业务转场状态；不参与任务栏对齐，不能据此推断物理动画运行情况。 */
    @PublicApi
    UNKNOWN(false, false),
    /** 上滑至胶囊的业务状态；参与朝桌面端的任务栏对齐。 */
    @PublicApi
    SWIPE_UP_TO_CAPSULE(true, true),
    /** 上滑至分屏或浮窗的业务状态；参与朝桌面端的任务栏对齐。 */
    @PublicApi
    SWIPE_UP_TO_SPLIT_OR_FLOATING(true, true)
}
