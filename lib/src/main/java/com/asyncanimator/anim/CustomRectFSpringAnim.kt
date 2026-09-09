package com.asyncanimator.anim

/**
 * CustomRectFSpringAnim — Recents 转场的弹簧动画占位。
 *
 * 对应原 OPPO 代码 `com.android.quickstep.util.animation.CustomRectFSpringAnim`（简化）。
 *
 * 在本 demo 模块里用作"动画句柄"——AnimationController 持有 List<CustomRectFSpringAnim>。
 * 实际动画逻辑由 SpringAnimation 实现。
 */
class CustomRectFSpringAnim(internal val animType: AnimType) {

    enum class AnimType {
        SWIPE_TO_HOME,
        RECENTS_TRANSITION,
        APP_LAUNCH
    }
}
