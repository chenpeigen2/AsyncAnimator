package com.asyncanimator.core.anim;

import com.asyncanimator.core.scheduler.ScheduledTickScheduler;
import com.asyncanimator.core.scheduler.TickScheduler;

import java.util.ArrayList;

/**
 * AnimationHandler — 核心动画调度中枢。
 *
 * <p>对应 Android 平台 {@code androidx.core.animation.AnimationHandler}（简化版）和分析文档 §2。
 *
 * <p>核心不变式（与原版完全一致）：
 * <ul>
 *   <li><b>ThreadLocal 单例</b>：每线程一份，避免多线程竞争</li>
 *   <li><b>懒注册</b>：首次 addAnimationFrameCallback 才向 TickScheduler 注册</li>
 *   <li><b>自维持回路</b>：每次 tick 后若还有 callback 活跃，则由本类再次 post（这里 TickScheduler 内部自己续帧）</li>
 *   <li><b>懒删除</b>：removeCallback 置 null + mListDirty=true，cleanUpList 在下一帧才真正压缩</li>
 * </ul>
 *
 * <p>本类不直接持有 TickScheduler 实例，而是用 {@link Holder} 关联（让 TickScheduler 实例可替换/可测）。
 * 这是和原 AndroidX 实现的细微差异——原版用 FrameCallbackProvider14/16 包装 Choreographer，
 * 这里用统一的 TickScheduler（统一抽象）。
 */
public class AnimationHandler {

    /** 每帧回调契约：返回 true 表示本动画已结束，Handler 据此调度续帧。 */
    public interface AnimationFrameCallback {
        boolean doAnimationFrame(long frameTimeMs);
    }

    /** TickScheduler 持有者（懒构造）。可被 replaceThreadScheduler 替换。 */
    private TickSchedulerHolder schedulerHolder;

    /** 当前线程上活跃的 animation callbacks。懒删除（null 槽）。 */
    private final ArrayList<AnimationFrameCallback> mAnimationCallbacks = new ArrayList<>();

    /** 懒删除标志：true 表示本帧末尾需要 cleanUpList。 */
    private boolean mListDirty = false;

    /** 当前帧回调数（不计 null 槽）。 */
    public int getCallbackSize() {
        int n = 0;
        for (int i = mAnimationCallbacks.size() - 1; i >= 0; i--) {
            if (mAnimationCallbacks.get(i) != null) n++;
        }
        return n;
    }

    public AnimationHandler(TickScheduler scheduler) {
        this.schedulerHolder = new TickSchedulerHolder(scheduler);
    }

    /** 懒构造：第一次访问时把 scheduler 注入。 */
    public AnimationHandler() {
        this.schedulerHolder = new TickSchedulerHolder(null);
    }

    public TickScheduler getScheduler() {
        return schedulerHolder.get();
    }

    // ──── 单例管理（ThreadLocal）─────────────────────────────────────

    private static final ThreadLocal<AnimationHandler> sAnimationHandler = new ThreadLocal<>();
    private static volatile AnimationHandler sTestHandler;

    public static AnimationHandler getInstance() {
        AnimationHandler h = sTestHandler;
        if (h != null) return h;
        ThreadLocal<AnimationHandler> tl = sAnimationHandler;
        if (tl.get() == null) {
            tl.set(new AnimationHandler(new ScheduledTickScheduler()));
        }
        return tl.get();
    }

    public static void setTestHandler(AnimationHandler handler) {
        sTestHandler = handler;
    }

    /**
     * 为当前线程安装自定义 TickScheduler。
     *
     * <p>对应"独立动画线程"方案：独立线程在 onLooperPrepared() 时调用，
     * 让该线程的 AnimationHandler（ThreadLocal 单例）用绑定本线程 Looper 的帧调度器，
     * 而不是默认的 ScheduledTickScheduler（共享 JVM 调度线程）。
     *
     * <p>必须在该线程首次 {@link #getInstance()} 之前调用；若已被创建则不生效。
     */
    public static void installThreadScheduler(TickScheduler scheduler) {
        if (sTestHandler != null) return;
        if (scheduler == null) return;
        if (sAnimationHandler.get() == null) {
            sAnimationHandler.set(new AnimationHandler(scheduler));
        }
    }

