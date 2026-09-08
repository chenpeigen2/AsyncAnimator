package com.asyncanimator.launcher.async;

import android.os.Handler;

/**
 * Executors — 预定义的 LooperExecutor 单例集合。
 *
 * <p>对应原 OPPO 代码 {@code com.oplus.basecommon.thread.Executors}（简化版）
 * 和分析文档 §6.3.2。
 *
 * <p>{@link #MAIN_EXECUTOR} 直接绑定 {@code android.os.Looper.getMainLooper()}，
 * 与"哪个线程先触发类加载"无关——在任意线程首次引用都指向真正的主线程。
 * JVM 单测环境下（android stub，returnDefaultValues）拿不到主 Looper，
 * 退化为"就地执行"，保证单测可跑。
 */
public final class Executors {

    private Executors() {}

    /** 主线程 LooperExecutor（绑定真实 Main Looper）。 */
    public static final LooperExecutor MAIN_EXECUTOR =
            new LooperExecutor(new MainHandlerAdapter());

    private static android.os.Looper mainLooperOrNull() {
        try {
            return android.os.Looper.getMainLooper();
        } catch (Throwable t) {
            return null; // JVM 单测无 android runtime
        }
    }

    /** android.os.Handler(mainLooper) → LooperExecutor.Handler 适配。 */
    private static final class MainHandlerAdapter implements LooperExecutor.Handler {
        private volatile Handler mMainHandler;

        private Handler handlerOrNull() {
            if (mMainHandler == null) {
                android.os.Looper main = mainLooperOrNull();
                if (main != null) {
                    mMainHandler = new Handler(main);
                }
            }
            return mMainHandler;
        }

        @Override
        public LooperExecutor.Looper getLooper() {
            Handler h = handlerOrNull();
            if (h != null) {
                android.os.Looper l = h.getLooper();
                return l::getThread;
            }
            // JVM 单测兜底：把当前线程当目标线程 → execute() 就地执行
            return Thread::currentThread;
        }

        @Override
        public boolean post(Runnable r) {
            Handler h = handlerOrNull();
            if (h != null) {
                return h.post(r);
            }
            r.run();
            return true;
        }

        @Override
        public boolean postDelayed(Runnable r, long delayMs) {
            Handler h = handlerOrNull();
            if (h != null) {
                return h.postDelayed(r, delayMs);
            }
            new Thread(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                }
                r.run();
            }, "AsyncAnimator-Delayed").start();
            return true;
        }
    }
}
