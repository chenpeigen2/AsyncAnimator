package com.asyncanimator.manager

import java.util.Collections
import com.asyncanimator.core.LogUtils
import com.asyncanimator.thread.Executors
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * 进程内的动画配置及订阅容器，通过本地更新入口模拟配置变化。
 * 标量支持单字段可见性，批量 snapshot 使用同一锁；列表以不可修改的整体快照替换。
 * 不接入远程服务，不自动控制动画线程，也不自动重建管理器工厂。
 */
object AnimationFeatureHelper {

    private val lock = Any()
    /**
     * 单次订阅的可失效引用，由配置锁保护；动作置空后旧通知即使仍持有条目也会跳过它。
     */
    private class Registration(var callback: (() -> Unit)?)
    private val registrations = mutableListOf<Registration>()
    @Volatile private var adaptiveAnimationEnabled = false

    /**
     * 一批配置的只读属性容器，包含七个标量、两个禁用列表及自适应标志。
     * 由 snapshot() 返回时列表已不可修改；直接构造或 copy 并传入自有列表时，调用方仍需保证列表不可变。
     * @property asyncEnable 异步配置值，负值表示未配置，零可表示显式禁用。
     * @property rtUnlockEnable 实时解锁相关配置值，由宿主解释。
     * @property multiAppBlockEnable 多应用阻断相关配置值，由宿主解释。
     * @property iconBlurEnable 图标模糊配置值。
     * @property onePxEnable 单像素策略配置值。
     * @property interruptThreshold 中断阈值，自适应策略开启时配置入口将其设为 1。
     * @property limtSize 宿主提供的大小限制数值，容器不解释单位。
     * @property onePxPkgDisableList 按包名禁用单像素策略的快照列表。
     * @property onePxCardDisableList 按卡片标识禁用单像素策略的快照列表。
     * @property adaptiveAnimationEnabled 宿主明确提交的自适应动画策略。
     */
    data class Snapshot(
        val asyncEnable: Int, val rtUnlockEnable: Int, val multiAppBlockEnable: Int,
        val iconBlurEnable: Int, val onePxEnable: Int, val interruptThreshold: Float,
        val limtSize: Int, val onePxPkgDisableList: List<String>, val onePxCardDisableList: List<Int>,
        val adaptiveAnimationEnabled: Boolean
    ) {
        /**
         * 从该快照自身的自适应标志推导半径动画开关，不读取之后变化的全局配置。
         */
        val radiusAnimationEnable: Boolean get() = !adaptiveAnimationEnabled
    }

