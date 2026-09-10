package com.asyncanimator.core

import com.asyncanimator.BuildConfig

/**
 * 进程内诊断日志策略与 stderr 输出入口。
 * Debug 默认 INFO，Release 默认 OFF；等级通过 volatile 发布，不是持久化或远程配置系统。
 */
object LogUtils {
    /**
     * 关闭通过普通等级门控入口提交的诊断输出。
     */
    const val OFF = 0
    /**
     * 允许普通信息输出，也是 Debug 构建的初始等级。
     */
    const val INFO = 1
    /**
     * 允许信息输出并使 isAlwayson 返回 true；无需改变输出目的地。
     */
    const val ALWAYS = 2
    @Volatile private var level = if (BuildConfig.DEBUG) INFO else OFF

    /**
     * 读取当前进程是否允许普通诊断输出；INFO 和 ALWAYS 等级返回 true。
     * 只读取已发布的等级，不访问远程配置或设备日志服务。
     */
    @JvmStatic fun isLogOpen(): Boolean = level >= INFO
    /**
     * 判断等级是否恰为 ALWAYS，而非仅判断日志是否开启。
     * 该标记是本地诊断策略，不代表跨进程或跨重启持久化。
     */
    @JvmStatic fun isAlwayson(): Boolean = level == ALWAYS
    /**
     * 替换当前进程的诊断等级，后续普通日志读取新值。
     * @param level 只接受 OFF、INFO 或 ALWAYS；非法值在修改前抛出参数异常。
     * 已开始的 Trace 区段仍按开始时记录的策略输出结束标记。
     */
    @JvmStatic fun setLogLevel(level: Int) {
        require(level in OFF..ALWAYS) { "Unknown diagnostic log level: $level" }
        this.level = level
    }
    /**
     * 在当前等级允许时输出带线程名、标签和正文的普通诊断日志。
     * 关闭日志时不调用底层输出；消息字符串的构造仍由调用方在进入本方法前完成。
     */
    @JvmStatic fun i(tag: String, message: String) {
        if (isLogOpen()) emit(tag, message)
    }

    /**
     * 直接向当前 System.err 输出带线程名和标签的一行诊断文本，不再次检查日志等级。
     * Trace 用此入口遵守区段开始时的策略，避免中途关闭日志导致开始与结束标记不配对。
     */
    internal fun emit(tag: String, message: String) {
        System.err.println("Trace [${Thread.currentThread().name}] $tag: $message")
    }
}
