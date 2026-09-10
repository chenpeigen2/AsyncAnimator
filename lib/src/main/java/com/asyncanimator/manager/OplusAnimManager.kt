package com.asyncanimator.manager

import com.asyncanimator.control.checkControllerMainThread
import com.asyncanimator.control.AnimationController
import com.asyncanimator.control.DefaultAnimationController
import com.asyncanimator.seq.AnimationSeqHelper
import com.asyncanimator.seq.DefaultAnimationSeqHelper

/**
 * OplusAnimManager — feature flag 驱动的工厂单例。
 *
 * 对应 `docs/review/03-controller-manager-seq.md`。首次访问 object 时创建 Controller/Seq 两个实现，
 * [supportInterruption] 是固定 true 的能力占位；[interruptionEnabled] 才是本地工厂开关。
 * 关闭后新查询返回 Default（并非所有方法空操作），再次启用创建新的实现实例。
 * FeatureHelper 的数据更新不自动重建本工厂。
 *
 * 业务统一通过 `OplusAnimManager.animController` 等获取实例，
 * 不需要知道当前返回的是 Default 还是 Impl。
 */
object OplusAnimManager {

    // Eager within object initialization, not per-helper lazy/observable delegates.
    @Volatile
    private var animationControllerImpl: AnimationController? = null
    @Volatile
    private var animationSeqHelperImpl: AnimationSeqHelper? = null

    init {
        if (supportInterruption()) {
            animationControllerImpl = AnimationController()
            animationSeqHelperImpl = AnimationSeqHelper()
        }
    }

    /**
     * Capability placeholder, always true; not a query of the local factory toggle.
     * OEM combines LauncherAnimConfig, shell transitions and AppFeatureUtils. This library
     * does not discover those ROM settings; use interruptionEnabled for its local switch.
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

    /** 重置为 no-op（feature toggle 关闭）。demo 用来演示降级。切换限主线程；关闭时释放旧实例持有的任务。 */
    @set:Synchronized
    var interruptionEnabled: Boolean
        get() = animationControllerImpl != null
        set(enabled) {
            checkControllerMainThread()
            if (enabled) {
                if (animationControllerImpl == null) animationControllerImpl = AnimationController()
                if (animationSeqHelperImpl == null) animationSeqHelperImpl = AnimationSeqHelper()
            } else {
                val oldController = animationControllerImpl
                val oldSeq = animationSeqHelperImpl
                animationControllerImpl = null
                animationSeqHelperImpl = null
                try {
                    oldController?.destroy()
                } finally {
                    oldSeq?.clearFinishRecentsRunnable()
                }
            }
        }
}
