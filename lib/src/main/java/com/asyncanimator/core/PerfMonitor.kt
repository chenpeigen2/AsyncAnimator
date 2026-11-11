package com.asyncanimator.core

import com.asyncanimator.api.Layer
import com.asyncanimator.api.LayerId
import com.asyncanimator.api.PublicApi
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 可选的帧性能统计开关与聚合容器，按命名通道记录帧间隔。
 * 关闭时 recordFrame 只做一次 volatile 读取与提前返回，不分配对象、不格式化日志；
 * 开启后才创建通道条目并按帧更新计数，统计写入使用无锁原子量，不持有调用方锁。
 * 统计是纯诊断数据：不改变帧调度、动画生命周期或线程归属，也不接入平台 Perfetto。
 */
@PublicApi
@Layer(LayerId.BASIC)
object PerfMonitor {

    /**
     * 单通道的原子统计：最近帧时间、累计帧数、间隔总和与最大间隔，均为纳秒。
     * 多线程同时记录同一通道时计数可能略微丢失，但不会产生不一致的崩溃状态。
     */
    private class ChannelStats {
        val lastNanos = AtomicLong(-1L)
        val count = AtomicLong(0L)
        val totalGapNanos = AtomicLong(0L)
        val maxGapNanos = AtomicLong(0L)
    }

    /**
     * 某一通道某一时点的只读统计快照；avgGapNanos 为累计间隔平均值，firstFrame 表示尚未收到第二帧。
     */
    @PublicApi
    data class FrameStatsSnapshot @PublicApi constructor(
        /** 已记录的帧数；至少为 1 时才存在有效统计。 */
        @property:PublicApi
        val count: Long,
        /** 相邻帧间隔平均值，纳秒；只有一帧时为 0。 */
        @property:PublicApi
        val avgGapNanos: Long,
        /** 观察到的最大相邻帧间隔，纳秒。 */
        @property:PublicApi
        val maxGapNanos: Long,
        /** 最近一次记录的帧时间戳，纳秒，未记录过任何帧时为 -1。 */
        @property:PublicApi
        val lastNanos: Long
    )

    /**
     * 全局统计开关，volatile 发布；读取成本一次原子可见性检查，适合每帧热路径。
     * 关闭状态下所有 recordFrame 直接返回，通道表不被触碰。
     */
    @Volatile
    private var enabled = false

    /**
     * 内置帧通道名，供三条帧路径与测试统一引用，避免埋点处散落字符串字面量。
     */
    internal object Channels {
        /** AnimationHandler 所属线程帧循环的通道名。 */
        internal const val ANIMATION_HANDLER = "animation-handler"

        /** ChoreographerTickScheduler VSYNC 帧派发的通道名。 */
        internal const val CHOREOGRAPHER_TICK = "choreographer-tick"

        /** RectSpringDriver 六轴聚合帧的通道名。 */
        internal const val RECT_SPRING = "rect-spring"
    }

    /** 全部通道统计；条目仅在开启状态下首次记录对应通道时创建。 */
    private val channels = ConcurrentHashMap<String, ChannelStats>()

    /**
     * 打开或关闭帧统计。切换不回填历史、不清空已有通道；关闭期间的帧不计入任何通道。
     * @param value true 时后续 recordFrame 开始更新统计，false 时全部记录入口立即返回。
     */
    @PublicApi
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** 读取当前统计是否开启；只读取已发布的开关，不触碰通道表。 */
    @PublicApi
    fun isEnabled(): Boolean = enabled

    /**
     * 记录一帧并按与上一帧的时间差更新指定通道。
     * 关闭时为纯早退；开启时首次遇到新通道会创建条目，后续更新均为原子量写入。
     * 时间戳原点须与同一通道的历史记录一致；不校验时间倒退，倒退间隔按负值计入平均值。
     * @param channel 通道名，如调度器或驱动标识，不作为平台追踪标签。
     * @param nowNanos 本帧的纳秒时间戳，使用与通道一致的单调时钟。
     */
    internal fun recordFrame(channel: String, nowNanos: Long) {
        if (!enabled) return
        val stats = channels.getOrPut(channel) { ChannelStats() }
        val last = stats.lastNanos.getAndSet(nowNanos)
        if (last < 0) {
            stats.count.incrementAndGet()
            return
        }
        val gap = nowNanos - last
        stats.count.incrementAndGet()
        stats.totalGapNanos.addAndGet(gap)
        stats.maxGapNanos.accumulateAndGet(gap) { current, candidate -> maxOf(current, candidate) }
    }

    /**
     * 取得全部通道的只读统计快照；返回值不与内部状态共享可变对象。
     * 快照是逐通道独立读取的，跨通道不构成同一时间点的原子视图。
     */
    @PublicApi
    fun snapshot(): Map<String, FrameStatsSnapshot> = Collections.unmodifiableMap(
        channels.mapValues { (_, stats) ->
            val count = stats.count.get()
            val total = stats.totalGapNanos.get()
            FrameStatsSnapshot(
                count = count,
                avgGapNanos = if (count > 1) total / (count - 1) else 0L,
                maxGapNanos = stats.maxGapNanos.get(),
                lastNanos = stats.lastNanos.get()
            )
        }
    )

    /**
     * 清空全部通道统计，开关状态保持不变；不清除开关、不回收通道表容量。
     */
    @PublicApi
    fun reset() {
        channels.clear()
    }

    /**
     * 按当前通道统计输出多行诊断文本到 stderr；无条件输出，与统计开关和日志等级独立。
     * 调用方应先检查 isEnabled/isLogOpen 决定是否承担格式化成本；方法本身不修改任何统计。
     */
    @PublicApi
    fun dump() {
        for ((channel, stats) in snapshot()) {
            LogUtils.emit(
                "PerfMonitor",
                "$channel frames=${stats.count} avgGapMs=${stats.avgGapNanos / 1_000_000.0} " +
                    "maxGapMs=${stats.maxGapNanos / 1_000_000.0}"
            )
        }
    }
}
