package com.asyncanimator.interop;

import static org.junit.Assert.*;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.os.Looper;
import com.asyncanimator.anim.AsyncValueAnimator;
import com.asyncanimator.control.AnimationState;
import com.asyncanimator.control.DefaultAnimationController;
import com.asyncanimator.control.OnAnimStateChangeListener;
import com.asyncanimator.playback.NullableAnimatorListenerAdapter;
import com.asyncanimator.thread.Executors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {36}, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class JavaApiInteropTest {
    @Test public void testBooleanFactoryAndPropertyAccessorsCompileForJava() {
        ValueAnimator plain = AsyncValueAnimator.ofFloat(false, 2f, 8f);
        ValueAnimator selected = AsyncValueAnimator.ofFloat(true, 2f, 8f);
        assertFalse(plain instanceof AsyncValueAnimator);
        assertTrue(selected instanceof AsyncValueAnimator);
        AsyncValueAnimator async = (AsyncValueAnimator) selected;
        try {
            async.setExecutor(Executors.INSTANCE.getMAIN_EXECUTOR());
            assertSame(Looper.getMainLooper(), async.getExecutor().getLooper());
            assertSame(Looper.getMainLooper().getThread(), async.getExecutor().getThread());
            assertSame(Looper.getMainLooper(), async.getExecutor().getHandler().getLooper());
            async.setCurrentFraction(1f);
            assertEquals(8f, (Float) async.getAnimatedValue(), 0f);
        } finally { async.dispose(); }
    }

    @Test public void testJavaSamCanBeRetainedAndRemoved() {
        DefaultAnimationController controller = new DefaultAnimationController();
        int[] calls = {0};
        OnAnimStateChangeListener listener = (oldState, newState, task) -> calls[0]++;
        controller.addOnAnimStateChangeListener(listener);
        controller.onAnimStateChanged(AnimationState.NONE, AnimationState.OPEN, null);
        controller.removeOnAnimStateChangeListener(listener);
        controller.onAnimStateChanged(AnimationState.OPEN, AnimationState.NONE, null);
        assertEquals(1, calls[0]);
    }

    @Test public void testJavaAdapterUsesCompatibleAddAndRemoveEntryPoints() {
        AsyncValueAnimator animator = (AsyncValueAnimator) AsyncValueAnimator.ofFloat(true, 0f, 1f);
        int[] starts = {0};
        int[] ends = {0};
        NullableAnimatorListenerAdapter listener = new NullableAnimatorListenerAdapter() {
            @Override public void onAnimationStart(Animator animation) { starts[0]++; }
            @Override public void onAnimationEnd(Animator animation) { ends[0]++; }
        };
        try {
            animator.addAnimatorListener(listener);
            animator.removeAnimatorListener(listener);
            animator.addAnimatorListener(listener);
            animator.start();
            animator.end();
            assertEquals(1, starts[0]);
            assertEquals(1, ends[0]);
        } finally { animator.dispose(); }
    }
    @Test public void testFunctionCallbacksUseUnitAndDefaultSeqExecutesImmediately() {
        int[] calls = {0};
        kotlin.jvm.functions.Function0<kotlin.Unit> action = () -> {
            calls[0]++;
            return kotlin.Unit.INSTANCE;
        };
        Executors.INSTANCE.getMAIN_EXECUTOR().execute(action);
        assertEquals(1, calls[0]);
        com.asyncanimator.seq.DefaultAnimationSeqHelper helper =
                new com.asyncanimator.seq.DefaultAnimationSeqHelper();
        assertFalse(helper.delayFinishRecents(action));
        assertEquals(2, calls[0]);
        assertFalse(helper.delayFinishRecents(null));
        assertEquals(2, calls[0]);
        DefaultAnimationController controller = new DefaultAnimationController();
        assertFalse(controller.delayStartActivityIfNeed(null, null, () -> true, action));
        assertEquals(2, calls[0]); // Default Controller leaves immediate launch to its caller.
    }

    @SuppressWarnings("deprecation")
    @Test public void testSharedExecutorRejectsShutdownWithoutStoppingDelivery() {
        com.asyncanimator.thread.LooperExecutor executor = Executors.INSTANCE.getMAIN_EXECUTOR();
        assertFalse(executor.isShutdown());
        assertFalse(executor.isTerminated());
        assertThrows(UnsupportedOperationException.class, () -> executor.shutdown());
        assertThrows(UnsupportedOperationException.class, () -> executor.shutdownNow());
        assertThrows(UnsupportedOperationException.class,
                () -> executor.awaitTermination(0, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertFalse(executor.isShutdown());
        assertFalse(executor.isTerminated());
        int[] delivered = {0};
        executor.execute(() -> { delivered[0]++; return kotlin.Unit.INSTANCE; });
        assertEquals(1, delivered[0]);
        assertSame(Looper.getMainLooper(), executor.getLooper());
        assertFalse(java.util.concurrent.ExecutorService.class.isAssignableFrom(executor.getClass()));
    }
}