    /**
     * 在配置锁内取得全部标量、列表及自适应标志的一致快照。
     * 经更新入口发布的列表已经防御复制并不可修改，因此可共享其引用；连续读单独属性不能替代这一原子快照。
     */
    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(asyncEnable, rtUnlockEnable, multiAppBlockEnable, iconBlurEnable, onePxEnable,
            interruptThreshold, limtSize, onePxPkgDisableSnapshot, onePxCardDisableSnapshot,
            adaptiveAnimationEnabled)
    }

    /**
     * 登记一个独立订阅并返回可重复关闭的句柄，同一 callback 多次登记也拥有各自生命周期。
     * 通知总是排入主线程普通消息，回调应读取最新 snapshot，而非假设对应历史更新负载。
     * 关闭句柄会取消未开始的该订阅通知；已经取走并开始执行的回调无法撤回，业务执行时不持有配置锁。
     */
    fun addRemoteUpdateListener(callback: () -> Unit): AutoCloseable {
        val registration = Registration(callback)
        synchronized(lock) { registrations.add(registration) }
        return AutoCloseable {
            synchronized(lock) {
                registration.callback = null
                registrations.remove(registration)
            }
        }
    }

    /**
     * 在配置锁内按引用身份移除同一 callback 的全部注册，并清空其可调用引用。
     * 已经排队但尚未取得动作的通知将跳过这些条目；仅想释放一次注册时应关闭对应句柄。
     */
    fun removeRemoteUpdateListener(callback: () -> Unit) = synchronized(lock) {
        registrations.removeAll {
            if (it.callback === callback) { it.callback = null; true } else false
        }
        Unit
    }

    /**
     * 清空所有订阅及其动作引用，供全局配置所有者结束时释放监听。
     * 保留已发布配置并允许以后重新注册；单个页面应关闭自己的句柄，而非调用此全局清理入口。
     */
    fun onDestroy() = synchronized(lock) {
        registrations.forEach { it.callback = null }
        registrations.clear()
    }

    /**
     * 在配置锁内执行更新并复制当时的订阅列表，然后向主线程排入一次通知任务。
     * 交付前逐项重新读取动作引用，已关闭订阅被跳过；新登记订阅不会收到这次旧通知。
     * 业务 Exception 记录后继续后续监听，Error 不被吞掉；回调在锁外运行，可安全重入配置接口。
     */
    private fun publish(update: () -> Unit) {
        val listeners = synchronized(lock) {
            update()
            registrations.toList()
        }
        if (listeners.isNotEmpty()) Executors.MAIN_EXECUTOR.post {
            for (registration in listeners) {
                val callback = synchronized(lock) { registration.callback } ?: continue
                try { callback() }
                catch (error: Exception) {
                    LogUtils.i("AnimationFeatureHelper", "update listener failed: ${error.message}")
                }
            }
        }
    }

    /**
     * 更新宿主提供的自适应策略并发布通知，开启时把中断阈值强制设为 1。
     * 关闭策略不会恢复此前阈值，需后续配置更新显式提供；重复设置也会触发通知，不自动查询系统策略。
     */
    fun setAdaptiveAnimationEnabled(enabled: Boolean) = publish {
        adaptiveAnimationEnabled = enabled
        if (enabled) interruptThreshold = 1.0f
    }

    /**
     * 返回当前自适应策略的反值，表示是否允许半径动画。
     * 只读取已发布标志，不修改阈值或通知监听器；需要与其他配置一致读取时使用 snapshot。
     */
    fun getRadiusAnimationEnable(): Boolean = !adaptiveAnimationEnabled

    var asyncEnable by SyncedVar(lock, -1)
        private set
    var rtUnlockEnable by SyncedVar(lock, -1)
        private set
    var multiAppBlockEnable by SyncedVar(lock, -1)
        private set
    var iconBlurEnable by SyncedVar(lock, -1)
        private set
    var onePxEnable by SyncedVar(lock, -1)
        private set
    var interruptThreshold by SyncedVar(lock, 1.0f)
        private set
    var limtSize by SyncedVar(lock, -1)
        private set

    @Volatile
    private var onePxPkgDisableSnapshot: List<String> = emptyList()

    @Volatile
    private var onePxCardDisableSnapshot: List<Int> = emptyList()

    /**
     * 判断 asyncEnable 是否为非负值；显式禁用的零也属于已配置，不等同于异步功能已开启。
     */
    val isAsyncConfigured: Boolean get() = asyncEnable >= 0

    /**
     * 读取最近发布的包名禁用列表；返回只读快照，不允许通过强制转换就地修改。
     */
    val onePxPkgDisableList: List<String> get() = onePxPkgDisableSnapshot

    /**
     * 读取最近发布的卡片禁用列表；后续更新整体替换引用，不会修改读者已持有的旧列表。
     */
    val onePxCardDisableList: List<Int> get() = onePxCardDisableSnapshot

    /**
     * 批量发布七个标量和两个可选禁用列表，仅模拟配置输入，不连接远程服务。
     * 先在配置锁外复制两个非空列表，全部复制成功后才写入状态；复制失败不改配置也不通知。
     * null 列表表示保留，空列表表示清空；复制期间调用方不可并发修改输入集合。
     * 自适应策略开启时 threshold 被覆盖为 1，其余标量不在此入口校验取值范围。
     */
    fun simulateRemoteUpdate(async: Int, rtUnlock: Int, multiApp: Int, iconBlur: Int,
                             onePx: Int, threshold: Float, limtSize: Int,
                             onePxPkgDisableList: List<String>?, onePxCardDisableList: List<Int>?) {

        val packages = onePxPkgDisableList?.let { Collections.unmodifiableList(ArrayList(it)) }
        val cards = onePxCardDisableList?.let { Collections.unmodifiableList(ArrayList(it)) }
        publish {
            updateScalars(async, rtUnlock, multiApp, iconBlur, threshold, limtSize)
            this.onePxEnable = onePx
            if (packages != null) onePxPkgDisableSnapshot = packages
            if (cards != null) onePxCardDisableSnapshot = cards
        }
    }

    /**
     * 发布六个标量配置，保留 onePxEnable 和两个禁用列表的现值。
     * 使用与完整更新相同的锁和通知流程，不通过读取旧列表再回写来拼接配置，避免覆盖并发更新。
     * 自适应策略开启时忽略传入 threshold 并维持阈值 1。
     */
    fun simulateRemoteUpdate(async: Int, rtUnlock: Int, multiApp: Int, iconBlur: Int,
                             threshold: Float, limtSize: Int) = publish {
        updateScalars(async, rtUnlock, multiApp, iconBlur, threshold, limtSize)
    }

    /**
     * 在调用方已经持有配置锁时更新六个标量，并根据自适应策略选择实际阈值。
     * 不处理 onePxEnable 或列表，不自行发布通知，避免一次外层更新重复回调。
     */
    private fun updateScalars(async: Int, rtUnlock: Int, multiApp: Int, iconBlur: Int,
                              threshold: Float, limtSize: Int) {
        asyncEnable = async
        rtUnlockEnable = rtUnlock
        multiAppBlockEnable = multiApp
        iconBlurEnable = iconBlur
        interruptThreshold = if (adaptiveAnimationEnabled) 1.0f else threshold
        this.limtSize = limtSize
    }

    /**
     * 用于标量配置的属性委托：读取依靠 volatile，写入与外层批量更新共用同一对象锁。
     * @param lock 串行化该配置族写入的共享锁。
     * @param initial 委托首次读取的值，不触发通知或远程查询。
     */
    private class SyncedVar<T>(private val lock: Any, initial: T) : ReadWriteProperty<Any?, T> {
        @Volatile
        private var value = initial

        /**
         * 返回该委托当前通过 volatile 发布的值，不获取写锁。
         * 单个属性读取可见最新写入，但多个委托的连续读取不保证来自同一批更新；thisRef/property 不参与取值。
         */
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = value

        /**
         * 在与外层批量更新共用的锁内替换委托值，再通过 volatile 向读者发布。
         * 不做输入校验或通知；外层 publish 负责组织一致批次和订阅交付。
         */
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            synchronized(lock) { this.value = value }
        }
    }
}