    /**
     * 强制替换当前线程 AnimationHandler 的 TickScheduler（demo / 实验用）。
     *
     * <p>与 {@link #installThreadScheduler} 的区别：本方法在线程的 handler 已存在时也生效。
     * 行为：停掉旧 scheduler 的脉冲 → 换新 → 若仍有活跃动画则在新 scheduler 上重建回路。
     */
    public static void replaceThreadScheduler(TickScheduler scheduler) {
        if (sTestHandler != null) return;
        if (scheduler == null) return;
        getInstance().swapScheduler(scheduler);
    }

    private synchronized void swapScheduler(TickScheduler s) {
        TickScheduler old = schedulerHolder.get();
        if (old != null) old.stop();
        schedulerHolder = new TickSchedulerHolder(s);
        if (getCallbackSize() > 0) {
            s.start();
            s.postFrameCallback(this::onTick);
        }
    }

    // ──── 注册/取消 ────────────────────────────────────────────────

    /**
     * 注册一个 animation callback。
     * <p>若列表为空，会同时调用 {@link TickScheduler#start()} 启动调度循环（如果是首次注册）。
     */
    public void addAnimationFrameCallback(AnimationFrameCallback callback) {
        if (callback == null) return;
        if (mAnimationCallbacks.size() == 0) {
            // 首次注册：确保 scheduler 已就绪
            TickScheduler s = schedulerHolder.get();
            if (s != null) s.start();
            // 注册 self-pulse：本类实现 TickScheduler.FrameCallback
            if (s != null) s.postFrameCallback(this::onTick);
        }
        if (!mAnimationCallbacks.contains(callback)) {
            mAnimationCallbacks.add(callback);
        }
    }

    /**
     * 取消注册。懒删除：置 null + mListDirty=true（避免迭代中修改）。
     */
    public void removeCallback(AnimationFrameCallback callback) {
        if (callback == null) return;
        int idx = mAnimationCallbacks.indexOf(callback);
        if (idx >= 0) {
            mAnimationCallbacks.set(idx, null);
            mListDirty = true;
        }
    }

    public static int getAnimationCount() {
        AnimationHandler h = getInstance();
        return h == null ? 0 : h.getCallbackSize();
    }

    // ──── 帧循环（每 tick 调一次）────────────────────────────────────

    /**
     * TickScheduler 每帧调一次。这是分析文档 §2.5 onAnimationFrame 的入口。
     *
     * <p>顺序：分发所有 callback → cleanUpList 压缩 null 槽 → 若还有 callback 则由 TickScheduler 续帧
     * （这里续帧逻辑由 ScheduledTickScheduler.scheduleAtFixedRate 自动完成，
     * 不需要手动 postFrameCallback）。
     */
    void onTick(long frameTimeNanos) {
        long frameTimeMs = frameTimeNanos / 1_000_000L; // nanos → ms（对齐 AndroidX 行为）
        // ① 分发：遍历当前 callback 列表
        doAnimationFrame(frameTimeMs);
        // ② 压缩 null 槽
        cleanUpList();
    }

    /**
     * 顺序遍历 mAnimationCallbacks，跳过 null 槽，调每个 callback 的 doAnimationFrame。
     * 对应分析文档 §2.5 doAnimationFrame。
     */
    private void doAnimationFrame(long frameTimeMs) {
        int size = mAnimationCallbacks.size();
        for (int i = 0; i < size; i++) {
            AnimationFrameCallback cb = mAnimationCallbacks.get(i);
            if (cb != null) {
                // 异常隔离：一个动画出错不影响其他
                try {
                    cb.doAnimationFrame(frameTimeMs);
                } catch (Throwable t) {
                    // 类似原版 swallow
                }
            }
        }
    }

    /**
     * 清理 null 槽。仅当 mListDirty=true 才执行（性能优化）。
     */
    private void cleanUpList() {
        if (!mListDirty) return;
        for (int i = mAnimationCallbacks.size() - 1; i >= 0; i--) {
            if (mAnimationCallbacks.get(i) == null) {
                mAnimationCallbacks.remove(i);
            }
        }
        mListDirty = false;
    }

    // ──── 内部 Holder：TickScheduler 懒构造 ──────────────────────────

    private static class TickSchedulerHolder {
        private TickScheduler scheduler;

        TickSchedulerHolder(TickScheduler initial) {
            this.scheduler = initial;
        }

        synchronized TickScheduler get() {
            if (scheduler == null) {
                scheduler = new ScheduledTickScheduler();
            }
            return scheduler;
        }
    }
}