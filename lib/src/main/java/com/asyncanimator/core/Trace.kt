package com.asyncanimator.core

/**
 * Thread-local diagnostic sections, emitted to stderr for the demo log view.
 * This is NOT android.os.Trace/Perfetto. Begin and end must execute on the same thread;
 * another thread's end cannot close an open section here.
 */
object Trace {
    /** AOSP VIEW tag value; metadata only, not a platform tracing backend. */
    internal const val TAG_VIEW = 8L

    private data class Section(val name: String, val logAtBegin: Boolean)
    private val stack = ThreadLocal.withInitial { ArrayDeque<Section>() }
    private fun currentStack(): ArrayDeque<Section> = checkNotNull(stack.get())

    internal fun traceBegin(tag: Long, name: String) {
        val section = Section("[$tag] $name", LogUtils.isLogOpen())
        currentStack().addFirst(section)
        if (section.logAtBegin) LogUtils.emit("Trace", ">>> ${section.name}")
    }

    // Callers must pair the same tag on the same thread; no platform tag filtering is emulated.
    @Suppress("UNUSED_PARAMETER")
    internal fun traceEnd(tag: Long) {
        val section = currentStack().removeFirstOrNull() ?: return
        // Policy belongs to begin, so disabling logging mid-section still emits its matching end.
        if (section.logAtBegin) LogUtils.emit("Trace", "<<< ${section.name}")
    }

    internal fun <T> section(tag: Long, name: String, action: () -> T): T {
        traceBegin(tag, name)
        return try { action() } finally { traceEnd(tag) }
    }

    internal val depth: Int get() = currentStack().size
    internal fun clear() = currentStack().clear()
}
