package com.asyncanimator.launcher.manager;

import com.asyncanimator.launcher.controller.AnimationController;
import com.asyncanimator.launcher.controller.DefaultAnimationController;
import com.asyncanimator.launcher.feature.AnimationFeatureHelper;
import com.asyncanimator.launcher.seq.AnimationSeqHelper;
import com.asyncanimator.launcher.seq.DefaultAnimationSeqHelper;

/**
 * OplusAnimManager — feature flag 驱动的工厂单例。
 *
 * <p>对应分析文档 §6.10。默认所有 helper 返回 {@code Default*BaseClass}（no-op），
 * 当 {@link #supportInterruption()} 为 true 时切换到 {@code Impl}。
 *
 * <p>业务统一通过 {@code OplusAnimManager.getInstance().getAnimController()} 等获取实例，
 * 不需要知道当前返回的是 Default 还是 Impl。
 */
public class OplusAnimManager {

    private static final OplusAnimManager INSTANCE = new OplusAnimManager();

    // 简化版：直接 lazy 创建（生产环境应该是 t4.b 类型懒加载）
    private AnimationController animationController;
    private AnimationSeqHelper animationSeqHelper;

    private OplusAnimManager() {
        if (supportInterruption()) {
            animationController = new AnimationController();
            animationSeqHelper = new AnimationSeqHelper();
        } else {
            animationController = null;
            animationSeqHelper = null;
        }
    }

    public static OplusAnimManager getInstance() {
        return INSTANCE;
    }

    /**
     * feature 开关。简化：默认 true。
     * 生产代码会检查 LauncherAnimConfig / TaskAnimationManager.ENABLE_SHELL_TRANSITIONS
     * 等多个条件。
     */
    public boolean supportInterruption() {
        // demo 默认打开所有功能
        return true;
    }

    public DefaultAnimationController getAnimController() {
        return animationController != null ? animationController : new DefaultAnimationController();
    }

    public DefaultAnimationSeqHelper getAnimationSeqHelper() {
        return animationSeqHelper != null ? animationSeqHelper : new DefaultAnimationSeqHelper();
    }

    public AnimationFeatureHelper getFeatureHelper() {
        return AnimationFeatureHelper.getInstance();
    }

    public void cleanUpRecentsAnimation() {
        if (animationController != null) animationController.cleanUpRecentsAnim();
    }

    /** 重置为 no-op（feature toggle 关闭）。demo 用来演示降级。 */
    public void setInterruptionEnabled(boolean enabled) {
        if (enabled) {
            if (animationController == null) animationController = new AnimationController();
            if (animationSeqHelper == null) animationSeqHelper = new AnimationSeqHelper();
        } else {
            animationController = null;
            animationSeqHelper = null;
        }
    }
}