# 子线程更新 UI：AsyncValueAnimator 是怎么做到的？

> OPPO Launcher 动画线程体系的一个常被误解的点：业务方经常问"动画的 listener 回调如果在子线程里改 View 不是会崩吗？"
>
> 答案：**不会崩**——但不是因为 Android 允许子线程更新 UI，而是因为 AsyncValueAnimator 配合 Android 框架的设计**天然保证 listener 在主线程 fire**。
>
> 本文彻底拆解这件事。

---

## 0. Android UI 线程硬约束

Android 的 View 树有"主线程独占"的硬约束——这条规则 100% 不可绕过：

```java
//  Android 平台代码：ViewRootImpl.java
void checkThread() {
    if (mThread != Thread.currentThread()) {
        throw new CalledFromWrongThreadException(
            "Only the original thread that created a view hierarchy can touch its views.");
    }
}
```

`View.setXxx()`（`setAlpha` / `setTranslationX` / `setText` / ...）和 `View.invalidate()` 内部都过 `checkThread()`。在子线程直接动 View **立即抛异常**。

---

## 1. 关键澄清：AsyncValueAnimator 的设计目的

> **AsyncValueAnimator 不是"让业务在子线程更新 UI"——那永远不合法。**
>
> 它是让"业务在子线程触发动画（start / cancel / end）变成合法"，但**实际帧推进和 listener fire 都在主线程**。

| 操作 | 谁真正执行 |
|---|---|
| `anim.start()` 被调用 | 任何线程都行（业务调用方） |
| `start()` 内部 `super.start()` 执行 | **主线程**（被 marshal 过去） |
| 每帧 `doAnimationFrame()` | **主线程**（Choreographer 在主线程） |
| `mUpdateListeners[i].onAnimationUpdate(this)` | **主线程**（在 `animateValue` 同步调用） |
| `AnimatorListener.onAnimationStart/End` | **主线程**（在 `start/endAnimation` 同步调用） |

**业务方在 listener 里改 View，运行时线程 = 主线程**。`ViewRootImpl.checkThread()` 通过。

---

## 2. 三层 thread-marshalling 协同工作

### 2.1 LooperExecutor —— 跨线程的 Executor

`lib/src/main/java/com/asyncanimator/launcher/async/LooperExecutor.java:51`

```java
public void execute(Runnable runnable) {
    if (runnable == null) return;
    if (isCurrentThread()) {
        runnable.run();                    // 同一线程：直接跑（零开销）
    } else {
        mHandler.post(runnable);            // 跨线程：post 到目标 Looper
    }
}

public boolean isCurrentThread() {          //  O(1) 判定
    Looper l = getLooper();
    return l != null && l.getThread() == Thread.currentThread();
}
```

**关键设计**：同一线程直接跑（无 MessageQueue 调度延迟），跨线程用 `Handler.post`。

### 2.2 AsyncValueAnimator —— 包装生命周期调用

`lib/src/main/java/com/asyncanimator/launcher/async/AsyncValueAnimator.java:52-67`

```java
@Override
public void start() {
    if (isCurrentExecutor()) super.start();                   // 主线程：直接走
    else mAnimLooperExecutor.execute(super::start);          // 子线程：marshal 过去
}
@Override public void cancel() { ... 同模式 ... }
@Override public void end()    { ... 同模式 ... }
```

`start` / `cancel` / `end` 三个生命周期方法**自动 thread-marshalling**。

业务方代码：
```java
//  在任何线程都能这样调
AsyncValueAnimator anim = new AsyncValueAnimator();
anim.duration = 300;
anim.addUpdateListener(...);
anim.start();   // ← 这里调用的线程不重要，super.start() 一定在主线程
```

### 2.3 AsyncAnimCallbacks —— listener 跨线程兜底

`lib/src/main/java/com/asyncanimator/launcher/async/AsyncAnimCallbacks.java:90-110`

```java
public void onAnimationEnd(Animator animator) {
    Trace.traceBegin(8L, "AsyncAnimEnd-" + mAnimationId);
    runOnMainThread(() -> {                                  //  ← 再次 marshal
        for (NullableAnimatorListener l : mAnimListeners) {
            if (l != null) l.onAnimationEnd(animator);      //  业务 listener 一定在主线程
        }
    });
    Trace.traceEnd(8L);
}

private void runOnMainThread(Runnable r) {
    LooperExecutor exec = Executors.MAIN_EXECUTOR;
    if (exec.isCurrentThread()) r.run();
    else exec.post(r);
}
```

**为什么需要这层兜底？** 因为 Android framework 不能 100% 保证 listener fire 的线程：
- `addUpdateListener` 在 `animateValue` 里触发——在主线程
- `addListener` 在 `start`/`endAnimation` 里触发——理论上在 marshal 后的主线程
- 但**框架有边界情况**：比如 `mIsEnd` 状态机混乱时可能在错误线程 fire

