package com.asyncanimator.demo

import java.io.PrintStream

/** One stderr wrapper shared by live demo pages, released independently of page destruction order.
 * Only String println trace lines are routed, matching LogUtils. Does not close the delegated stream.
 * A callback already executing cannot be recalled; queued UI work must check its page lifecycle.
 */
internal class TraceLogRedirector {
    private class Registration(var callback: ((String) -> Unit)?)
    private val lock = Any()
    private val registrations = mutableListOf<Registration>()
    private var original: PrintStream? = null
    private var stream: PrintStream? = null

    fun subscribe(callback: (String) -> Unit): AutoCloseable = synchronized(lock) {
        if (registrations.isEmpty()) {
            val delegate = System.err
            val wrapper = object : PrintStream(delegate) {
                override fun println(x: String?) {
                    super.println(x)
                    if (x != null && x.contains("Trace")) deliver(x)
                }
            }
            original = delegate
            stream = wrapper
            System.setErr(wrapper)
        }
        val registration = Registration(callback)
        registrations.add(registration)
        AutoCloseable {
            synchronized(lock) {
                if (registrations.remove(registration)) {
                    registration.callback = null
                    if (registrations.isEmpty()) {
                        // Never replace stderr installed by another owner while we were active.
                        if (System.err === stream) System.setErr(checkNotNull(original))
                        stream = null
                        original = null
                    }
                }
            }
        }
    }

    private fun deliver(line: String) {
        val snapshot = synchronized(lock) { registrations.toList() }
        for (registration in snapshot) {
            val callback = synchronized(lock) { registration.callback } ?: continue
            try { callback(line) } catch (_: Exception) {
                // An optional UI log sink must not break stderr or the animation emitting it.
                // Do not log this failure through stderr recursively.
            }
        }
    }
}
