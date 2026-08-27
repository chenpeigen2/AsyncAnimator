package com.asyncanimator.util;

/**
 * Trace — 简化的 trace 工具，对应 Android 平台 {@link android.os.Trace}。
 *
 * <p>分析文档 §6.3.3 中 AsyncAnimCallbacks 大量使用 {@code Trace.traceBegin/End} 做动效溯源。
 * 本实现把 trace 输出到 stderr（demo 模块的 DemoBaseActivity 会重定向到日志区）。
 *
 * <p>设计：单 tag 字符串 + 嵌套深度，避免 native Trace 的开销，方便单元测试断言。
 */
public final class Trace {

    private static final java.util.Deque<String> STACK = new java.util.ArrayDeque<>();

    private Trace() {}

    public static void traceBegin(long tag, String name) {
        String tagStr = "[" + tag + "] " + name;
        STACK.push(tagStr);
        log(">>> " + tagStr);
    }

    public static void traceEnd(long tag) {
        if (!STACK.isEmpty()) {
            String name = STACK.pop();
            log("<<< " + name);
        }
    }

    public static int depth() {
        return STACK.size();
    }

    public static void clear() {
        STACK.clear();
    }

    private static void log(String msg) {
        // 测试时可重定向 System.err
        System.err.println("Trace " + msg);
    }
}