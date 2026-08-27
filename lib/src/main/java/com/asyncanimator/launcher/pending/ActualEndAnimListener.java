package com.asyncanimator.launcher.pending;

import com.asyncanimator.core.anim.Animator;

/**
 * ActualEndAnimListener — cancel/end 都触发的 listener。
 *
 * <p>对应分析文档 §6.3.4。业务用来"无论如何都要清理"的 hook。
 */
public class ActualEndAnimListener extends NullableAnimatorListenerAdapter {

    public void onAnimActualEnd(Animator animator) {}
}