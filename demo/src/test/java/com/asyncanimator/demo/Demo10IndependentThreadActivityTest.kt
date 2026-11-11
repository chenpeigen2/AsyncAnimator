package com.asyncanimator.demo

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Demo10IndependentThreadActivityTest {

    @Test
    fun testDriverSwitchStopAndDestroy() {
        val controller = Robolectric.buildActivity(Demo10IndependentThreadActivity::class.java).setup()
        val activity = controller.get()
        try {
            val mainLooper = shadowOf(Looper.getMainLooper())
            mainLooper.idle()

            clickButton(activity, "启动：主线程驱动")
            mainLooper.idle()
            clickButton(activity, "启动：launcher.anim 驱动")
            mainLooper.idle()
            clickButton(activity, "停止")
            mainLooper.idle()
        } finally {
            controller.destroy()
        }
    }

    private fun clickButton(activity: Demo10IndependentThreadActivity, text: String) {
        val button = requireNotNull(findButton(activity.window.decorView, text)) { "Button not found: $text" }
        assertTrue(button.performClick())
    }

    private fun findButton(root: View, text: String): Button? {
        if (root is Button && root.text.toString() == text) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findButton(root.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }
}
