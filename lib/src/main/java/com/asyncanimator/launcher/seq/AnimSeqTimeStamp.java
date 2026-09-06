package com.asyncanimator.launcher.seq;

/**
 * AnimSeqTimeStamp — 全局时间戳协调（demo 简化版）。
 *
 * <p>对应原 OPPO 代码 {@code com.android.systemui.shared.system.AnimSeqTimeStamp}。
 * 跨模块（launcher + systemui）共享 4 个时间戳，用于判断"上次 recent finish/start app 时间"。
 */
public class AnimSeqTimeStamp {

    private static volatile long lastStartAppTime = 0;
    private static volatile long lastRecentFinishTime = 0;
    private static volatile long lastRecentStartTime = 0;
    private static volatile long lastLaunchTaskTime = 0;

    public static void updateLastStartAppTime() {
        lastStartAppTime = System.currentTimeMillis();
    }

    public static void updateLastRecentFinishTime() {
        lastRecentFinishTime = System.currentTimeMillis();
    }

    public static void updateLastRecentStartTime() {
        lastRecentStartTime = System.currentTimeMillis();
    }

    public static void updateLastLaunchTaskTime() {
        lastLaunchTaskTime = System.currentTimeMillis();
    }

    public static void resetLastStartAppTime() {
        lastStartAppTime = 0;
    }

    /** 测试辅助：复位全部时间戳（全局静态状态，测试间互相污染）。 */
    public static void resetAllForTest() {
        lastStartAppTime = 0;
        lastRecentFinishTime = 0;
        lastRecentStartTime = 0;
        lastLaunchTaskTime = 0;
    }

    public static long getTimeGapToLastStartAppTime() {
        return lastStartAppTime == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastStartAppTime;
    }

    public static long getTimeGapToLastRecentFinishTime() {
        return lastRecentFinishTime == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastRecentFinishTime;
    }

    public static long getTimeGapToLastRecentStartTime() {
        return lastRecentStartTime == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastRecentStartTime;
    }

    public static long getTimeGapToLastLaunchTaskTime() {
        return lastLaunchTaskTime == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastLaunchTaskTime;
    }
}