package com.asyncanimator.launcher.seq

/**
 * DefaultAnimationSeqHelper — SeqHelper 的 no-op 基类。
 *
 * 对应分析文档 §6.9。feature off 时返回此基类。
 */
open class DefaultAnimationSeqHelper {

    open fun addSeqId(bundle: android.os.Bundle?) {}
    open val canFinishRecent: Boolean get() = true
    open val canInterceptGesture: Boolean get() = true
    open fun delayFinishRecents(r: Runnable?): Boolean {
        r?.run()
        return false
    }
    open fun clearFinishRecentsRunnable() {}
    open fun resetInterceptState() {}
    open fun updateNextFinishSeqIdIfNeed(recentsController: Any?) {}
    open fun getNextFinishSeqId(recentsController: Any?): Long = 0L
}
