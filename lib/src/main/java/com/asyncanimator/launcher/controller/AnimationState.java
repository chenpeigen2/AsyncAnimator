package com.asyncanimator.launcher.controller;

/**
 * AnimationState — 转场状态枚举。
 *
 * <p>对应分析文档 §6.8.2。11 个状态，每个状态携带两个 boolean：
 * <ul>
 *   <li>withTaskbarAlignment — 该状态下 taskbar 是否参与对齐</li>
 *   <li>taskbarAlignmentToLauncher — 对齐方向</li>
 * </ul>
 */
public enum AnimationState {
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
    SWIPE_UP_TO_SPLIT_OR_FLOATING(true, true);

    public final boolean withTaskbarAlignment;
    public final boolean taskbarAlignmentToLauncher;

    AnimationState(boolean withTaskbarAlignment, boolean taskbarAlignmentToLauncher) {
        this.withTaskbarAlignment = withTaskbarAlignment;
        this.taskbarAlignmentToLauncher = taskbarAlignmentToLauncher;
    }
}