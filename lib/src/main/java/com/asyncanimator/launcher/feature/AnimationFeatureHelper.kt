package com.asyncanimator.launcher.feature

/**
 * AnimationFeatureHelper — 远程灰度配置容器（demo 简化版）。
 *
 * 对应分析文档 §6.11。volatile 字段 + 远程配置变更监听。
 *
 * demo 版本：用本地 setter 模拟远程 RUS 配置下发。
 */
object AnimationFeatureHelper {

    private val lock = Any()

    @Volatile
    var asyncEnable = 1
        set(v) = synchronized(lock) { field = v }

    @Volatile
    var rtUnlockEnable = 1
        set(v) = synchronized(lock) { field = v }

    @Volatile
    var multiAppBlockEnable = 0
        set(v) = synchronized(lock) { field = v }

    @Volatile
    var iconBlurEnable = 1
        set(v) = synchronized(lock) { field = v }

    @Volatile
    var onePxEnable = 1
        set(v) = synchronized(lock) { field = v }

    @Volatile
    var interruptThreshold = 1.0f
        set(v) = synchronized(lock) { field = v }

    @Volatile
    var limtSize = -1
        set(v) = synchronized(lock) { field = v }

    val onePxPkgDisableList: List<String> = ArrayList()
    val onePxCardDisableList: List<Int> = ArrayList()

    /** 模拟远程配置下发：批量更新所有字段。 */
    fun simulateRemoteUpdate(async: Int, rtUnlock: Int, multiApp: Int, iconBlur: Int,
                             threshold: Float, limtSize: Int) {
        synchronized(lock) {
            this.asyncEnable = async
            this.rtUnlockEnable = rtUnlock
            this.multiAppBlockEnable = multiApp
            this.iconBlurEnable = iconBlur
            this.interruptThreshold = threshold
            this.limtSize = limtSize
        }
    }
}