所以 AsyncAnimCallbacks 兜底再 marshal 一次，**保证业务 listener 一定在主线程**。

---

## 3. 完整数据流：worker 线程 → 主线程

```
[Worker 线程]     anim.start()
        ↓
[AsyncValueAnimator.start()]
        ↓ isCurrentExecutor() = false
[LooperExecutor.execute()]
        ↓ isCurrentThread() = false
[Handler.post(super::start)]
        ↓  MessageQueue
[主 Looper 调度]
        ↓
[主线程] super.start()
        ↓
[AnimationHandler.addAnimationFrameCallback(this)]
        ↓  ThreadLocal<AnimationHandler> 拿到主线程的实例
[TickScheduler.postFrameCallback]
        ↓
[Choreographer.postFrameCallback]    ← Choreographer 也是 ThreadLocal
[主线程下一帧 doFrame]
        ↓
[主线程] onAnimationFrame()           ← 永远在主线程
        ↓
[主线程] animateValue(fraction)
        ↓
[主线程] mUpdateListeners[i].onAnimationUpdate(this)   ←  ← 业务 listener 在主线程
[主线程] View.setAlpha(...)                              ← 安全
```

**关键观察**：`super.start()` 这一行开始，到 listener fire 触发 View setter，**全在主线程**。

---

## 4. 代码层证据

### 4.1 `animateValue` 在主线程触发

`lib/src/main/java/com/asyncanimator/core/anim/ValueAnimator.java:321`

```java
public void animateValue(float fraction) {
    fraction = mInterpolator.getInterpolation(fraction);
    mCurrentFraction = fraction;
    if (mValues != null) {
        for (PropertyValuesHolder pvh : mValues) pvh.calculateValue(fraction);
    }
    if (mUpdateListeners != null) {
        for (AnimatorUpdateListener l : mUpdateListeners) {
            l.onAnimationUpdate(this);                  // ← 同步调用，当前线程 = 谁调
        }
    }
}
```

### 4.2 `doAnimationFrame` 在主线程被调用

`lib/src/main/java/com/asyncanimator/core/anim/AnimationHandler.java:doAnimationFrame`

```java
void doAnimationFrame(long frameTimeMs) {
    int size = mAnimationCallbacks.size();
    for (int i = 0; i < size; i++) {
        AnimationFrameCallback cb = mAnimationCallbacks.get(i);
        if (cb != null) cb.doAnimationFrame(frameTimeMs);
    }
}
```

`onTick` 是 TickScheduler 注册到 Choreographer 的 callback 触发的——**主线程**。

### 4.3 TickScheduler 的 callback 链

`lib/src/main/java/com/asyncanimator/core/scheduler/ScheduledTickScheduler.java:tick`

```java
void tick() {
    long count = frameCount.incrementAndGet();
    long t = System.nanoTime();
    frameTimeNanos.set(t);
    FrameCallback[] snapshot = callbacks.toArray(new FrameCallback[0]);
    for (FrameCallback cb : snapshot) {
        try {
            cb.doFrame(t);                              // ← Choreographer 在主线程 tick
        } catch (Throwable t2) {}
    }
}
```

`tick` 是 `ScheduledExecutorService.scheduleAtFixedRate` 调用的——默认 daemon 线程，但 demo 模块的 ChoreographerTickScheduler 用真 Choreographer 是在主线程。

### 4.4 start/cancel/end 路径上的 listener 触发

`lib/src/main/java/com/asyncanimator/core/anim/ValueAnimator.java:endAnimation`

```java
private void endAnimation() {
    if (this.mAnimationEndRequested) return;
    removeAnimationCallback();
    this.mAnimationEndRequested = true;
    ...
    if (startedOrRunning && mListeners != null) {
        ArrayList<AnimatorListener> tmp = (ArrayList<AnimatorListener>) mListeners.clone();
        for (AnimatorListener l : tmp) l.onAnimationEnd(this, this.mReversing);
        //                          ↑ 当前线程 = 谁调 endAnimation = 主线程
    }
}
```

---

## 5. 业务方正确做法

### ✅ 安全：在 listener 回调里改 View

```kotlin
val anim = AsyncValueAnimator.ofFloat(true, 0f, 1f) as AsyncValueAnimator
anim.duration = 300
anim.addUpdateListener { a ->
    view.alpha = a.animatedValue as Float   // ← 实际跑在主线程
}
anim.addListener(object : AnimatorListenerAdapter() {
    override fun onAnimationEnd(animation: Animator) {
        view.visibility = View.GONE         // ← 也在主线程
    }
})
anim.start()    // 任何线程都行
```

**为什么不写线程切换代码也对**——listener 一定在主线程 fire，`ViewRootImpl.checkThread()` 通过。

