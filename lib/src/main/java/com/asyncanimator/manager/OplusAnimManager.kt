package com.asyncanimator.manager

import com.asyncanimator.control.AnimationController
import com.asyncanimator.control.DefaultAnimationController
import com.asyncanimator.seq.AnimationSeqHelper
import com.asyncanimator.seq.DefaultAnimationSeqHelper

/**
 * OplusAnimManager — feature flag 驱动的工厂单例。
 *
 * 对应 `docs/review/03-controller-manager-seq.md`。默认所有 helper 返回 `Default*BaseClass`（no-op），
 * 当 [supportInterruption] 为 true 时切换到 `Impl`。
 *
 * 业务统一通过 `OplusAnimManager.animController` 等获取实例，
 * 不需要知道当前返回的是 Default 还是 Impl。
 */
object OplusAnimManager {

    // 简化版：直接 lazy 创建（生产环境应该是 t4.b 类型懒加载）
    private var animationControllerImpl: AnimationController? = null
    private var animationSeqHelperImpl: AnimationSeqHelper? = null

    init {
        if (supportInterruption()) {
            animationControllerImpl = AnimationController()
            animationSeqHelperImpl = AnimationSeqHelper()
        }
    }

    /**
     * feature 开关。简化：默认 true。
     * 生产代码会检查 LauncherAnimConfig / TaskAnimationManager.ENABLE_SHELL_TRANSITIONS
     * 等多个条件。
     */
    fun supportInterruption(): Boolean = true

    val animController: DefaultAnimationController
        get() = animationControllerImpl ?: DefaultAnimationController()

    internal val animationSeqHelper: DefaultAnimationSeqHelper
        get() = animationSeqHelperImpl ?: DefaultAnimationSeqHelper()

    internal val featureHelper: AnimationFeatureHelper
        get() = AnimationFeatureHelper

    internal fun cleanUpRecentsAnimation() {
        animationControllerImpl?.cleanUpRecentsAnim()
    }

    /** 重置为 no-op（feature toggle 关闭）。demo 用来演示降级。setter 加锁防并发切换 race。 */
    @set:Synchronized
    var interruptionEnabled: Boolean
        get() = animationControllerImpl != null
        set(enabled) {
            if (enabled) {
                if (animationControllerImpl == null) animationControllerImpl = AnimationController()
                if (animationSeqHelperImpl == null) animationSeqHelperImpl = AnimationSeqHelper()
            } else {
                animationControllerImpl = null
                animationSeqHelperImpl = null
            }
        }
}
