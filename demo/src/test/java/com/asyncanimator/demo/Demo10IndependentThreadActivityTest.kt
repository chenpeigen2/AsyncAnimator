package com.asyncanimator.demo

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/**
 * Demo 10 独立动画线程 Activity 的驱动切换与停止流程测试。
 *
 * <p>舞台 SceneClock 每帧都会自我续帧，Robolectric 默认在 postFrameCallback 时立即同步派发 vsync，
 * 直接调用 idle() 会让帧回调无限循环。测试改用异步 vsync：帧只在系统时钟推进到下一帧时刻时派发，
 * 配合固定帧数的时间窗口，保证测试可以稳定推进若干帧后退出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Demo10IndependentThreadActivityTest {

    @Before
    fun setUp() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @Test
    fun testDriverSwitchStopAndDestroy() {
        val controller = Robolectric.buildActivity(Demo10IndependentThreadActivity::class.java).setup()
        val activity = controller.get()
        try {
            val mainLooper = shadowOf(Looper.getMainLooper())
            idleFrames(mainLooper)

            clickButton(activity, "启动：主线程驱动")
            idleFrames(mainLooper)
            clickButton(activity, "启动：launcher.anim 驱动")
            idleFrames(mainLooper)
            clickButton(activity, "停止")
            idleFrames(mainLooper)
        } finally {
            controller.destroy()
        }
    }

    /** 推进固定帧数的虚拟时间，避免无限自续帧的动画让 idle() 永不返回。 */
    private fun idleFrames(looper: ShadowLooper) {
        repeat(FRAME_COUNT) { looper.idleFor(Duration.ofMillis(FRAME_INTERVAL_MS)) }
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

    private companion object {
        /** 每次推进的帧数：足够覆盖启动、切换与停止三类操作。 */
        const val FRAME_COUNT = 3

        /** 每帧推进的虚拟时间间隔（毫秒）。 */
        const val FRAME_INTERVAL_MS = 16L
    }
}
