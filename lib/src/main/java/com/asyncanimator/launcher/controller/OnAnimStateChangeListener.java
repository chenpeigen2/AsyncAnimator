package com.asyncanimator.launcher.controller;

/**
 * 状态变更监听器接口。
 *
 * <p>对应分析文档 §6.8.10。
 */
public interface OnAnimStateChangeListener {
    void onAnimStateChanged(AnimationState oldState, AnimationState newState, Object runningTask);
}