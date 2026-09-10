package com.asyncanimator.control

import com.asyncanimator.api.PublicApi

/**
 * 调用方提交给控制器的应用退出场景快照，不自行查询设备导航或动画配置。
 * 字段均为不可变值，copy 可构造修改后的独立快照；场景对象本身不注册超时或执行动画。
 * @property threeButtonNavigation 是否使用三键导航，参与退出场景的联合条件判断。
 * @property landscape 是否处于横屏，需由调用方提供当前事实。
 * @property adaptiveLowAnimation 是否启用适配的低动画策略。
 * @property largeDisplayInLargeMode 是否处于大屏的大显示模式，默认 false；为 true 时排除特殊退出场景。
 */
@PublicApi
data class AppExitScene @PublicApi constructor(
    /** 是否使用三键导航，参与退出场景的联合条件判断。 */
    @property:PublicApi
    val threeButtonNavigation: Boolean,
    /** 是否处于横屏，需由调用方提供当前事实。 */
    @property:PublicApi
    val landscape: Boolean,
    /** 是否启用适配的低动画策略。 */
    @property:PublicApi
    val adaptiveLowAnimation: Boolean,
    /** 是否处于大屏的大显示模式，默认 false；为 true 时排除特殊退出场景。 */
    @property:PublicApi
    val largeDisplayInLargeMode: Boolean = false
)

/**
 * 控制器使用的手势场景快照，默认布尔条件均关闭，两个包名默认未提供。
 * 通过 copy 改变输入不修改旧快照；传递本对象表示开始或更新手势，结束由控制器接口的 null 输入表达。
 * @property landscape 当前手势是否发生于横屏。
 * @property splitScreen 是否处于分屏手势场景。
 * @property recentContinuation 是否为最近任务的续行动画场景。
 * @property tablet 当前设备是否按平板处理，影响横屏场景分支。
 * @property homeAndOverviewSame 主屏与概览是否共用宿主，影响手势结束后的等待场景。
 * @property baseActivityPackage 基础 Activity 包名，提供时优先用于上滑应用标识。
 * @property topActivityPackage 顶层 Activity 包名，仅在基础包名为 null 时作为回退值。
 */
@PublicApi
data class GestureScene @PublicApi constructor(
    /** 当前手势是否发生于横屏。 */
    @property:PublicApi
    val landscape: Boolean = false,
    /** 是否处于分屏手势场景。 */
    @property:PublicApi
    val splitScreen: Boolean = false,
    /** 是否为最近任务的续行动画场景。 */
    @property:PublicApi
    val recentContinuation: Boolean = false,
    /** 当前设备是否按平板处理，影响横屏场景分支。 */
    @property:PublicApi
    val tablet: Boolean = false,
    /** 主屏与概览是否共用宿主，影响手势结束后的等待场景。 */
    @property:PublicApi
    val homeAndOverviewSame: Boolean = false,
    /** 基础 Activity 包名，提供时优先用于上滑应用标识。 */
    @property:PublicApi
    val baseActivityPackage: String? = null,
    /** 顶层 Activity 包名，仅在基础包名为 null 时作为回退值。 */
    @property:PublicApi
    val topActivityPackage: String? = null
)
