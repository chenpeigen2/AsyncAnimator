package com.asyncanimator.manager

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * AnimationFeatureHelper — 远程灰度配置容器（demo 简化版）。
 *
 * 对应 `docs/review/03-controller-manager-seq.md`。volatile 读 + 同锁写的配置项，外加远程配置变更监听。
 *
 * demo 版本：用本地 setter 模拟远程 RUS 配置下发。
 */
object AnimationFeatureHelper {

    private val lock = Any()

    var asyncEnable by SyncedVar(lock, 1)
    var rtUnlockEnable by SyncedVar(lock, 1)
    var multiAppBlockEnable by SyncedVar(lock, 0)
    var iconBlurEnable by SyncedVar(lock, 1)
    var onePxEnable by SyncedVar(lock, 1)
    var interruptThreshold by SyncedVar(lock, 1.0f)
    var limtSize by SyncedVar(lock, -1)

    val onePxPkgDisableList: List<String> = mutableListOf()
    val onePxCardDisableList: List<Int> = mutableListOf()

    /** 模拟远程配置下发：批量更新所有字段。 */
    fun simulateRemoteUpdate(async: Int, rtUnlock: Int, multiApp: Int, iconBlur: Int,
                             threshold: Float, limtSize: Int) = synchronized(lock) {
        this.asyncEnable = async
        this.rtUnlockEnable = rtUnlock
        this.multiAppBlockEnable = multiApp
        this.iconBlurEnable = iconBlur
        this.interruptThreshold = threshold
        this.limtSize = limtSize
    }

    /** 配置项委托：@Volatile 读（无锁）+ 同 [lock] 写（与批量下发互斥）。 */
    private class SyncedVar<T>(private val lock: Any, initial: T) : ReadWriteProperty<Any?, T> {
        @Volatile
        private var value = initial

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            synchronized(lock) { this.value = value }
        }
    }
}
