package com.asyncanimator.core

/**
 * Trace — 简化的 trace 工具，对应 Android 平台 [android.os.Trace]。
 *
 * review 01 中 AsyncAnimCallbacks 大量使用 `Trace.traceBegin/End` 做动效溯源。
 * 本实现把 trace 输出到 stderr（demo 模块的 DemoBaseActivity 会重定向到日志区）。
 *
 * 设计：单 tag 字符串 + 嵌套深度，避免 native Trace 的开销，方便单元测试断言。
 */
object Trace {

    /** 每线程一份栈：traceBegin/End 允许跨线程（动画线程 begin、主线程 end 不互相错位）。 */
    private val STACK = ThreadLocal.withInitial { ArrayDeque<String>() }

    internal fun traceBegin(tag: Long, name: String) {
        val tagStr = "[$tag] $name"
        STACK.get().addFirst(tagStr)
        log(">>> $tagStr")
    }

    internal fun traceEnd(tag: Long) {
        val stack = STACK.get()
        if (stack.isNotEmpty()) {
            val name = stack.removeFirst()
            log("<<< $name")
        }
    }

    internal val depth: Int get() = STACK.get().size

    internal fun clear() = STACK.get().clear()

    private fun log(msg: String) {
        // 测试时可重定向 System.err
        System.err.println("Trace $msg")
    }
}
