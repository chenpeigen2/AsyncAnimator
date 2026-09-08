package com.asyncanimator.launcher.controller

/**
 * 任务状态变化超时监听器接口。
 *
 * 对应分析文档 §6.8.5。三种 listener 都实现此接口：
 *
 *  - mSpecialSceneExitTimeOutListener（横屏/分屏退出，1500/2500ms）
 *  - mTransitionFinishTimeOutListener（特殊应用，1500ms）
 *  - mOverviewContinuationTimeOutListener（swipe-to-recent 续行，100ms）
 */
fun interface TaskStateChangeTimeOutListener {

    enum class Type {
        ON_LAND_SCAPE_SCENE_EXIT,
        ON_TRANSITION_FINISH,
        ON_APP_TO_OVERVIEW_CONTINUATION
    }

    fun onTimeOut(type: Type, duration: Long)
}
