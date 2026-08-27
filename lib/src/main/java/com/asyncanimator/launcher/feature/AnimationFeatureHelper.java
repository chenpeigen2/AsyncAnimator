package com.asyncanimator.launcher.feature;

import java.util.ArrayList;
import java.util.List;

/**
 * AnimationFeatureHelper — 远程灰度配置容器（demo 简化版）。
 *
 * <p>对应分析文档 §6.11。9 个 volatile 字段 + 远程配置变更监听。
 *
 * <p>demo 版本：用本地 setter 模拟远程 RUS 配置下发。
 */
public class AnimationFeatureHelper {

    private static final AnimationFeatureHelper INSTANCE = new AnimationFeatureHelper();

    public static AnimationFeatureHelper getInstance() {
        return INSTANCE;
    }

    private volatile int mAsyncEnable = 1;
    private volatile int mRTUnlockEnable = 1;
    private volatile int mMultiAppBlockEnable = 0;
    private volatile int mIconBlurEnable = 1;
    private volatile int m1pxEnable = 1;
    private volatile float mInterruptThreshold = 1.0f;
    private volatile int mLimtSize = -1;
    private volatile List<String> m1pxPkgDisableList = new ArrayList<>();
    private volatile List<Integer> m1pxCardDisableList = new ArrayList<>();

    private final Object lock = new Object();

    public int getAsyncEnable() { return mAsyncEnable; }
    public int getRTUnlockEnable() { return mRTUnlockEnable; }
    public int getMultiAppBlockEnable() { return mMultiAppBlockEnable; }
    public int getIconBlurEnable() { return mIconBlurEnable; }
    public int get1pxEnable() { return m1pxEnable; }
    public float getInterruptThreshold() { return mInterruptThreshold; }
    public int getLimtSize() { return mLimtSize; }
    public List<String> get1pxPkgDisableList() { return m1pxPkgDisableList; }
    public List<Integer> get1pxCardDisableList() { return m1pxCardDisableList; }

    public void setAsyncEnable(int v) { synchronized (lock) { this.mAsyncEnable = v; } }
    public void setRTUnlockEnable(int v) { synchronized (lock) { this.mRTUnlockEnable = v; } }
    public void setMultiAppBlockEnable(int v) { synchronized (lock) { this.mMultiAppBlockEnable = v; } }
    public void setIconBlurEnable(int v) { synchronized (lock) { this.mIconBlurEnable = v; } }
    public void set1pxEnable(int v) { synchronized (lock) { this.m1pxEnable = v; } }
    public void setInterruptThreshold(float v) {
        synchronized (lock) {
            this.mInterruptThreshold = v;
        }
    }
    public void setLimtSize(int v) { synchronized (lock) { this.mLimtSize = v; } }

    /** 模拟远程配置下发：批量更新所有字段。 */
    public void simulateRemoteUpdate(int async, int rtUnlock, int multiApp, int iconBlur,
                                     float threshold, int limtSize) {
        synchronized (lock) {
            this.mAsyncEnable = async;
            this.mRTUnlockEnable = rtUnlock;
            this.mMultiAppBlockEnable = multiApp;
            this.mIconBlurEnable = iconBlur;
            this.mInterruptThreshold = threshold;
            this.mLimtSize = limtSize;
        }
    }
}