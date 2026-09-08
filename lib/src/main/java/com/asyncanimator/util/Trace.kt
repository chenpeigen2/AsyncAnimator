package com.asyncanimator.util

/**
 * Trace — 简化的 trace 工具，对应 Android 平台 [android.os.Trace]。
 *
 * 分析文档 §6.3.3 中 AsyncAnimCallbacks 大量使用 `Trace.traceBegin/End` 做动效溯源。
 * 本实现把 trace 输出到 stderr（demo 模块的 DemoBaseActivity 会重定向到日志区）。
 *
 * 设计：单 tag 字符串 + 嵌套深度，避免 native Trace 的开销，方便单元测试断言。
 */
object Trace {

    private val STACK: java.util.Deque<String> = java.util.ArrayDeque()

    internal fun traceBegin(tag: Long, name: String) {
        val tagStr = "[$tag] $name"
        STACK.push(tagStr)
        log(">>> $tagStr")
    }

    internal fun traceEnd(tag: Long) {
        if (STACK.isNotEmpty()) {
            val name = STACK.pop()
            log("<<< $name")
        }
    }

    internal val depth: Int get() = STACK.size

    internal fun clear() = STACK.clear()

    private fun log(msg: String) {
        // 测试时可重定向 System.err
        System.err.println("Trace $msg")
    }
}
