package com.asyncanimator.control

/**
 * 控制器维护的十二种业务转场状态，不等同于底层帧循环或触摸门控的运行状态。
 * 枚举名称和顺序保持稳定；构造参数描述任务栏的参与方式，而非自动执行任务栏动画。
 * @property withTaskbarAlignment 当前状态是否让任务栏参与位置对齐。
 * @property taskbarAlignmentToLauncher 参与对齐时是否朝向桌面端；未参与时应忽略此方向标记。
 */
enum class AnimationState(
    val withTaskbarAlignment: Boolean,
    val taskbarAlignmentToLauncher: Boolean
) {
    NONE(false, false),
    OPEN(false, false),
    REVERSE_OPEN(true, false),
    CLOSE(true, true),
    MULTI_OPEN(true, false),
    MULTI_REVERSE_OPEN(true, false),
    MULTI_CLOSE(true, true),
    WAITING(false, false),
    MULTI_WAITING(false, false),
    UNKNOWN(false, false),
    SWIPE_UP_TO_CAPSULE(true, true),
    SWIPE_UP_TO_SPLIT_OR_FLOATING(true, true)
}
