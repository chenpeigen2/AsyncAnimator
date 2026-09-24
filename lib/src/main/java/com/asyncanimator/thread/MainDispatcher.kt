package com.asyncanimator.thread

import android.os.Handler
import android.os.Looper
import com.asyncanimator.api.Layer
import com.asyncanimator.api.LayerId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher

/**
 * 主线程调度设施：绑定平台主 Looper 的原生 Handler 及其协程调度器视图。
 * @property handler 主线程 Handler，用于需要同步穿过主线程消息泵的外抛路径
 * @property dispatcher 与 [handler] 绑定的协程调度器，delay 等延时经其排队
 */
@Layer(LayerId.BASIC)
internal class MainThreadScheduler(
    val handler: Handler,
    val dispatcher: CoroutineDispatcher
)

/**
 * 按平台主 Looper 解析主线程调度设施：Looper 存在时返回绑定该 Looper 的 Handler 与调度器，
 * 纯 JVM 单元测试等无主 Looper 环境返回 null，调用方据此退化为仅事件通道或按旧 Handler 语义失败。
 * 不使用 Dispatchers.Main，避免测试类加载器先经历无 Looper 环境后缓存缺失主调度器、污染同进程其他用例。
 * 本探测不创建线程或 Looper，也不触发实际派发；解析结果按调用点缓存，不随 Looper 生命周期刷新。
 */
internal fun mainThreadScheduler(): MainThreadScheduler? = runCatching {
    val handler = Handler(checkNotNull(Looper.getMainLooper()))
    MainThreadScheduler(handler, handler.asCoroutineDispatcher())
}.getOrNull()
