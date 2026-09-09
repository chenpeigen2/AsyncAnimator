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

    var asyncEnable by SyncedVar(lock, -1)
        private set
    var rtUnlockEnable by SyncedVar(lock, -1)
        private set
    var multiAppBlockEnable by SyncedVar(lock, -1)
        private set
    var iconBlurEnable by SyncedVar(lock, -1)
        private set
    var onePxEnable by SyncedVar(lock, -1)
        private set
    var interruptThreshold by SyncedVar(lock, 1.0f)
        private set
    var limtSize by SyncedVar(lock, -1)
        private set

    @Volatile
    private var onePxPkgDisableSnapshot: List<String> = emptyList()

    @Volatile
    private var onePxCardDisableSnapshot: List<Int> = emptyList()

    /** OPPO compatibility: -1 = unconfigured (default), 0 = disabled, 1 = enabled. */
    val isAsyncConfigured: Boolean get() = asyncEnable >= 0

    /** Read-only snapshot view; updated only via [simulateRemoteUpdate] whole-snapshot replace. */
    val onePxPkgDisableList: List<String> get() = onePxPkgDisableSnapshot

    val onePxCardDisableList: List<Int> get() = onePxCardDisableSnapshot

    /** Full remote-update simulation: 7 scalars + 2 disable lists. Lists are replaced as
     * immutable whole snapshots (never in-place clear/add); null list keeps the previous value.
     * Mirrors OPPO RUS parser writing onePxEnable + both 1px ItemArrays, which the old
     * 6-arg overload could not express. */
    fun simulateRemoteUpdate(async: Int, rtUnlock: Int, multiApp: Int, iconBlur: Int,
                             onePx: Int, threshold: Float, limtSize: Int,
                             onePxPkgDisableList: List<String>?, onePxCardDisableList: List<Int>?) =
        synchronized(lock) {
            this.asyncEnable = async
            this.rtUnlockEnable = rtUnlock
            this.multiAppBlockEnable = multiApp
            this.iconBlurEnable = iconBlur
            this.onePxEnable = onePx
            this.interruptThreshold = threshold
            this.limtSize = limtSize
            if (onePxPkgDisableList != null) onePxPkgDisableSnapshot = onePxPkgDisableList.toList()
            if (onePxCardDisableList != null) onePxCardDisableSnapshot = onePxCardDisableList.toList()
        }

    /** Backward-compatible 6-scalar variant: leaves onePxEnable and both lists untouched. */
    fun simulateRemoteUpdate(async: Int, rtUnlock: Int, multiApp: Int, iconBlur: Int,
                             threshold: Float, limtSize: Int) =
        simulateRemoteUpdate(async, rtUnlock, multiApp, iconBlur, this.onePxEnable,
            threshold, limtSize, null, null)

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
