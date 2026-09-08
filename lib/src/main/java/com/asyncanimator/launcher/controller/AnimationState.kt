package com.asyncanimator.launcher.controller

/**
 * AnimationState — 转场状态枚举。
 *
 * 对应 `docs/review/03-controller-manager-seq.md`。每个状态携带两个 boolean：
 *
 *  - [withTaskbarAlignment] — 该状态下 taskbar 是否参与对齐
 *  - [taskbarAlignmentToLauncher] — 对齐方向
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
