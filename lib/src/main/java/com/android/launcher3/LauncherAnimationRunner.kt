package com.android.launcher3

/**
 * LauncherAnimationRunner — 远程转场 runner 的最小移植桩。
 *
 * 原 OPPO/Launcher3 代码中 `com.android.launcher3.LauncherAnimationRunner`
 * 承载 RemoteAnimation 的 Binder 回调（startAnimation(RemoteAnimationTarget[])）。
 * 本移植工程不需要真实 Binder 通道，仅保留
 * [RemoteAnimationTarget] 类型壳，供
 * `com.asyncanimator.control.DefaultAnimationController.appLaunchAnimStartOrEnd`
 * 等签名使用。嵌套类不是平台 android.view.RemoteAnimationTarget，不能传入真实平台数组
 * 或把 Any leash 当 SurfaceControl 操作；Controller 当前不读取目标内容。
 */
abstract class LauncherAnimationRunner {

    /** Opaque local descriptor, not Parcelable or a platform remote-animation target. */
    class RemoteAnimationTarget(
        var taskId: Int = 0,
        var leash: Any? = null
    )
}
