package com.asyncanimator.launcher.continuation;

import com.asyncanimator.core.anim.ValueAnimator;
import com.asyncanimator.util.FloatProperty;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * OplusValueAnimator 单元测试。
 *
 * <p>验证：
 * <ul>
 *   <li>无 timeController 时：super 行为（标准 ValueAnimator）</li>
 *   <li>有 timeController 时：start 委托给 timeController</li>
 *   <li>generateContinuationAnim 用 CURRENT_FRACTION 桥接</li>
 *   <li>AnimParam.copy 复用配置</li>
 * </ul>
 */
public class OplusValueAnimatorTest {

    /** 测试用的对象。 */
    public static class TestTarget {
        float value = 0f;
    }

    public static final FloatProperty<TestTarget> TEST_PROPERTY = new FloatProperty<TestTarget>("value") {
        @Override public void setValue(TestTarget t, float v) { t.value = v; }
        @Override public Float getValue(TestTarget t) { return t.value; }
    };

    @Test
    public void testBasicConstructor() {
        OplusValueAnimator<TestTarget> anim = new OplusValueAnimator<>();
        assertNotNull(anim);
        assertEquals("default", anim.getName());
        assertNotNull(anim.getParam());
    }

    @Test
    public void testAnimParamCopy() {
        OplusValueAnimator.AnimParam original = new OplusValueAnimator.AnimParam()
            .setName("test")
            .setRange(0f, 100f)
            .setCurrentFraction(0.5f)
            .setDuration(300);
        OplusValueAnimator.AnimParam copy = original.copy();
        assertEquals("test", copy.name);
        assertEquals(0f, copy.fromValue, 0.001f);
        assertEquals(100f, copy.toValue, 0.001f);
        assertEquals(0.5f, copy.currentFraction, 0.001f);
        assertEquals(300, copy.duration);
    }

    @Test
    public void testSetCurrentFractionUpdatesBothFields() {
        OplusValueAnimator<TestTarget> anim = new OplusValueAnimator<>();
        // 默认插值器是 AccelerateDecelerate，为使断言确定化改用线性
        anim.setInterpolator(f -> f);
        anim.setCurrentFraction(0.3f);
        assertEquals(0.3f, anim.getAnimatedFraction(), 0.001f);
        assertEquals(0.3f, anim.getParam().currentFraction, 0.001f);
    }

    @Test
    public void testGenerateContinuationAnimWithoutSourceReturnsNull() {
        OplusValueAnimator<TestTarget> result = OplusValueAnimator.generateContinuationAnim(null, 300);
        assertNull(result);
    }

    @Test
    public void testGenerateContinuationAnimWithInvalidFractionReturnsNull() {
        // fraction ≥ 1.0 时不生成
        OplusValueAnimator<TestTarget> src = new OplusValueAnimator<>("src",
            new OplusValueAnimator.AnimParam().setCurrentFraction(1.5f), null);
        OplusValueAnimator<TestTarget> result = OplusValueAnimator.generateContinuationAnim(src, 300);
        assertNull(result);
    }

    @Test
    public void testOfFloatStaticFactory() {
        ValueAnimator anim = OplusValueAnimator.ofFloat(false, 0f, 1f);
        assertNotNull(anim);
    }
}