package com.asyncanimator.core

import com.asyncanimator.api.Layer
import com.asyncanimator.api.LayerId
import com.asyncanimator.api.PublicApi

/**
 * 按线程维护嵌套区段的轻量诊断工具，同时写入平台 Trace 和 stderr。
 * 平台区段可由 Perfetto 等工具采集；stderr 仅用于本地与 Demo 展示，不能据此推断屏幕呈现时序。
 */
@PublicApi
@Layer(LayerId.BASIC)
object Trace {

    /**
     * 界面相关诊断的标签值，仅作为输出元数据；平台后端使用应用 Trace 通道，不按该数值选择原生标签。
     */
    internal const val TAG_VIEW = 8L

    private const val MAX_SECTION_NAME_LENGTH = 127

    /**
     * 平台区段写入边界，隔离 android.os.Trace 以便 JVM 测试验证配对和名称约束。
     * 每个区段开始时会捕获当前后端，后续替换不影响已存在区段的结束配对。
     */
    internal interface Backend {

        /** 在当前线程开始指定名称的平台区段，名称已由调用方按平台限制截断。 */
        fun beginSection(name: String)

        /** 在当前线程结束最近一次由同一后端开始的区段。 */
        fun endSection()
    }

    /**
     * 使用 android.os.Trace 的默认生产后端，不增加日志等级判断或跨线程配对。
     */
    private object AndroidBackend : Backend {
        override fun beginSection(name: String) = android.os.Trace.beginSection(name)
        override fun endSection() = android.os.Trace.endSection()
    }

    /**
     * 当前新区段使用的平台后端，默认写入 android.os.Trace。
     * 仅用于库内测试替换；调用方应在没有活跃区段时更换，并在测试结束后恢复默认实例。
     */
    @Volatile
    internal var backend: Backend = AndroidBackend

    /**
     * 单个区段的不可变快照：保存格式化名称、开始时的日志策略及配对的平台后端。
     */
    private data class Section(val name: String, val logAtBegin: Boolean, val backend: Backend)

    private val stack = ThreadLocal.withInitial { ArrayDeque<Section>() }

    /**
     * 取得当前线程独有的区段栈，首次读取由 ThreadLocal 工厂创建。
     * 返回活跃栈本身而非副本；只在当前线程上使用，不与其他线程组合成共享追踪栈。
     */
    private fun currentStack(): ArrayDeque<Section> = checkNotNull(stack.get())

    /**
     * 将标签和区段名入栈，并记录开始瞬间是否允许输出日志。
     * 平台名称按 127 字符限制截断；即使日志关闭也写入平台区段，结束必须回到同一线程配对。
     */
    internal fun traceBegin(tag: Long, name: String) {
        val section = Section("[$tag] $name", LogUtils.isLogOpen(), backend)
        currentStack().addFirst(section)
        section.backend.beginSection(section.name.take(MAX_SECTION_NAME_LENGTH))
        if (section.logAtBegin) LogUtils.emit("Trace", ">>> ${section.name}")
    }

    /**
     * 弹出当前线程最近开始的区段，空栈时直接返回。
     * 先闭合该段开始时捕获的平台后端；是否输出结束日志由该区段开始时的策略决定。
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun traceEnd(tag: Long) {
        val section = currentStack().removeFirstOrNull() ?: return
        section.backend.endSection()
        if (section.logAtBegin) LogUtils.emit("Trace", "<<< ${section.name}")
    }

    /**
     * 在当前线程创建包围 action 的诊断区段，返回 action 的原始结果。
     * 无论正常返回还是抛出异常，均在 finally 中结束平台与日志区段；业务异常继续向外传播。
     */
    internal fun <T> section(tag: Long, name: String, action: () -> T): T {
        traceBegin(tag, name)
        return try {
            action()
        } finally {
            traceEnd(tag)
        }
    }

    /**
     * 读取当前线程尚未结束的区段数量；其他线程的 begin/end 不影响该值。
     */
    internal val depth: Int
        get() = currentStack().size

    /**
     * 丢弃当前线程的全部未结束区段，并仅闭合对应平台后端，不补发结束日志。
     * 不影响其他线程的区段栈，主要用于测试恢复或当前线程诊断上下文清理。
     */
    internal fun clear() {
        val sections = currentStack()
        while (sections.isNotEmpty()) sections.removeFirst().backend.endSection()
    }
}
