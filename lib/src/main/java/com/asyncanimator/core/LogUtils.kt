package com.asyncanimator.core

import com.asyncanimator.BuildConfig

/** Minimal process-local diagnostic policy, not OPPO's RUS or persistent logging system. */
object LogUtils {
    const val OFF = 0
    const val INFO = 1
    const val ALWAYS = 2
    @Volatile private var level = if (BuildConfig.DEBUG) INFO else OFF

    @JvmStatic fun isLogOpen(): Boolean = level >= INFO
    @JvmStatic fun isAlwayson(): Boolean = level == ALWAYS
    @JvmStatic fun setLogLevel(level: Int) {
        require(level in OFF..ALWAYS) { "Unknown diagnostic log level: $level" }
        this.level = level
    }
    @JvmStatic fun i(tag: String, message: String) {
        if (isLogOpen()) emit(tag, message)
    }

    /** Trace uses its begin-time policy so a level change cannot orphan an open log section. */
    internal fun emit(tag: String, message: String) {
        System.err.println("Trace [${Thread.currentThread().name}] $tag: $message")
    }
}