### ✅ 安全：把 View 操作异步到主线程

```kotlin
//  如果有"已经知道在子线程"的代码段
Thread {
    val newAlpha = computeNewAlpha()  // 子线程做计算
    view.post {                       // ← post 到主线程
        view.alpha = newAlpha
    }
}.start()
```

`view.post(Runnable)` 把 Runnable 投到主线程 MessageQueue。

### ❌ 危险：子线程直接动 View

```kotlin
Thread {
    view.alpha = 0.5f                 //  ← 立刻抛 CalledFromWrongThreadException
}.start()
```

无论用不用 AsyncValueAnimator，这条都会崩。**Android 不允许子线程动 View**，这条规则 100% 不可绕过。

### ❌ 反模式：listener 里又起子线程

```kotlin
anim.addUpdateListener { a ->
    Thread {
        view.alpha = a.animatedValue as Float   //  ← 崩
    }.start()
}
```

listener 本身在主线程，但**新起的线程**不在——View 操作照样崩。

---

## 6. AsyncValueAnimator 的 mIsEnd 跨线程安全

`lib/src/main/java/com/asyncanimator/launcher/async/AsyncValueAnimator.java:21,31`

```java
private final AtomicBoolean mIsEnd = new AtomicBoolean(false);

@Override public void onAnimationEnd(Animator a) {
    if (mIsEnd.compareAndSet(false, true)) {    // ← CAS 跨线程安全
        mAsyncAnimCallbacks.onAnimationEnd(null);
    }
}
```

**为什么用 `AtomicBoolean.compareAndSet`**？因为 end 通知在跨线程场景下可能重复触发（end 路径 + cancel 触发的 end），CAS 保证 onAnimationEnd 只 fire 一次。

---

## 7. Demo 3 验证

`demo/src/main/java/com/asyncanimator/demo/Demo3AsyncCrossThreadActivity.kt` 实际跑起来会显示：

```
[log] === worker 线程 start() ===
[log] worker thread = Thread-45                         ← 调用的线程
[log] onAnimationStart on main                          ← listener fire 在主线程
[log] onAnimationEnd on main                            ← listener fire 在主线程
[log] === 主线程 start() ===
[log] 主线程 = main                                      ← 调用的线程
[log] onAnimationStart on main                          ← listener fire 在主线程
[log] onAnimationEnd on main
```

**业务方不需要写任何线程切换代码**。

---

## 8. TL;DR

> **Android 不允许子线程更新 UI**——这条硬约束 100% 不可绕过。
>
> AsyncValueAnimator 让"**启动动画**"这件事在子线程发生变成合法，但**所有 listener 回调都在主线程 fire**。
>
> 业务方只要把 View 修改放进 listener 回调（或者用 `view.post { ... }`），就永远不需要关心线程问题。
>
> 如果在子线程直接动 View（绕过 listener），**立即抛 `CalledFromWrongThreadException`**——和是否用 AsyncValueAnimator 无关。

---

## 9. 关键源码引用

| 文件 | 行号 | 内容 |
|---|---|---|
| `lib/.../launcher/async/LooperExecutor.java` | 51-57 | `execute()` 核心 |
| `lib/.../launcher/async/LooperExecutor.java` | 46-49 | `isCurrentThread()` |
| `lib/.../launcher/async/AsyncValueAnimator.java` | 52-67 | `start/cancel/end` 委托 |
| `lib/.../launcher/async/AsyncAnimCallbacks.java` | 90-110 | listener 二次 marshal |
| `lib/.../launcher/async/AsyncValueAnimator.java` | 21,31 | `mIsEnd` 跨线程防重入 |
| `lib/.../core/anim/ValueAnimator.java` | 321-335 | `animateValue` 触发 listener |
| `lib/.../core/anim/AnimationHandler.java` | `doAnimationFrame` | 主线程 tick → 帧回调 |
| `lib/.../core/scheduler/ScheduledTickScheduler.java` | `tick` | 仿真调度器（主线程 tick） |

---

## 10. 与原版 OPPO 的差异

| 维度 | 真实 OPPO（Kotlin） | 本项目（Java） |
|---|---|---|
| `if (looper.isCurrentThread)` 字段 | `@JvmField` 标注 | 普通 getter 方法 |
| listener 容器 | `kotlin.collections.ArrayList<NullableAnimatorListener>` | `java.util.ArrayList<NullableAnimatorListener>` |
| `mIsEnd` | `@Volatile var mIsEnd: Boolean` | `java.util.concurrent.atomic.AtomicBoolean` |
| `LooperExecutor.execute` | Kotlin lambda `{ super.start() }` | Java method ref `super::start` |
| 主线程 Looper 校验 | `@MainThread` 注解 | 运行时 `Looper.myLooper()` 校验 |

**核心 thread-marshalling 逻辑完全一致**。
