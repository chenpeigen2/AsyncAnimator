package com.asyncanimator.launcher.controller;

/**
 * 任务状态变化超时监听器接口。
 *
 * <p>对应分析文档 §6.8.5。三种 listener 都实现此接口：
 * <ul>
 *   <li>mSpecialSceneExitTimeOutListener（横屏/分屏退出，1500/2500ms）</li>
 *   <li>mTransitionFinishTimeOutListener（特殊应用，1500ms）</li>
 *   <li>mOverviewContinuationTimeOutListener（swipe-to-recent 续行，100ms）</li>
 * </ul>
 */
public interface TaskStateChangeTimeOutListener {

    enum Type {
        ON_LAND_SCAPE_SCENE_EXIT,
        ON_TRANSITION_FINISH,
        ON_APP_TO_OVERVIEW_CONTINUATION
    }

    void onTimeOut(Type type, long duration);
}