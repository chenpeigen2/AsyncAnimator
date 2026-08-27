package com.asyncanimator.launcher.async;

/**
 * CustomRectFSpringAnim — Recents 转场的弹簧动画占位。
 *
 * <p>对应原 OPPO 代码 {@code com.android.quickstep.util.animation.CustomRectFSpringAnim}（简化）。
 *
 * <p>在本 demo 模块里用作"动画句柄"——AnimationController 持有 List<CustomRectFSpringAnim>。
 * 实际动画逻辑由 SpringAnimation 实现。
 */
public class CustomRectFSpringAnim {

    public enum AnimType {
        SWIPE_TO_HOME,
        RECENTS_TRANSITION,
        APP_LAUNCH
    }

    private final AnimType animType;

    public CustomRectFSpringAnim(AnimType type) {
        this.animType = type;
    }

    public AnimType getAnimType() {
        return animType;
    }
}