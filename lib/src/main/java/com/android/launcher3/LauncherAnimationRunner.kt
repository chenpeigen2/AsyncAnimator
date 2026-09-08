package com.android.launcher3

/**
 * LauncherAnimationRunner — 远程转场 runner 的最小移植桩。
 *
 * 原 OPPO/Launcher3 代码中 `com.android.launcher3.LauncherAnimationRunner`
 * 承载 RemoteAnimation 的 Binder 回调（startAnimation(RemoteAnimationTarget[])）。
 * 本移植工程不需要真实 Binder 通道，仅保留
 * [RemoteAnimationTarget] 类型壳，供
 * `com.asyncanimator.launcher.controller.DefaultAnimationController.appLaunchAnimStartOrEnd`
 * 等签名使用（保持与原厂代码形状一致）。
 */
abstract class LauncherAnimationRunner {

    /** 远程动画目标的最小描述（原类含 leash/taskId 等，demo 只需类型存在）。 */
    class RemoteAnimationTarget(
        var taskId: Int = 0,
        var leash: Any? = null
    )
}
