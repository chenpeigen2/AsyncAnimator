package com.asyncanimator.playback

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class AnimatorPlaybackControllerTest {
    @Test
    fun testDispatchVisitsNestedTreeInPreOrderForEveryEvent() {
        val root = AnimatorSet()
        val nested = AnimatorSet()
        val leaf = ValueAnimator.ofFloat(0f, 1f)
        val sibling = ValueAnimator.ofFloat(0f, 1f)
        root.playTogether(nested, sibling)
        nested.playTogether(leaf)
        val calls = mutableListOf<String>()
        for ((name, anim) in listOf("root" to root, "nested" to nested, "leaf" to leaf, "sibling" to sibling)) {
            val listener = object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) { calls.add("start:$name") }
                override fun onAnimationCancel(animation: Animator) { calls.add("cancel:$name") }
                override fun onAnimationEnd(animation: Animator) { calls.add("end:$name") }
            }
            anim.addListener(listener)
        }
        run {
            val controller = AnimatorPlaybackController(root, 100, emptyList())
            controller.dispatchOnStart()
            controller.dispatchOnCancel()
            controller.dispatchOnEnd()
        }
        assertEquals(listOf("start", "cancel", "end").flatMap { event ->
            listOf("root", "nested", "leaf", "sibling").map { "$event:$it" }
        }, calls)
    }

    @Test
    fun testListenerCanRemoveItselfDuringDispatch() {
        val root = ValueAnimator.ofFloat(0f, 1f)
        val listeners = arrayListOf<Animator.AnimatorListener>()
        val calls = mutableListOf<String>()
        listeners.add(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                calls.add("first")
                animation.removeListener(this)
            }
        })
        listeners.add(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { calls.add("second") }
        })
        listeners.forEach(root::addListener)
        run {
            AnimatorPlaybackController(root, 100, emptyList()).dispatchOnEnd()
        }
        assertEquals(listOf("first", "second"), calls)
    }
}
