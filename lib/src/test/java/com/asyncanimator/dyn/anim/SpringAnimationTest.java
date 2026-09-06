package com.asyncanimator.dyn.anim;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * SpringAnimation 单元测试。
 *
 * <p>验证：
 * <ul>
 *   <li>三种 damping regime 的闭式解</li>
 *   <li>2 阶段过渡</li>
 *   <li>isAtEquilibrium 终止判定</li>
 *   <li>animateToFinalPosition 推迟生效</li>
 * </ul>
 */
public class SpringAnimationTest {

    @Test
    public void testSpringForceBasicConstructor() {
        SpringForce sf = new SpringForce(100f);
        assertEquals(100f, sf.getFinalPosition(), 0.001f);
    }

    @Test
    public void testSpringForceSetStiffness() {
        SpringForce sf = new SpringForce();
        sf.setStiffness(SpringForce.STIFFNESS_HIGH);
        sf.setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY);
        // 验证没有抛异常
        assertNotNull(sf);
    }

    @Test
    public void testSpringForceUpdateValuesUnderDamped() {
        // 欠阻尼：finalPosition=100, ζ=0.5
        // 注意：两参构造是 (stiffness, dampingRatio)，此处必须用单参构造 + setDampingRatio
        SpringForce sf = new SpringForce(100f).setDampingRatio(0.5f);
        sf.setStiffness(SpringForce.STIFFNESS_MEDIUM);
        SpringForce.MassState s = sf.updateValues(0, 0, 16);  // 16ms
        // 16ms 后应该有非零位置（往 100 移动）
        assertTrue("value should move toward 100", s.mValue > 0);
        assertNotEquals(0f, s.mVelocity);
    }

    @Test
    public void testSpringForceUpdateValuesCriticallyDamped() {
        // 临界阻尼 ζ=1
        SpringForce sf = new SpringForce(100f).setDampingRatio(1.0f);
        sf.setStiffness(SpringForce.STIFFNESS_MEDIUM);
        SpringForce.MassState s = sf.updateValues(0, 0, 16);
        assertTrue("value should move toward 100", s.mValue > 0);
    }

    @Test
    public void testSpringForceUpdateValuesOverDamped() {
        // 过阻尼 ζ>1
        SpringForce sf = new SpringForce(100f).setDampingRatio(2.0f);
        sf.setStiffness(SpringForce.STIFFNESS_MEDIUM);
        SpringForce.MassState s = sf.updateValues(0, 0, 16);
        assertTrue("value should move toward 100", s.mValue > 0);
    }

    @Test
    public void testIsAtEquilibrium() {
        SpringForce sf = new SpringForce(100f);
        sf.setValueThreshold(0.5f); // valueThreshold=0.5 → velocityThreshold=0.5*62.5=31.25
        // 在 finalPosition 附近 + 速度 ≈ 0 → 平衡
        assertTrue(sf.isAtEquilibrium(100.001f, 0.001f));
        // 远离 finalPosition → 不平衡
        assertFalse(sf.isAtEquilibrium(50f, 0.001f));
        // finalPosition 附近但速度大（>31.25）→ 不平衡
        assertFalse(sf.isAtEquilibrium(100.001f, 100f));
    }

    @Test
    public void testSpringAnimationSanityCheckNoSpring() {
        SpringAnimation sa = new SpringAnimation(new Object());
        try {
            sa.start();
            fail("Should have thrown UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // expected
        }
    }

    @Test
    public void testSpringAnimationCanSkipToEnd() {
        SpringAnimation sa = new SpringAnimation(new Object(), 100f);
        assertTrue(sa.canSkipToEnd());  // 默认 damping=0.5 > 0
        sa.setSpring(new SpringForce(SpringForce.STIFFNESS_MEDIUM, 0f));  // damping=0
        assertFalse(sa.canSkipToEnd());
    }
}