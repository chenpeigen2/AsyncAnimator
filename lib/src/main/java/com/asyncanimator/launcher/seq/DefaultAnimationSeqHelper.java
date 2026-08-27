package com.asyncanimator.launcher.seq;

/**
 * DefaultAnimationSeqHelper — SeqHelper 的 no-op 基类。
 *
 * <p>对应分析文档 §6.9。feature off 时返回此基类。
 */
public class DefaultAnimationSeqHelper {

    public void addSeqId(android.os.Bundle bundle) {}
    public boolean canFinishRecent() { return true; }
    public boolean canInterceptGesture() { return true; }
    public boolean delayFinishRecents(Runnable r) { if (r != null) r.run(); return false; }
    public void clearFinishRecentsRunnable() {}
    public void resetInterceptState() {}
    public void updateNextFinishSeqIdIfNeed(Object recentsController) {}
    public long getNextFinishSeqId(Object recentsController) { return 0L; }
}