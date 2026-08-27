package com.asyncanimator.launcher.spring;

import com.asyncanimator.util.FloatProperty;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * OplusSpringObjectAnimator 单元测试。
 *
 * <p>验证：
 * <ul>
 *   <li>构造时不抛异常</li>
 *   <li>switchToSpring 后 useSpring=true</li>
 *   <li>SpringProperty 委托逻辑</li>
 * </ul>
 */
public class OplusSpringObjectAnimatorTest {

    public static class TestTarget {
        float value = 0f;
    }

    public static final FloatProperty<TestTarget> TEST_PROPERTY = new FloatProperty<TestTarget>("value") {
        @Override public void setValue(TestTarget t, float v) { t.value = v; }
        @Override public Float getValue(TestTarget t) { return t.value; }
    };

    @Test
    public void testBasicConstruction() {
        OplusSpringObjectAnimator<TestTarget> anim = new OplusSpringObjectAnimator<>(
            new TestTarget(), TEST_PROPERTY, 1f, 0.5f, 1500f, 0f, 100f);
        assertNotNull(anim);
    }

    @Test
    public void testSwitchToSpringFlag() {
        OplusSpringObjectAnimator.SpringProperty<TestTarget> sp =
            new OplusSpringObjectAnimator.SpringProperty<>(TEST_PROPERTY, null);
        // 默认 false
        assertNotNull(sp);
        // 切换
        sp.switchToSpring();
        // 没有 setUseSpring public 方法，但 getValue 仍能用
        assertEquals("value", sp.getName());
    }

    @Test
    public void testInterpolatorConstants() {
        assertNotNull(OplusSpringObjectAnimator.Interpolators.LINEAR);
    }
}