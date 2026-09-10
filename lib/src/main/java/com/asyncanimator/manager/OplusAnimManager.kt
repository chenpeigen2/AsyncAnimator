package com.asyncanimator.manager

import com.asyncanimator.api.PublicApi
import com.asyncanimator.control.AnimationController
import com.asyncanimator.control.DefaultAnimationController
import com.asyncanimator.control.checkControllerMainThread
import com.asyncanimator.seq.AnimationSeqHelper
import com.asyncanimator.seq.DefaultAnimationSeqHelper

/**
 * 根据本地开关提供转场控制器与序列协调器的进程内工厂。
 * 首次对象初始化时创建实际实现，关闭后读取返回新的降级实例，再启用时创建新的实际实现。
 * 配置容器的数据变更不会自动切换或重建本工厂，类型名称与现有调用接口保持不变。
 */
@PublicApi
object OplusAnimManager {

    @Volatile
    private var animationControllerImpl: AnimationController? = null
    @Volatile
    private var animationSeqHelperImpl: AnimationSeqHelper? = null

    /**
     * 在单例对象首次初始化时按能力声明建立控制器和序列实例，不是每个 getter 各自惰性构造。
     */
    init {
        if (supportInterruption()) {
            animationControllerImpl = AnimationController()
            animationSeqHelperImpl = AnimationSeqHelper()
        }
    }

    /**
     * 返回本库声明的中断能力占位值，当前固定为 true。
     * 不读取本地工厂开关、系统设置或远程配置；是否返回实际控制器应查询 interruptionEnabled。
     */
    @PublicApi
    fun supportInterruption(): Boolean = true

    /**
     * 读取当前实际控制器；关闭时每次返回新的降级实例，不缓存或共享降级监听注册表。
     */
    @PublicApi
    val animController: DefaultAnimationController
        get() = animationControllerImpl ?: DefaultAnimationController()

    /**
     * 读取当前实际序列 helper；关闭时返回新的默认实现，其延后入口仍会同步执行动作。
     */
    internal val animationSeqHelper: DefaultAnimationSeqHelper
        get() = animationSeqHelperImpl ?: DefaultAnimationSeqHelper()

    /**
     * 返回进程共享配置容器，不创建副本，也不因工厂开关切换而替换配置实例。
     */
    internal val featureHelper: AnimationFeatureHelper
        get() = AnimationFeatureHelper

    /**
     * 把 Recents 清理请求交给当前启用的实际控制器，功能关闭时无操作。
     * 不会替换控制器或序列实例，也不接管宿主动画工厂；启用时遵守控制器的主线程约束。
     */
    internal fun cleanUpRecentsAnimation() {
        animationControllerImpl?.cleanUpRecentsAnim()
    }

    /**
     * 读取实际控制器是否存在；赋值必须在主线程且由 setter 锁串行化。
     * 启用时只补建缺失实例，重复启用保持身份；关闭时先摘除引用，再销毁旧控制器并确保清理旧序列任务。
     * 工厂开关不销毁独立配置订阅，能力声明也不会因此变为 false。
     */
    @PublicApi
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
