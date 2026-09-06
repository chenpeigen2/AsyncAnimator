package com.asyncanimator.launcher.animthread;

import android.os.Handler;

import com.asyncanimator.launcher.async.LooperExecutor;

/**
 * AnimExecutors — 独立动画线程的执行器集合。
 *
 * <p>还原自 OPPO 源码 com/oplus/basecommon/thread/Executors.java：
 * <pre>
 *   public static Looper createAndStartNewLooper(String name, int priority, int affinity) {
 *       HandlerThread thread = new HandlerThread(name, priority);
 *       ... Process.setThreadPriority + OPPO 私有绑核(affinity) ...
 *   }
 *   MAIN_EXECUTOR      = new LooperExecutor(Looper.getMainLooper());
 *   UI_HELPER_EXECUTOR = new LooperExecutor(createAndStartNewLooper("UiThreadHelper", -4, 2003));
 *   MODEL_EXECUTOR     = new OplusLooperExecutor(createAndStartNewLooper("launcher-loader", -4, 2001));
 * </pre>
 *
 * <p>第三参 affinity（2001/2003 是 OPPO 的大核绑定标记）属 UIFirst 私有能力，
 * AOSP 无公开 API，本移植仅保留"优先级 -4"语义。
 *
 * <p>用法（配合 {@link com.asyncanimator.launcher.async.AsyncValueAnimator}）：
 * <pre>
 *   AsyncValueAnimator anim = new AsyncValueAnimator();
 *   anim.setExecutor(AnimExecutors.ANIM_CONTROL_EXECUTOR); // start/帧推进都在独立线程
 *   anim.getAsyncAnimCallbacks().addListener(...);          // listener 仍回主线程
 *   anim.start();                                           // 任意线程调用都安全
 * </pre>
 */
public final class AnimExecutors {

    /** 独立动画线程执行器：首次加载即拉起 AnimationControlThread（holder 单例）。 */
    public static final LooperExecutor ANIM_CONTROL_EXECUTOR =
            new LooperExecutor(new AndroidHandlerAdapter(
                    new Handler(AnimationControlThread.getInstance().getLooper())));

    private AnimExecutors() {
    }

    /** android.os.Handler → LooperExecutor.Handler 适配。 */
    private static final class AndroidHandlerAdapter implements LooperExecutor.Handler {
        private final android.os.Handler mHandler;

        AndroidHandlerAdapter(android.os.Handler handler) {
            mHandler = handler;
        }

        @Override
        public LooperExecutor.Looper getLooper() {
            // 映射到 LooperExecutor.Looper（单方法 Thread getThread()）；
            // android.os.Looper.getThread() 正好返回该线程
            android.os.Looper l = mHandler.getLooper();
            return l::getThread;
        }

        @Override
        public boolean post(Runnable r) {
            return mHandler.post(r);
        }

        @Override
        public boolean postDelayed(Runnable r, long delayMs) {
            return mHandler.postDelayed(r, delayMs);
        }
    }
}
