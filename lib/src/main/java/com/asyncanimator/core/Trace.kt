package com.asyncanimator.core

/**
 * 按线程维护嵌套区段的轻量诊断工具，输出到 stderr。
 * 不是平台 Trace 或 Perfetto 接口，不能据此推断系统绘制或屏幕呈现时序。
 */
object Trace {

    /**
     * 界面相关诊断的标签值，仅作为输出元数据，不连接平台追踪后端。
     */
    internal const val TAG_VIEW = 8L

    /**
     * 单个区段的不可变快照：保存格式化名称及开始时的日志策略，供结束时配对使用。
     */
    private data class Section(val name: String, val logAtBegin: Boolean)
    private val stack = ThreadLocal.withInitial { ArrayDeque<Section>() }
    /**
     * 取得当前线程独有的区段栈，首次读取由 ThreadLocal 工厂创建。
     * 返回活跃栈本身而非副本；只在当前线程上使用，不与其他线程组合成共享追踪栈。
     */
    private fun currentStack(): ArrayDeque<Section> = checkNotNull(stack.get())

    /**
     * 将标签和区段名入栈，并记录开始瞬间是否允许输出日志。
     * 即使日志关闭也入栈以维持嵌套层级；开启时立即输出开始标记，结束必须回到同一线程配对。
     */
    internal fun traceBegin(tag: Long, name: String) {
        val section = Section("[$tag] $name", LogUtils.isLogOpen())
        currentStack().addFirst(section)
        if (section.logAtBegin) LogUtils.emit("Trace", ">>> ${section.name}")
    }

    /**
     * 弹出当前线程最近开始的区段，空栈时直接返回。
     * 是否输出结束标记由该区段开始时的策略决定；tag 不参与查找或校验，调用方负责标签配对。
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun traceEnd(tag: Long) {
        val section = currentStack().removeFirstOrNull() ?: return

        if (section.logAtBegin) LogUtils.emit("Trace", "<<< ${section.name}")
    }

    /**
     * 在当前线程创建包围 action 的诊断区段，返回 action 的原始结果。
     * 无论正常返回还是抛出异常，均在 finally 中结束区段；业务异常继续向外传播，不跨线程配对。
     */
    internal fun <T> section(tag: Long, name: String, action: () -> T): T {
        traceBegin(tag, name)
        return try { action() } finally { traceEnd(tag) }
    }

    /**
     * 读取当前线程尚未结束的区段数量；其他线程的 begin/end 不影响该值。
     */
    internal val depth: Int get() = currentStack().size
    /**
     * 丢弃当前线程的全部未结束区段，不补发结束日志。
     * 不影响其他线程的区段栈，主要用于测试恢复或当前线程诊断上下文清理。
     */
    internal fun clear() = currentStack().clear()
}
