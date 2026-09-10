package com.asyncanimator.manager

import java.util.Collections
import com.asyncanimator.core.LogUtils
import com.asyncanimator.thread.Executors
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
    private class Registration(var callback: (() -> Unit)?)
    private val registrations = mutableListOf<Registration>()
    @Volatile private var adaptiveAnimationEnabled = false

    /** One coherent read. Lists are already defensive, unmodifiable snapshots. */
    data class Snapshot(
        val asyncEnable: Int, val rtUnlockEnable: Int, val multiAppBlockEnable: Int,
        val iconBlurEnable: Int, val onePxEnable: Int, val interruptThreshold: Float,
        val limtSize: Int, val onePxPkgDisableList: List<String>, val onePxCardDisableList: List<Int>,
        val adaptiveAnimationEnabled: Boolean
    ) {
        val radiusAnimationEnable: Boolean get() = !adaptiveAnimationEnabled
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(asyncEnable, rtUnlockEnable, multiAppBlockEnable, iconBlurEnable, onePxEnable,
            interruptThreshold, limtSize, onePxPkgDisableSnapshot, onePxCardDisableSnapshot,
            adaptiveAnimationEnabled)
    }

    /**
     * Local RUS-like notification, NOT a ROM service connection. Always posts to main on Android;
     * read snapshot() there for the latest coherent state, not every historical update payload.
     * Each add owns a registration. Closing it drops queued deliveries; executing callbacks
     * cannot be recalled. A callback may register/remove/update reentrantly (no lock is held).
     */
    fun addRemoteUpdateListener(callback: () -> Unit): AutoCloseable {
        val registration = Registration(callback)
        synchronized(lock) { registrations.add(registration) }
        return AutoCloseable {
            synchronized(lock) {
                registration.callback = null
                registrations.remove(registration)
            }
        }
    }

    /** Removes all registrations of this exact callback instance. Prefer the returned handle. */
    fun removeRemoteUpdateListener(callback: () -> Unit) = synchronized(lock) {
        registrations.removeAll {
            if (it.callback === callback) { it.callback = null; true } else false
        }
        Unit
    }

    /** Global owner teardown only; pages close their own subscription instead. Keeps config. */
    fun onDestroy() = synchronized(lock) {
        registrations.forEach { it.callback = null }
        registrations.clear()
    }

    private fun publish(update: () -> Unit) {
        val listeners = synchronized(lock) {
            update()
            registrations.toList()
        }
        if (listeners.isNotEmpty()) Executors.MAIN_EXECUTOR.post {
            for (registration in listeners) {
                val callback = synchronized(lock) { registration.callback } ?: continue
                try { callback() }
                catch (error: Exception) {
                    LogUtils.i("AnimationFeatureHelper", "update listener failed: ${error.message}")
                }
            }
        }
    }

    /** Host-supplied policy; there is no automatic LauncherAnimConfig/RUS lookup in this library. */
    fun setAdaptiveAnimationEnabled(enabled: Boolean) = publish {
        adaptiveAnimationEnabled = enabled
        if (enabled) interruptThreshold = 1.0f
    }

    fun getRadiusAnimationEnable(): Boolean = !adaptiveAnimationEnabled

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
     * A list-copy failure propagates without changing config or notifying subscribers. Callers
     * must not mutate input collections concurrently while this method copies them.
     * Mirrors OPPO RUS parser writing onePxEnable + both 1px ItemArrays, which the old
     * 6-arg overload could not express. */
    fun simulateRemoteUpdate(async: Int, rtUnlock: Int, multiApp: Int, iconBlur: Int,
                             onePx: Int, threshold: Float, limtSize: Int,
                             onePxPkgDisableList: List<String>?, onePxCardDisableList: List<Int>?) {
        // Caller collections may execute code or throw while copied. Prepare BOTH before
        // changing config or taking its lock, so conversion failures cannot publish half a batch.
        val packages = onePxPkgDisableList?.let { Collections.unmodifiableList(ArrayList(it)) }
        val cards = onePxCardDisableList?.let { Collections.unmodifiableList(ArrayList(it)) }
        publish {
            updateScalars(async, rtUnlock, multiApp, iconBlur, threshold, limtSize)
            this.onePxEnable = onePx
            if (packages != null) onePxPkgDisableSnapshot = packages
            if (cards != null) onePxCardDisableSnapshot = cards
        }
    }

    /** Backward-compatible 6-scalar variant: leaves onePxEnable and both lists untouched. */
    fun simulateRemoteUpdate(async: Int, rtUnlock: Int, multiApp: Int, iconBlur: Int,
                             threshold: Float, limtSize: Int) = publish {
        updateScalars(async, rtUnlock, multiApp, iconBlur, threshold, limtSize)
    }

    /** Called only inside publish's lock; no nested publication/duplicate notification. */
    private fun updateScalars(async: Int, rtUnlock: Int, multiApp: Int, iconBlur: Int,
                              threshold: Float, limtSize: Int) {
        asyncEnable = async
        rtUnlockEnable = rtUnlock
        multiAppBlockEnable = multiApp
        iconBlurEnable = iconBlur
        interruptThreshold = if (adaptiveAnimationEnabled) 1.0f else threshold
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
