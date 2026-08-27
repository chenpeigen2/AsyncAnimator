# OPPO Launcher 动画线程执行动画的实现方案 — 深入分析

> 范围：`com.android.launcher 15.8.24`（OPPO/ColorOS 15 Android Launcher）反编译源码中的动画执行机制。
>
> 工作目录：`D:/oppo_a6_launcher/sources`，共 16 343 个 `.java` 文件，3 095 个明文，其余 13 248 个被企业 DLP 加密（`%TSD-Header-###%` 头）。所有动画相关的核心类**均被加密**，本分析基于 `Grep` 工具穿透 DLP 读出的源码片段 + Android 公开 API 知识推断。
>
> 阅读对象：想了解这套机制、想复用模式或排查性能问题的工程师。

---

## 0. TL;DR（先用 30 秒看明白）

整个动画体系分四层，**自底向上**：

```
┌──────────────────────────────────────────────────────────────────────────┐
│ L4 业务层                                                                  │
│ LauncherAnimationRunner  / WorkspaceStateTransitionAnimation              │
│ AnimationController (状态机) / PendingAnimation (构建器)                   │
└──────────────────────────────────────────────────────────────────────────┘
                                  ▲
┌──────────────────────────────────────────────────────────────────────────┐
│ L3 Launcher 自定义封装层（OPPO 在标准之上加的）                              │
│ AnimatorPlaybackController  (一套进度驱动 N 个子动画)                       │
│ AsyncValueAnimator          (跨 Looper 的 ValueAnimator)                   │
│ AsyncAnimCallbacks          (跨线程 listener 派发)                          │
│ OplusSpringObjectAnimator   (ValueAnimator + Spring 混合)                  │
│ ChoreographerUtil           (N 帧延迟回调)                                │
└──────────────────────────────────────────────────────────────────────────┘
                                  ▲
┌──────────────────────────────────────────────────────────────────────────┐
│ L2 AndroidX 动画框架（项目直接依赖的标准库）                                 │
│ Animator / ValueAnimator / AnimatorSet / ObjectAnimator                  │
│ androidx.core.animation.AnimationHandler                                 │
│ androidx.dynamicanimation.animation.AnimationHandler                       │
└──────────────────────────────────────────────────────────────────────────┘
                                  ▲
┌──────────────────────────────────────────────────────────────────────────┐
│ L1 平台层                                                                  │
│ android.view.Choreographer  → VSYNC → doFrame(frameTimeNanos)            │
└──────────────────────────────────────────────────────────────────────────┘
```

**最重要的三个事实**：

1. **所有动画只有一条进系统 Choreographer 的通道**：即 `AnimationHandler.FrameCallbackProvider16.postFrameCallback()`。无论上层套多少层包装，最终都是这一行进入 `Choreographer.getInstance().postFrameCallback(this)`。
2. **AnimationHandler 是 ThreadLocal**：每个线程各持一份 `sAnimationHandler`，所以**动画必须跑在有 Looper 的线程上**，且**start 和后续帧必须在同一线程**。
3. **Launcher 的核心创新是 `AnimatorPlaybackController`**：它把 N 个动画压缩成"主 ValueAnimator [0,1] → 同步驱动所有子动画 Holder"，避免多套 AnimatorSet 各跑各的导致不同步。

---

## 1. 平台层：Choreographer

`Choreographer` 是 Android 平台层的"V-SYNC 回调中转站"。它本身是 ThreadLocal 的（绑 Looper），每 16ms（60Hz）或 8.3ms（120Hz）由 SurfaceFlinger 触发一次 doFrame，把所有注册过的 `FrameCallback` 按优先级（INPUT / ANIMATION / COMMIT / OVERLAY 4 个阶段）依次调用一次。

在动画线程的执行链中，**Choreographer 只负责一件事：在每个 VSYNC 把 frameTime 推给所有注册者**。

项目里直接接触 Choreographer 的代码只有两类：

- **桥接类**：`AnimationHandler.FrameCallbackProvider16.postFrameCallback()`、`OplusSpringObjectAnimator` 内部的 `SpringAnimation.updateValueAndVelocity`、以及 `com/oplus/quickstep/utils/ChoreographerUtil`。
- **间接使用**：所有 `ValueAnimator.start()` 链路最终都会调到上面那个桥接类。

一个**关键认知**：Choreographer 自己**不执行任何动画**，它只管在正确的时机调用 `doFrame`。动画的"执行"完全在 `ValueAnimator.doAnimationFrame()` 里完成。

---

## 2. 框架层一：androidx.core.animation.AnimationHandler

文件：`androidx/core/animation/AnimationHandler.java`

这是 **`androidx.core.animation` 子模块（用于兼容旧版本的 ValueAnimator/Animator 体系）** 的调度中枢。它和我们平时说的 `android.animation.ValueAnimator` 是两套不同来源但接口兼容的代码。

### 2.1 类的设计动机

`ValueAnimator` 需要每帧推进进度，而每帧推进必须挂到某个时间源上。最准的时间源就是 `Choreographer`。但 `Choreographer` 是 ThreadLocal 的（绑 Looper），如果让每个 `ValueAnimator` 都直接 register 自己的 `Choreographer.FrameCallback`，会有三个问题：

1. **冗余注册**：N 个动画同时跑，就有 N 个 `Choreographer.FrameCallback`，但 Choreographer 实际上对所有 callback 是一次性统一调用 doFrame。
2. **移除不及时**：动画结束时要 unregister，但 Choreographer 不允许迭代中修改。
3. **跨 API 版本兼容**：API 14 没有 Choreographer，必须 fallback 到 Handler.postDelayed。

`AnimationHandler` 就是用来解决这三个问题的。

### 2.2 核心结构

```java
public class AnimationHandler {
    public static final ThreadLocal<AnimationHandler> sAnimationHandler = new ThreadLocal<>();
    private static AnimationHandler sTestHandler = null;
    private final ArrayList<AnimationFrameCallback> mAnimationCallbacks = new ArrayList<>();
    boolean mListDirty = false;
    private final AnimationFrameCallbackProvider mProvider;
    ...
    public interface AnimationFrameCallback {
        boolean doAnimationFrame(long j8);   // 返回 true 表示动画结束
    }

    public interface AnimationFrameCallbackProvider {
        long getFrameDelay();
        void onNewCallbackAdded(AnimationFrameCallback animationFrameCallback);
        void postFrameCallback();
        void setFrameDelay(long j8);
    }
}
```

| 字段 | 含义 |
|---|---|
| `sAnimationHandler` | **每线程一份**的 `AnimationHandler` 单例。由 `Animator.addAnimationCallback(cb)` 静态方法触发 lazy 创建。 |
| `mAnimationCallbacks` | 当前线程上**所有还在跑的 `ValueAnimator` 引用列表**。 |
| `mProvider` | 时间源抽象：`FrameCallbackProvider14`（API < 16）或 `FrameCallbackProvider16`（API ≥ 16）。 |
| `mListDirty` | **懒清理标志**。`removeCallback` 不会立即从列表移除，而是置 null 并打这个标志，下次 `cleanUpList` 才真正清理。 |
| `sTestHandler` | 测试用注入钩子（Robolectric 等场景）。 |

### 2.3 FrameCallbackProvider16 — 真正的 Choreographer 桥梁

```java
@RequiresApi(16)
public class FrameCallbackProvider16 implements AnimationFrameCallbackProvider, Choreographer.FrameCallback {
    @Override
    public void postFrameCallback() {
        Choreographer.getInstance().postFrameCallback(this);   // ★ 唯一进入 Choreographer 的入口
    }

    @Override
    public void doFrame(long j8) {
        AnimationHandler.this.onAnimationFrame(j8 / AnimationKt.MillisToNanos);  // nanos → ms
    }

    @Override
    public long getFrameDelay() { return android.animation.ValueAnimator.getFrameDelay(); }

    @Override
    public void setFrameDelay(long j8) { android.animation.ValueAnimator.setFrameDelay(j8); }
}
```

- **唯一性**：一个 `AnimationHandler` 对应一个 `FrameCallbackProvider16`，所以一个线程上只有一个 `Choreographer.FrameCallback` 注册。
- **唯一转换**：frameTime 从 `nanos` 转为 `ms` 在这里完成（`AnimationKt.MillisToNanos` 常量）。
- **唯一跨层级**：它把"系统级 Choreographer"和"应用级 Animator 列表"用一次 doFrame 桥接起来。

### 2.4 帧调度主循环

```java
public void onAnimationFrame(long j8) {
    doAnimationFrame(j8);                              // ① 通知所有 callback
    if (this.mAnimationCallbacks.size() > 0) {
        this.mProvider.postFrameCallback();            // ② 还有动画在跑，再注册下一帧
    }
}

public void addAnimationFrameCallback(AnimationFrameCallback animationFrameCallback) {
    if (this.mAnimationCallbacks.size() == 0) {
        this.mProvider.postFrameCallback();            // 第一个 callback 加入时才注册 Choreographer
    }
    if (!this.mAnimationCallbacks.contains(animationFrameCallback)) {
        this.mAnimationCallbacks.add(animationFrameCallback);
    }
    this.mProvider.onNewCallbackAdded(animationFrameCallback);
}

private void cleanUpList() {
    if (this.mListDirty) {
        for (int size = this.mAnimationCallbacks.size() - 1; size >= 0; size--) {
            if (this.mAnimationCallbacks.get(size) == null) {
                this.mAnimationCallbacks.remove(size);
            }
        }
        this.mListDirty = false;
    }
}

public void removeCallback(AnimationFrameCallback animationFrameCallback) {
    int iIndexOf = this.mAnimationCallbacks.indexOf(animationFrameCallback);
    if (iIndexOf >= 0) {
        this.mAnimationCallbacks.set(iIndexOf, null);   // 置 null，迭代安全
        this.mListDirty = true;
    }
}
```

**四条核心原则**：

1. **懒注册**：第一个 callback 加入时才 `postFrameCallback`，避免空载注册浪费 Choreographer 槽位。
2. **持续驱动**：只要还有未结束的 callback，每帧结束会自动再 post 一次——这就是"动画跑起来就停不下来"的实现。
3. **迭代安全**：`removeCallback` 永远不修改列表结构，只置 null + 打标，下次帧开头才真正清掉。
4. **线程单例**：因为 `AnimationHandler` 是 ThreadLocal，整个调度链天然无锁。

---

## 3. 框架层二：androidx.dynamicanimation.animation.AnimationHandler

文件：`androidx/dynamicanimation/animation/AnimationHandler.java`

`dynamicanimation` 是另一套独立的动画体系（SpringAnimation、FlingAnimation），它**也自己实现了一套 `AnimationHandler`**——和 `core.animation` 的同名但不同类。

> ⚠ 这是项目里的第二个"两个 AnimationHandler"陷阱，看代码时**必须看清 import 的是哪个包**。

```java
class AnimationHandler {
    private static final long FRAME_DELAY_MS = 10;
    public static final ThreadLocal<AnimationHandler> sAnimatorHandler = new ThreadLocal<>();
    ...
    final ArrayList<AnimationFrameCallback> mAnimationCallbacks = new ArrayList<>();
    long mCurrentFrameTime = 0;
    private final AnimationCallbackDispatcher mCallbackDispatcher = new AnimationCallbackDispatcher();

    public class AnimationCallbackDispatcher {
        public void dispatchAnimationFrame() {
            AnimationHandler.this.mCurrentFrameTime = SystemClock.uptimeMillis();
            AnimationHandler.this.doAnimationFrame(AnimationHandler.this.mCurrentFrameTime);
            if (AnimationHandler.this.mAnimationCallbacks.size() > 0) {
                AnimationHandler.this.getProvider().postFrameCallback();
            }
        }
    }

    @RequiresApi(16)
    public static class FrameCallbackProvider16 extends AnimationFrameCallbackProvider {
        private final Choreographer mChoreographer;
        private final Choreographer.FrameCallback mChoreographerCallback;
        public FrameCallbackProvider16(...) {
            this.mChoreographer = Choreographer.getInstance();
            this.mChoreographerCallback = new Choreographer.FrameCallback() {
                @Override public void doFrame(long j8) {
                    FrameCallbackProvider16.this.mDispatcher.dispatchAnimationFrame();
                }
            };
        }
        @Override public void postFrameCallback() {
            this.mChoreographer.postFrameCallback(this.mChoreographerCallback);
        }
    }
}
```

**和 core.animation 的关键差异**：

| 维度 | `core.animation.AnimationHandler` | `dynamicanimation.animation.AnimationHandler` |
|---|---|---|
| 单例字段 | `sAnimationHandler` | `sAnimatorHandler` |
| 类可见性 | public | package-private |
| Provider 字段 | 持有在 AnimationHandler 里 | 持有在 AnimationCallbackDispatcher 里（间接） |
| 支持延迟 callback | 否 | 是（`mDelayedCallbackStartTime`） |
| 用的人 | `ValueAnimator` 子类 | `SpringAnimation` / `FlingAnimation` |

**为什么 `OplusSpringObjectAnimator` 能"ValueAnimator → Spring"切换**：因为这两套 AnimationHandler 是**互相独立**的，可以同时存在。`OplusSpringObjectAnimator` 持有两者各一份——平时跑 `core.animation` 的 `ValueAnimator`，切到 Spring 时改用 `dynamicanimation.animation` 的 `AnimationHandler`。

### 3.1 完整的字段级对比表

> 来源：基于完整源码 dump.py 的逐行比对。

| 维度 | `core.animation.AnimationHandler` | `dynamicanimation.animation.AnimationHandler` |
|---|---|---|
| **类可见性** | `class AnimationHandler`（package-private） | `class AnimationHandler`（package-private） |
| **单例字段** | `ThreadLocal<AnimationHandler> sAnimationHandler` + `static AnimationHandler sTestHandler` | `ThreadLocal<AnimationHandler> sAnimatorHandler`（仅按线程） |
| **callback 列表容器** | `ArrayList<AnimationFrameCallback> mAnimationCallbacks` | `ArrayList<AnimationFrameCallback> mAnimationCallbacks` + 额外的 `SimpleArrayMap<AnimationFrameCallback, Long> mDelayedCallbackStartTime` |
| **`FrameCallbackProvider` 抽象** | 普通接口（4 方法）：`getFrameDelay` / `onNewCallbackAdded` / `postFrameCallback` / `setFrameDelay` | 抽象类（仅 1 方法）：`postFrameCallback` |
| **`FrameCallbackProvider14` 实现** | `implements Runnable`，`mFrameDelay=16`（默认），`ThreadLocal<Handler> sHandler` | `extends AnimationFrameCallbackProvider`，构造时 `new Handler(Looper.myLooper())`（非 ThreadLocal），`mRunnable` 匿名类 |
| **`FrameCallbackProvider16` 实现** | `implements Choreographer.FrameCallback`，`doFrame` 内做 ns→ms 转换 | `extends AnimationFrameCallbackProvider`，**不**做单位转换（Spring 只用 ms） |
| **时间戳来源** | 由 `Choreographer.doFrame` 传入 nanos，`/AnimationKt.MillisToNanos` 转 ms | `AnimationCallbackDispatcher.dispatchAnimationFrame` 内 `mCurrentFrameTime = SystemClock.uptimeMillis()` 自己取 |
| **`addAnimationFrameCallback` 签名** | `addAnimationFrameCallback(callback)` 单参 | `addAnimationFrameCallback(callback, delayedStartTime)` 双参 |
| **支持延迟启动** | ❌ callback 内用 `mStartTime > j8` 比较决定要不要跑 | ✅ 注册期通过 `mDelayedCallbackStartTime.put(cb, uptime + delayedStartTime)` 记录，每帧 `isCallbackDue` 检查 |
| **`getInstance()` 访问方式** | 先检查 `sTestHandler` 再 ThreadLocal，构造里 null → 默认 16版 | 仅 ThreadLocal，构造里**不**实例化 provider，`getProvider()` 懒分配 |
| **测试钩子** | `setTestHandler(AnimationHandler)` 旁路单例 | `setProvider(AnimationFrameCallbackProvider)` 替换帧源（更细粒度） |
| **14 兜底帧间隔** | `mFrameDelay=16` | `FRAME_DELAY_MS=10`（更激进，为物理动画追求更高刷新率） |
| **额外方法** | `autoCancelBasedOn(ObjectAnimator)` 同属性自动 cancel | `getFrameTime()` 静态读 `mCurrentFrameTime`，供 spring 数值积分 |
| **`AnimationCallbackDispatcher`** | 无 | 有——单抽出"取时间+分发+续命"的中间层，让 provider 不必持外层 Handler 引用 |

### 3.2 接口差异的工程原因

#### 3.2.1 为什么 `core` 的 `doAnimationFrame(long)` 返回 boolean？

返回 `boolean` 有两个作用：
1. **告诉 Handler 本帧是否还要继续 schedule**：`ValueAnimator.doAnimationFrame` 在 `animateBasedOnTime` 返回 `true` 时返回 `true`，但 Handler 末尾仍 `if (mAnimationCallbacks.size() > 0) postFrameCallback()`——**`callback 返回 boolean` 是通知"我已结束"的信号**，而真正的"是否重排"由 callback 列表的整体尺寸决定。
2. **支持 `pause()` 语义**：`ValueAnimator.doAnimationFrame` 在 `mPaused` 时 `removeAnimationCallback() + return false`，通知 Handler "本帧我不需要执行，下一帧也别叫我"，配合 `resume()` 时再 `addAnimationCallback()` 恢复。

dynamicanimation 版的 callback 接口**完全相同的 boolean 返回**，但因为物理动画无暂停概念，所以只在到达 equilibrium 时返回 `true`（`DynamicAnimation.java:391-392`），语义更纯粹——"是否到达终态"。

#### 3.2.2 为什么 `dynamicanimation` 引入 `AnimationCallbackDispatcher` 中间层？

```java
public class AnimationCallbackDispatcher {
    public void dispatchAnimationFrame() {
        AnimationHandler.this.mCurrentFrameTime = SystemClock.uptimeMillis();
        AnimationHandler animationHandler = AnimationHandler.this;
        animationHandler.doAnimationFrame(animationHandler.mCurrentFrameTime);
        if (AnimationHandler.this.mAnimationCallbacks.size() > 0) {
            AnimationHandler.this.getProvider().postFrameCallback();
        }
    }
}
```

三个设计动机：
1. **截获时间戳**：`mCurrentFrameTime` 必须由 Dispatcher 写入，**所有** SpringAnimation 才能在 `getFrameTime()`（静态）里拿到**当前帧的精确时间**。这是 SpringAnimation 用闭式解析解做数值积分的基础——若各 callback 各自调用 `SystemClock.uptimeMillis()`，会因调用顺序不同而读到不同的"当前时间"，导致同一帧不同 spring 状态不一致。
2. **解耦 provider 与 Handler 内部状态**：`AnimationFrameCallbackProvider` 是个抽象类，构造时接收 `AnimationCallbackDispatcher`，provider **不需要**直接持有 `AnimationHandler`——它只认识 dispatcher。这让 `setProvider(...)` 替换测试 provider 时不会污染 Handler 的状态机。
3. **匿名内部类只捕获 dispatcher**：14 版的 `mRunnable` 和 16 版的 `mChoreographerCallback` 都是匿名类，它们只引用 `FrameCallbackProviderX.mDispatcher` —— 而不是 `AnimationHandler.this`。对比 core 版内部类引用 `AnimationHandler.this`（紧耦合），dynamicanimation 版的 provider 是**松耦合**的。

#### 3.2.3 为什么 `dynamicanimation.addAnimationFrameCallback` 多一个 `delayedStartTime` 参数？

```java
public void addAnimationFrameCallback(AnimationFrameCallback cb, long delayedStartTime) {
    if (this.mAnimationCallbacks.size() == 0) getProvider().postFrameCallback();
    if (!this.mAnimationCallbacks.contains(cb)) this.mAnimationCallbacks.add(cb);
    if (delayedStartTime > 0) {
        this.mDelayedCallbackStartTime.put(cb, SystemClock.uptimeMillis() + delayedStartTime);
    }
}
```

`delayedStartTime` 真正的用处是**把"延迟启动"逻辑从 callback 内部提到 Handler 注册层**：

- **core 版**：延迟由 callback 自己实现。看 `ValueAnimator.doAnimationFrame`（`:358-365`）：
  ```java
  if (this.mStartTime < 0) this.mStartTime = ...;
  if (!this.mRunning) {
      if (this.mStartTime > j8 && this.mSeekFraction == -1.0f) return false;  // 还在 startDelay 中
  }
  ```
  **每一帧都被驱动**（浪费 CPU），只在 callback 里"空转"返回 false 推迟真正起跳。

- **dynamicanimation 版**：延迟在 Handler 层就生效，`doAnimationFrame` 里：
  ```java
  if (animationFrameCallback != null && isCallbackDue(animationFrameCallback, jUptimeMillis)) {
      animationFrameCallback.doAnimationFrame(j8);
  }
  ```
  配合 `isCallbackDue`，**到时间前完全不调用 callback**。避免了每帧都做无用的"距离起跳还有多少 ms"判断。

**设计哲学**：dynamicanimation 把 spring 视为**实时物理系统**，每帧必须基于真实的 dt 做积分——空转的帧污染 dt 序列；core 把 animator 视为**状态机**，未到 startDelay 只是"还在等待"，下一帧再问一次没成本。

### 3.3 `ValueAnimator` vs `DynamicAnimation` 的实现层对比

> 这是核心的"取舍对"——回答"为什么 core 用绝对时间，dynamicanimation 用帧间 dt"。

| 维度 | `ValueAnimator` (core) | `DynamicAnimation` |
|---|---|---|
| **时间基准** | 绝对时间 `mStartTime`、`mLastFrameTime` | 帧间 dt，`mLastFrameTime` |
| **关键公式** | `animateBasedOnTime` 里 `(j8 - mStartTime) / scaledDuration` → fraction → `Interpolator.getInterpolation(fraction)` | `doAnimationFrame` 里 `dt = j8 - j9`，把 dt 传给 `updateValueAndVelocity(dt)`（物理积分） |
| **暂停语义** | 绝对时间偏移：`mPauseTime`、`mResumed`、`mStartTime += (j8 - mPauseTime)` | **无暂停 API**——Spring 物理上没有"暂停"概念 |
| **线程约束** | `Looper.myLooper() == null` 抛 `AndroidRuntimeException`（任意 Looper 线程） | `@MainThread` 强校验（`Looper.myLooper() != Looper.getMainLooper()` 抛 `AndroidRuntimeException`） |
| **Listener 删除** | `Animator.removeListener` 立刻 `arrayList.remove` | 置 null + `removeNullEntries` 末尾清扫（每帧 `setPropertyValue` 都遍历 updateListeners） |
| **`OnAnimationEnd` 签名** | `onAnimationEnd(Animator, boolean isReversing)` | `onAnimationEnd(DynamicAnimation, boolean canceled, float value, float velocity)` —— 4 参 |
| **运行时添加 updateListener** | 允许（不抛异常） | **抛异常**："Error: Update listeners must be added before the animation" |
| **`start()` sanityCheck** | 仅 Looper 校验 | `mValue > mMaxValue || mValue < mMinValue` 抛异常；SpringAnimation 额外检查 `mSpring != null` 和 `finalPosition` 在 [min, max] |
| **`cancel` 处理 pending** | n/a（时序动画无"运行中改目标"） | cancel 时把 `mPendingPosition` 提交为新 finalPosition（保留用户最新意图） |
| **默认 duration** | `mDuration = 300`（毫秒） | n/a（Spring 没有"duration"概念，只有 `dampingRatio` 和 `stiffness`） |
| **默认插值器** | `AccelerateDecelerateInterpolator` | n/a（Spring 走的是物理方程，不是曲线插值） |

**dt vs 绝对时间的取舍**：
- `ValueAnimator` 用绝对时间：保证"播放到 50% 时一定是真实进度 50%"，哪怕丢了一帧
- `DynamicAnimation` 用帧间 dt：物理引擎基于 dt 积分是数值最稳定的方式（变步长积分器自动适应帧率波动），但**丢帧时速度计算会包含跳变**——所以 SpringAnimation 不在乎"播到 X% 应该是什么值"，只在乎"从上次到这次经历了多少时间，速度位置该如何演化"

`removeNullEntries` 立即清理 vs `mListDirty` 异步清理的取舍：dynamicanimation 是面向单帧的物理求解，listener 数量通常很少（1-3 个），立即清理的开销可以忽略；而 core.animation 是通用动画框架，listener 可能几十个并迭代中嵌套修改，所以延后清理更安全。

---

## 4. 基础类：ValueAnimator

文件：`androidx/core/animation/ValueAnimator.java`

### 4.1 类层级

```
androidx.core.animation.Animator                              (abstract)
        │
        ├── ValueAnimator   (abstract)
        │       │
        │       ├── ObjectAnimator
        │       └── TimeAnimator
        │
        └── AnimatorSet
```

```java
public class ValueAnimator extends Animator implements AnimationHandler.AnimationFrameCallback {
    public static final int INFINITE = -1;
    ...
}
```

**关键**：`ValueAnimator` 实现了 `AnimationHandler.AnimationFrameCallback`——所以它本身就是"那个每帧被通知的 callback"。

### 4.2 启动流程 `start(boolean)`

```java
private void start(boolean z8) {
    if (Looper.myLooper() == null) {
        throw new AndroidRuntimeException("Animators may only be run on Looper threads");
    }
    this.mReversing = z8;
    this.mSelfPulse = !this.mSuppressSelfPulseRequested;
    if (z8) { ... /* 处理反转初始化 */ }
    if (this.mStartDelay == 0 || this.mSeekFraction >= 0.0f || this.mReversing) {
        startAnimation();                      // ① 标记 mRunning=true, initAnimation()
        float f10 = this.mSeekFraction;
        if (f10 == -1.0f) {
            setCurrentPlayTime(0L);            // 从头开始
        } else {
            setCurrentFraction(f10);           // seek 到指定进度
        }
    }
    addAnimationCallback();                    // ② 把 this 加入 AnimationHandler 列表
}

private void addAnimationCallback() {
    if (this.mSelfPulse) {
        Animator.addAnimationCallback(this);   // AnimationHandler.getInstance().addAnimationFrameCallback(this)
    }
}
```

注意 `mSelfPulse`：当动画被外层容器"包养"时（比如 `AnimatorSet` 主动驱动子动画），子动画应 `setSuppressSelfPulse(true)`，否则会重复驱动。

### 4.3 每帧执行 `doAnimationFrame(long frameTime)`

```java
public final boolean doAnimationFrame(long j8) {
    if (this.mStartTime < 0) {
        this.mStartTime = j8;                  // 第一次记录起始时间
    }
    if (this.mLastFrameTime < 0 && this.mSeekFraction >= 0.0f) {
        this.mStartTime = j8 - ((long) (getScaledDuration() * this.mSeekFraction));
        this.mSeekFraction = -1.0f;            // 用完清掉 seek 状态
    }
    this.mLastFrameTime = j8;
    boolean zAnimateBasedOnTime = animateBasedOnTime(Math.max(j8, this.mStartTime));
    if (zAnimateBasedOnTime) {
        endAnimation();                         // 触发 onAnimationEnd
    }
    return zAnimateBasedOnTime;                // true → AnimationHandler 会从列表里清掉自己
}
```

### 4.4 帧时间模型

**`animateBasedOnTime(long currentTime)`**（伪代码还原）：

```java
public boolean animateBasedOnTime(long currentTime) {
    long delta = currentTime - this.mStartTime;
    float duration = getScaledDuration();     // duration × durationScale
    if (this.mDuration == INFINITE || delta < duration) {
        // 还在动画中
        float fraction = delta / duration;
        fraction = clampFraction(fraction);
        this.mOverallFraction = fraction;
        animateValue(getCurrentIterationFraction(fraction, this.mReversing));
        return false;
    } else {
        // 动画结束
        this.mOverallFraction = 1.0f;
        animateValue(getCurrentIterationFraction(1.0f, this.mReversing));
        return true;
    }
}
```

### 4.5 `animateValue(float fraction)` — 真正的"动画执行"

```java
@CallSuper
public void animateValue(float f9) {
    float interpolation = this.mInterpolator.getInterpolation(f9);   // 应用插值器
    this.mCurrentFraction = interpolation;
    int length = this.mValues.length;
    for (int i9 = 0; i9 < length; i9++) {
        this.mValues[i9].calculateValue(interpolation);              // 计算每个 PropertyValuesHolder 的当前值
    }
    ArrayList<Animator.AnimatorUpdateListener> arrayList = this.mUpdateListeners;
    if (arrayList != null) {
        int size = arrayList.size();
        for (int i10 = 0; i10 < size; i10++) {
            this.mUpdateListeners.get(i10).onAnimationUpdate(this);
        }
    }
}
```

**这是每帧真正产生视觉变化的代码**。对 `ObjectAnimator` 来说，`PropertyValuesHolder.calculateValue` 内部会把值 `setValue(target)` 到目标对象上——比如 `setTranslationX(...)`、`setAlpha(...)` 等。

### 4.6 生命周期时序图（一次普通 ValueAnimator.start() → 结束）

```
调用方           ValueAnimator            AnimationHandler       Choreographer       系统
 │                │                          │                       │                  │
 │ start()        │                          │                       │                  │
 ├───────────────►│                          │                       │                  │
 │                │ startAnimation()         │                       │                  │
 │                │   ├ mRunning=true        │                       │                  │
 │                │   └ initAnimation()      │                       │                  │
 │                │ addAnimationCallback()   │                       │                  │
 │                ├─────────────────────────►│                       │                  │
 │                │                          │ mCallbacks 空?        │                  │
 │                │                          │ postFrameCallback()    │                  │
 │                │                          ├──────────────────────►│                  │
 │                │                          │                       │ postFrameCallback │
 │                │                          │                       ├─────────────────►│
 │                │                          │                       │                  │
 │                │                          │                       │                  │ VSYNC @16ms
 │                │                          │                       │ ◄─────────────────┤
 │                │                          │                       │ doFrame(t_nanos)  │
 │                │                          │ ◄─────────────────────┤                  │
 │                │                          │ doAnimationFrame(t)   │                  │
 │                │                          │  ├ 遍历 callbacks     │                  │
 │                │ ◄─────────────────────────┤  └ callback.doAnimationFrame(t)
 │                │ doAnimationFrame(t)       │                       │                  │
 │                │  ├ animateBasedOnTime()  │                       │                  │
 │                │  ├ animateValue()        │                       │                  │
 │                │  │  ├ Interpolator      │                       │                  │
 │                │  │  ├ PVH.calculate()   │ (产生 UI 副作用)       │                  │
 │                │  └ onAnimationUpdate()   │                       │                  │
 │  onAnimationUpdate ◄──┤                  │                       │                  │
 │                │ (未结束)                  │ postFrameCallback()    │                  │
 │                │                          ├──────────────────────►├─────────────────►│
 │                │                          │                       │                  │
 │                │                          │                       │                  │ ...循环...
 │                │                          │                       │                  │
 │                │ (时间到)                  │                       │                  │
 │                │ animateBasedOnTime→true │                       │                  │
 │                │ endAnimation()           │                       │                  │
 │                │   └ onAnimationEnd()     │                       │                  │
 │                │ removeCallback()         │                       │                  │
 │                ├─────────────────────────►│                       │                  │
 │                │                          │ (列表空, 停止 postFrameCallback)         │
 │ onAnimationEnd ◄──┤                       │                       │                  │
```

---

## 5. Animator 基类的关键能力

文件：`androidx/core/animation/Animator.java`

```java
public abstract class Animator implements Cloneable {
    ArrayList<AnimatorListener> mListeners = null;
    ArrayList<AnimatorPauseListener> mPauseListeners = null;
    ArrayList<AnimatorUpdateListener> mUpdateListeners = null;
    boolean mPaused = false;
    ...

    public static void addAnimationCallback(AnimationHandler.AnimationFrameCallback animationFrameCallback) {
        AnimationHandler.getInstance().addAnimationFrameCallback(animationFrameCallback);
    }
    public static void removeAnimationCallback(AnimationHandler.AnimationFrameCallback animationFrameCallback) {
        AnimationHandler.getInstance().removeCallback(animationFrameCallback);
    }

    public void addListener(@NonNull AnimatorListener animatorListener) { ... }
    public void addUpdateListener(@NonNull AnimatorUpdateListener animatorListener) { ... }
    public void removeListener(...) { ... }
    public void pause() {
        if (!isStarted() || this.mPaused) return;
        this.mPaused = true;
        ArrayList<AnimatorPauseListener> arrayList = this.mPauseListeners;
        if (arrayList != null) {
            Object objClone = arrayList.clone();     // ★ clone 防迭代中修改
            ... // 触发 onAnimationPause
        }
    }
    public void resume() { ... }
    ...
}
```

**重要细节**：`pause()` / `resume()` 触发的是 `onAnimationPause` / `onAnimationResume`，调用前会 `clone` listener 列表——确保迭代过程中外部 removeListener 不会破坏循环。

---

## 6. Launcher 自定义层 — OPPO 改造的 5 件武器

### 6.1 AnimatorPlaybackController —— "一套进度驱动 N 个子动画"

文件：`com/android/launcher3/anim/AnimatorPlaybackController.java`

#### 6.1.1 设计动机

Launcher 里几乎所有"场景 A → 场景 B"的转场动画都包含**多个子动画**：

- AllApps ↔ Workspace：背景模糊、容器高度变化、图标缩放、HOT 等
- Recents 进入/退出：任务卡片缩放、透明度、位移、Dim 等
- Workspace 翻页：图标移动、Hotseat 联动等

如果让这些子动画各自独立跑 `ValueAnimator`，会出两个问题：
1. **进度不同步**：每个 `ValueAnimator.start()` 时间不一致，进度计算有微小漂移，多个动画会"看起来不齐"。
2. **reverse/pause 难做**：要暂停一个转场，得手动遍历所有子动画调 pause/reverse。

`AnimatorPlaybackController` 用一个 `ValueAnimator` 当"主时钟"，每帧回调时把 [0,1] 的进度**同步推**到所有子动画上，完美解决上述问题。

#### 6.1.2 核心结构

```java
public class AnimatorPlaybackController implements ValueAnimator.AnimatorUpdateListener {
    private final AnimatorSet mAnim;                       // 被托管的整套动画
    private final ValueAnimator mAnimationPlayer;          // ★ 主 ValueAnimator（0..1 进度）
    private final Holder[] mChildAnimations;               // 所有需要同步的子动画
    protected float mCurrentFraction;
    ...

    public AnimatorPlaybackController(AnimatorSet animatorSet, long j8, ArrayList<Holder> arrayList) {
        this.mAnim = animatorSet;
        this.mDuration = j8;
        ValueAnimator valueAnimatorOfFloat = ValueAnimator.ofFloat(0.0f, 1.0f);
        this.mAnimationPlayer = valueAnimatorOfFloat;
        valueAnimatorOfFloat.setInterpolator(Interpolators.LINEAR);   // ★ 强制线性：进度从主控线性推进
        valueAnimatorOfFloat.addListener(new OnAnimationEndDispatcher(this, 0));
        valueAnimatorOfFloat.addUpdateListener(this);                 // ★ 自己监听自己
        ...
        this.mChildAnimations = (Holder[]) arrayList.toArray(new Holder[arrayList.size()]);
    }
}
```

**注意两个细节**：

- `mAnimationPlayer` 用 `LINEAR` 插值器——这意味着 `[0,1]` 是线性时间进度。各子动画各自的 `Interpolator` 在 `Holder.setProgress` 里应用，互不干扰。
- 构造时把整套 `AnimatorSet` 挂上 listener，是为了追踪子动画被 cancel 的状态（不能直接 cancel，因为主控还在跑）。

#### 6.1.3 Holder —— "子动画代理"

```java
public static class Holder {
    public final ValueAnimator anim;
    public final float globalEndProgress;     // 子动画在 [0,1] 中"它自己应该结束"的进度
    public final TimeInterpolator interpolator;
    public ProgressMapper mapper = ProgressMapper.DEFAULT;
    public final SpringProperty springProperty;

    public Holder(Animator animator, float f9, SpringProperty springProperty) {
        ValueAnimator valueAnimator = (ValueAnimator) animator;
        this.anim = valueAnimator;
        this.springProperty = springProperty;
        this.interpolator = valueAnimator.getInterpolator();
        this.globalEndProgress = animator.getDuration() / f9;        // f9 = 总时长
    }

    public void reset() {
        this.anim.setInterpolator(this.interpolator);
        this.mapper = ProgressMapper.DEFAULT;
    }

    public void setProgress(float f9) {
        this.anim.setCurrentFraction(this.mapper.getProgress(f9, this.globalEndProgress));
    }

    public interface ProgressMapper {
        ProgressMapper DEFAULT = (f9, f10) -> f9 > f10 ? 1.0f : f9 / f10;
        float getProgress(float f9, float f10);    // f9=全局进度 [0,1], f10=globalEndProgress
    }
}
```

**Holder.setProgress 核心逻辑**：
1. 全局进度 → 子动画自己的进度（`globalEndProgress` 是"子动画在主时间轴上的归一化结束位置"）
2. 子动画自己的进度 → 子动画的 `Interpolator` → 子动画的当前属性值（由 `setCurrentFraction` 内部触发）

`ProgressMapper` 是个钩子，允许把"全局进度"映射成"子动画进度"——比如某个动画希望在 `progress=0.5` 之后才开始，就可以注入一个 `mapper`。

#### 6.1.4 onAnimationUpdate —— 帧分发

```java
@Override
public void onAnimationUpdate(ValueAnimator valueAnimator) {
    Float f9 = (Float) valueAnimator.getAnimatedValue();
    if (f9 != null) {
        setPlayFraction(f9.floatValue());
    }
}

public void setPlayFraction(float f9) {
    this.mCurrentFraction = f9;
    if (this.mTargetCancelled) return;
    float fBoundToRange = Utilities.boundToRange(f9, 0.0f, 1.0f);
    for (Holder holder : this.mChildAnimations) {
        holder.setProgress(fBoundToRange);     // ★ 同步推给每个子动画
    }
}
```

#### 6.1.5 start / reverse

```java
public void start() {
    this.mAnimationPlayer.setFloatValues(this.mCurrentFraction, 1.0f);
    this.mAnimationPlayer.setDuration(clampDuration(1.0f - this.mCurrentFraction));
    this.mAnimationPlayer.start();           // ★ 只启动主 ValueAnimator
    this.mIsDispatchStartPending = true;
}

public void reverse() {
    this.mAnimationPlayer.setFloatValues(this.mCurrentFraction, 0.0f);
    this.mAnimationPlayer.setDuration(clampDuration(this.mCurrentFraction));
    this.mAnimationPlayer.start();           // ★ 进度反向
    this.mIsDispatchStartPending = false;
}
```

**`reverse` 的精妙之处**：把 `setFloatValues(currentFraction, 0.0f)` + `start()`——主 ValueAnimator 从当前进度反向播到 0，每帧的 `onAnimationUpdate` 自动反向同步所有 Holder。这就是"统一进度驱动"的红利——reverse 不需要写任何额外代码。

#### 6.1.6 dispatchOnStart / dispatchOnEnd / dispatchOnCancel

```java
public AnimatorPlaybackController dispatchOnStart() {
    callListenerCommandRecursively(this.mAnim, (listener, anim) -> listener.onAnimationStart(anim));
    this.mIsDispatchStartPending = true;
    return this;
}
public AnimatorPlaybackController dispatchOnEnd() {
    callListenerCommandRecursively(this.mAnim, /* onAnimationEnd */);
    return this;
}
public AnimatorPlaybackController dispatchOnCancel() {
    callListenerCommandRecursively(this.mAnim, /* onAnimationCancel */);
    return this;
}
```

这些是**业务侧手动驱动生命周期事件**的钩子。`OnAnimationEndDispatcher` 还会再触发一次 `dispatchOnEnd()`，配合上面 listener 的递归调用，转场里的每个 `ObjectAnimator` 的 listener 都会被通知到。

#### 6.1.7 OnAnimationEndDispatcher 与 AnimationSuccessListener

```java
public class OnAnimationEndDispatcher extends AnimationSuccessListener {
    boolean mDispatched;
    @Override public void onAnimationStart(Animator animator) {
        this.mCancelled = false;
        this.mDispatched = false;
    }
    @Override public void onAnimationSuccess(Animator animator) {
        if (this.mDispatched) return;
        AnimatorPlaybackController.this.dispatchOnEnd();
        if (!AnimatorPlaybackController.this.mEndActionMap.isEmpty()) {
            AnimatorPlaybackController.this.mEndActionMap.forEach((k, v) -> v.run());
            AnimatorPlaybackController.this.mEndActionMap.clear();
        }
        this.mDispatched = true;
    }
    @Override public void onAnimationCancel(Animator animator) {
        super.onAnimationCancel(animator);
        if (AnimatorPlaybackController.this.mCancelAction != null) {
            AnimatorPlaybackController.this.mCancelAction.run();
        }
    }
}

// AnimationSuccessListener.java
public abstract class AnimationSuccessListener extends ActualEndAnimListener {
    protected boolean mCancelled = false;
    @Override public void onAnimationCancel(Animator animator) { this.mCancelled = true; }
    @Override public void onAnimationEnd(Animator animator) {
        if (this.mCancelled) return;
        onAnimationSuccess(animator);
    }
    public abstract void onAnimationSuccess(Animator animator);
}
```

**这是 Launcher 的一个常见模式**："success" 必须在**不是被 cancel** 的情况下才算数。标准 `Animator.AnimatorListener` 的 `onAnimationEnd` 在 cancel 时也会触发（cancel 触发的就是 cancel → 紧接着 end），Launcher 用 `mCancelled` 标志位过滤掉这种伪 end。

#### 6.1.8 forceFinishIfCloseToEnd / forceFinishIfNeed

```java
public void forceFinishIfCloseToEnd() {
    if (!this.mAnimationPlayer.isRunning() || this.mAnimationPlayer.getAnimatedFraction() <= 0.95f) return;
    this.mAnimationPlayer.end();             // 进度已超过 95%，强结束
}

public void forceFinishIfNeed() {
    ValueAnimator valueAnimator = this.mAnimationPlayer;
    if (valueAnimator == null || !valueAnimator.isRunning()) return;
    this.mAnimationPlayer.end();
}
```

`forceFinishIfCloseToEnd` 是为了优化性能——动画已经几乎完成时，没必要再跑几 ms 的渐进，直接 end 跳到 1.0。

### 6.2 PendingAnimation —— 转场动画的"构建器"

文件：`com/android/launcher3/anim/PendingAnimation.java`

```java
public class PendingAnimation implements PropertySetter {
    private final AnimatorSet mAnim;
    private AnimatorPlaybackController mAnimatorPlaybackController;
    private final long mDuration;
    private ValueAnimator mProgressAnimator;                // 单独的进度回调 ValueAnimator
    private final ArrayList<AnimatorPlaybackController.Holder> mAnimHolders = new ArrayList<>();
    public boolean isAnimFinished = false;

    public PendingAnimation(long j8) {
        this.mDuration = j8 <= 0 ? 0L : j8;
        AnimatorSet animatorSet = new AnimatorSet();
        this.mAnim = animatorSet;
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator animator) { PendingAnimation.this.isAnimFinished = true; }
            @Override public void onAnimationEnd(Animator animator) { PendingAnimation.this.isAnimFinished = true; }
            @Override public void onAnimationStart(Animator animator) { PendingAnimation.this.isAnimFinished = false; }
        });
    }

    public void addFloat(T t, FloatProperty<T> p, float from, float to, TimeInterpolator ip) {
        ObjectAnimator oa = ObjectAnimator.ofFloat(t, p, from, to);
        oa.setInterpolator(ip);
        add(oa);
    }
    public void addEndListener(Consumer<Boolean> consumer) {
        if (this.mProgressAnimator == null) this.mProgressAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        this.mProgressAnimator.addListener(AnimatorListeners.forEndCallback(consumer));
    }
    public void addOnFrameCallback(Runnable runnable) {
        addOnFrameListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) { runnable.run(); }
        });
    }
    public void addOnFrameListener(ValueAnimator.AnimatorUpdateListener l) {
        if (this.mProgressAnimator == null) this.mProgressAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        this.mProgressAnimator.addUpdateListener(l);
    }
    ...
}
```

**它是 `AnimatorPlaybackController` 的"上游构造器"**：用 `addFloat` 这种 DSL 风格的方法收集所有要同步的属性动画，最后 `build()` 出来一个 `AnimatorPlaybackController`。

`mProgressAnimator` 是**专门给"帧回调"和"结束回调"用的辅助 ValueAnimator**——它不参与视觉属性变化，只用来挂监听器（onFrame/end）。这是个非常聪明的解耦：属性动画的 listener 走 `AnimatorSet`，帧/结束回调走独立的 `mProgressAnimator`，互不干扰。

### 6.3 AsyncValueAnimator —— 跨 Looper 的 ValueAnimator

文件：`com/android/quickstep/util/animation/AsyncValueAnimator.java`

#### 6.3.1 解决的问题

标准 `ValueAnimator.start()` 有两个硬约束：
1. **必须跑在有 Looper 的线程**：`if (Looper.myLooper() == null) throw new AndroidRuntimeException(...)`
2. **后续帧必须在同一线程**：因为 `AnimationHandler` 是 ThreadLocal，跨线程的 doFrame 不会被触发。

但在 Launcher 里很多场景下动画的触发点不在主线程：
- `LauncherAnimationRunner.onAnimationStart` 在 Binder 线程被 system_server 调用
- Recents 转场里 `onRecentsAnimationStart` 由 system_server 跨进程触发

直接 `valueAnimator.start()` 会失败。`AsyncValueAnimator` 把"启动调用" marshal 到指定的 LooperExecutor 上，再 `super.start()`。

#### 6.3.2 实现

```java
public final class AsyncValueAnimator extends ValueAnimator {
    private LooperExecutor mAnimLooperExecutor;
    private AsyncAnimCallbacks mAsyncAnimCallbacks;
    private AtomicBoolean mIsEnd;

    public AsyncValueAnimator() {
        LooperExecutor MAIN_EXECUTOR = Executors.MAIN_EXECUTOR;
        this.mAnimLooperExecutor = MAIN_EXECUTOR;
        this.mAsyncAnimCallbacks = new AsyncAnimCallbacks();
        this.mIsEnd = new AtomicBoolean(false);
        // 内部 listener 把标准 AnimatorListener 事件转发到 mAsyncAnimCallbacks
        addListener(new Animator.AnimatorListener(this, this) {
            @Override public void onAnimationCancel(Animator animator) {
                if (this.this$0.mIsEnd.get()) return;
                this.this$0.mAsyncAnimCallbacks.onAnimationCancel(null);
            }
            @Override public void onAnimationEnd(Animator animator) {
                if (this.this$0.mIsEnd.compareAndSet(false, true)) {
                    this.this$0.mAsyncAnimCallbacks.onAnimationEnd(null);
                }
            }
            @Override public void onAnimationStart(Animator animator) {
                if (this.this$0.mIsEnd.get()) return;
                this.this$0.mAsyncAnimCallbacks.onAnimationStart(null);
            }
        });
    }

    @Override
    public void start() {
        Looper looper = this.mAnimLooperExecutor.getLooper();
        if (looper != null && looper.isCurrentThread()) {
            super.start();
        } else {
            // 通过 LooperExecutor marshal 到目标线程
            this.mAnimLooperExecutor.execute(() -> super.start());
        }
    }
    @Override public void cancel() { ... /* 同 start 的线程判断 */ }
    @Override public void end()   { ... /* 同 start 的线程判断 */ }

    public final void addAnimatorListener(NullableAnimatorListener l) {
        if (l == null) return;
        this.mAsyncAnimCallbacks.addListeners(l);
    }
    public final void removeAnimatorListener(NullableAnimatorListener l) {
        this.mAsyncAnimCallbacks.removeListener(l);
    }
    public final void setExecutor(LooperExecutor executor) {
        this.mAnimLooperExecutor = executor;
    }
}
```

#### 6.3.3 AsyncAnimCallbacks —— 跨线程 listener 派发

文件：`com/android/quickstep/util/animation/AsyncAnimCallbacks.java`

```java
public final class AsyncAnimCallbacks {
    private final ArrayList<NullableAnimatorListener> mAnimListeners = new ArrayList<>();
    private int mAnimationId = -1;
    private CustomRectFSpringAnim.AnimType mAnimType;

    public final void addListeners(NullableAnimatorListener listener) { ... }
    public final void removeListener(NullableAnimatorListener listener) {
        int idx = this.mAnimListeners.indexOf(listener);
        if (idx >= 0) this.mAnimListeners.set(idx, null);   // 同样用"置 null"的延迟删除
    }
    public final void onAnimationCancel(Animator animator) {
        runOnMainThread(() -> { /* 遍历调用 onAnimationCancel */ });
    }
    public final void onAnimationEnd(Animator animator) {
        Trace.traceBegin(8L, "#" + mAnimationId + "-" + mAnimType + "-End");
        runOnMainThread(() -> { /* 遍历调用 onAnimationEnd */ });
    }
    public final void onAnimationStart(Animator animator) {
        Trace.traceBegin(8L, "#" + mAnimationId + "-" + mAnimType + "-Start");
        runOnMainThread(() -> { /* 遍历调用 onAnimationStart */ });
    }
    public final void runOnMainThread(Runnable runnable) {
        LooperExecutor looperExecutor = Executors.MAIN_EXECUTOR;
        if (looperExecutor.getLooper().isCurrentThread()) {
            runnable.run();
        } else {
            looperExecutor.execute(runnable);
        }
    }
}
```

**Listener 跨线程安全**：因为 `AsyncValueAnimator` 自身的 listener 在动画 Looper 上被调，而业务方可能在别的线程 register listener，所以把 listener 调用统一 marshal 到 `MAIN_EXECUTOR`（主线程）上执行。

`Trace.traceBegin`/`traceEnd` 配合 `mAnimationId`/`mAnimType` 让每条动画事件都能在 Perfetto/Systrace 上溯源——这是 OPPO 在动效性能追踪上做的功夫。

### 6.4 OplusSpringObjectAnimator —— SpringProperty 装饰器实现"渐进切换"

文件：`com/oplus/quickstep/anim/OplusSpringObjectAnimator.java`

#### 6.4.1 设计动机：和 OplusValueAnimator 完全不同的双驱动

Launcher 手势动画经常需要这种 UX：

```
用户手指拖动 → 拖动跟随 (1对1 时序)
       │
       └─ 抬手 → 时序动画过渡到大致位置 → 切到 Spring 收敛到精确位置
```

时序动画 (`ValueAnimator`) 控制准确时长，Spring (`SpringAnimation`) 控制物理收敛。如果让两者各跑各的，在切换点会有视觉跳变。`OplusSpringObjectAnimator` 通过 `SpringProperty` 装饰器实现"在 `ObjectAnimator` 推进过程中悄悄切到 Spring"的无缝衔接。

**关键定位**：它是 **§6.5 OplusValueAnimator 的姐妹实现**，但**两者是完全不同的双驱动模式**——一定要区分：

| 维度 | `OplusSpringObjectAnimator` | `OplusValueAnimator` |
|---|---|---|
| 真实驱动器 | 内部 `ObjectAnimator`（即标准 `ValueAnimator`） | 外部传入的 `ObjectAnimator timeController` |
| `this` 的角色 | 真正的 `ValueAnimator`，被业务方使用 | **wrapper**：把 start/cancel/end/listener 委托给 timeController |
| Spring 切换机制 | `SpringProperty.setValue` 根据 useSpring 转发 | 无 Spring，纯时序 |
| `addUpdateListener` 行为 | 监听自身帧 | 监听 timeController（如果存在）的帧 |

#### 6.4.2 实现：SpringProperty 装饰器

```java
public class OplusSpringObjectAnimator<T> extends ValueAnimator {
    private final ObjectAnimator mObjectAnimator;
    private final SpringAnimation mSpring;
    private final SpringProperty<T> mProperty;

    public static class SpringProperty<T> extends FloatProperty<T> {
        final FloatProperty<T> mProperty;       // 业务方传入的真实属性
        final SpringAnimation mSpring;           // 物理引擎驱动器
        boolean useSpring = false;               // 切换标志

        public SpringProperty(FloatProperty<T> floatProperty, SpringAnimation springAnimation) {
            super(floatProperty.getName());
            this.mProperty = floatProperty;
            this.mSpring = springAnimation;
        }

        @Override public void setValue(T t, float v) {
            if (this.useSpring) {
                this.mSpring.animateToFinalPosition(v);   // ★ 切到 Spring 后由 Spring 驱动
            } else {
                this.mProperty.setValue(t, v);            // ★ 默认仍是 ObjectAnimator 直接赋值
            }
        }

        public void switchToSpring() { this.useSpring = true; }   // ★ 切换点
    }

    public OplusSpringObjectAnimator(T t, FloatProperty<T> floatProperty, float minChange, float damping, float stiffness, float... fArr) {
        SpringAnimation springAnimation = new SpringAnimation(t, FloatPropertyCompat.createFloatPropertyCompat(floatProperty));
        this.mSpring = springAnimation;
        springAnimation.setMinimumVisibleChange(minChange);
        springAnimation.setSpring(new SpringForce(0.0f).setDampingRatio(damping).setStiffness(stiffness));
        springAnimation.setStartVelocity(0.01f);
        SpringProperty<T> springProperty = new SpringProperty<>(floatProperty, springAnimation);
        this.mProperty = springProperty;
        ObjectAnimator objectAnimatorOfFloat = ObjectAnimator.ofFloat(t, springProperty, fArr);
        this.mObjectAnimator = objectAnimatorOfFloat;
        ...
    }
}
```

**关键点**：
- `SpringProperty` 是个"装饰器 FloatProperty"，`setValue` 时根据 `useSpring` 标志决定走 Spring 还是直接赋值。
- `switchToSpring()` 一旦调用，之后每次 `ObjectAnimator` 推进都会转发到 Spring，`SpringAnimation.animateToFinalPosition(v)` 会让 Spring 从当前位置收敛到 `v`。

#### 6.4.3 SpringAnimation 的内部帧驱动（完整）

文件：`androidx/dynamicanimation/animation/SpringAnimation.java`

```java
public final class SpringAnimation extends DynamicAnimation<SpringAnimation> {
    private boolean mEndRequested;       // skipToEnd 标志
    private float mPendingPosition;      // Float.MAX_VALUE 表示无 pending
    private SpringForce mSpring;

    public void animateToFinalPosition(float f9) {
        if (isRunning()) {
            this.mPendingPosition = f9;       // 动画中：只更新目标，下一帧由 updateValueAndVelocity 处理
            return;
        }
        ...
        start();
    }

    public boolean updateValueAndVelocity(long dt) {
        if (this.mEndRequested) {
            // 强制结束：直接跳到目标
            ...
            this.mValue = this.mSpring.getFinalPosition();
            this.mVelocity = 0.0f;
            this.mEndRequested = false;
            return true;
        }

        if (this.mPendingPosition != Float.MAX_VALUE) {
            // ★ 动画中改了 finalPosition: 用 2 阶段过渡软切
            long j9 = dt / 2;
            // 第一阶段：用旧 finalPosition 走 dt/2
            DynamicAnimation.MassState massStateUpdateValues =
                this.mSpring.updateValues(this.mValue, this.mVelocity, j9);
            // 切到新 finalPosition
            this.mSpring.setFinalPosition(this.mPendingPosition);
            this.mPendingPosition = Float.MAX_VALUE;
            // 第二阶段：用新 finalPosition 走 dt/2
            DynamicAnimation.MassState massStateUpdateValues2 =
                this.mSpring.updateValues(massStateUpdateValues.mValue, massStateUpdateValues.mVelocity, j9);
            this.mValue = massStateUpdateValues2.mValue;
            this.mVelocity = massStateUpdateValues2.mVelocity;
        } else {
            // 正常情况: 弹簧微分方程 dt 积分
            DynamicAnimation.MassState massStateUpdateValues3 =
                this.mSpring.updateValues(this.mValue, this.mVelocity, dt);
            this.mValue = massStateUpdateValues3.mValue;
            this.mVelocity = massStateUpdateValues3.mVelocity;
        }
        // 限幅到 min/max
        float fMax = Math.max(this.mValue, this.mMinValue);
        this.mValue = fMax;
        float fMin = Math.min(fMax, this.mMaxValue);
        this.mValue = fMin;
        if (!isAtEquilibrium(fMin, this.mVelocity)) {
            return false;       // 未达平衡, 继续
        }
        // 达到平衡, 结束
        this.mValue = this.mSpring.getFinalPosition();
        this.mVelocity = 0.0f;
        return true;
    }
}
```

**两大关键设计**：

1. **2 阶段过渡（Half-step transition）**：当 `animateToFinalPosition(v)` 在动画运行中被调用，本帧的 dt 被拆成两半，先用旧 finalPosition 走 dt/2，再切到新 finalPosition 走 dt/2。这样从"指向 A"切到"指向 B"时位置不会跳变——速度从 A 终点平滑过渡到 B 起点。这是 SpringAnimation 比裸物理积分"更聪明"的地方。

2. **平衡点检测（isAtEquilibrium）**：弹簧是个衰减振动系统，速度和位置都会趋近 0。`isAtEquilibrium(value, velocity)` 用 `getValueThreshold() = mMinVisibleChange * 0.75f` 作为阈值判断两者都接近 0 后才认为静止，避免抖动不收敛。

#### 6.4.4 DynamicAnimation 的帧调度（与 ValueAnimator 关键差异）

文件：`androidx/dynamicanimation/animation/DynamicAnimation.java`

```java
public abstract class DynamicAnimation<T extends DynamicAnimation<T>>
        implements AnimationHandler.AnimationFrameCallback {
    ...

    public boolean doAnimationFrame(long j8) {
        long j9 = this.mLastFrameTime;
        if (j9 == 0) {
            // 第一次: 记录时间 + 设置初始值
            this.mLastFrameTime = j8;
            setPropertyValue(this.mValue);
            return false;
        }
        this.mLastFrameTime = j8;
        boolean zUpdateValueAndVelocity = updateValueAndVelocity(j8 - j9);   // ★ dt = 两帧时间差
        // 限幅
        float fMin = Math.min(this.mValue, this.mMaxValue);
        this.mValue = fMin;
        float fMax = Math.max(fMin, this.mMinValue);
        this.mValue = fMax;
        setPropertyValue(fMax);
        if (zUpdateValueAndVelocity) {
            endAnimationInternal(false);
        }
        return zUpdateValueAndVelocity;
    }

    private void endAnimationInternal(boolean z8) {
        this.mRunning = false;
        AnimationHandler.getInstance().removeCallback(this);
        ...
        for (int i9 = 0; i9 < this.mEndListeners.size(); i9++) {
            if (this.mEndListeners.get(i9) != null) {
                this.mEndListeners.get(i9).onAnimationEnd(this, z8, this.mValue, this.mVelocity);
            }
        }
        removeNullEntries(this.mEndListeners);     // ★ 与 core.animation 不同: 立即清理 null
    }
}
```

**与 `ValueAnimator` 的三大差异**：

| 维度 | `ValueAnimator.doAnimationFrame` | `DynamicAnimation.doAnimationFrame` |
|---|---|---|
| 时间基准 | 绝对时间 `currentTime - mStartTime` | **帧间 dt** `j8 - mLastFrameTime` |
| 线程约束 | 任意 Looper 线程（受 AnimationHandler ThreadLocal 约束） | **`@MainThread`**，抛 `AndroidRuntimeException` |
| Listener 清理 | `mListDirty + cleanUpList`（下一帧开头异步清理） | `removeNullEntries`（**通知后立即**清理） |
| `addAnimationFrameCallback` 签名 | 单参 `(callback)` | **双参 `(callback, delayedStartTime)`**（支持延迟启动） |
| OnAnimationEnd 签名 | `onAnimationEnd(animator, isReversing)` | `onAnimationEnd(anim, isCancel, value, velocity)`（多带 2 个参数） |

**dt vs 绝对时间的取舍**：
- `ValueAnimator` 用绝对时间：保证"播放到 50% 时一定是真实进度 50%"，哪怕丢了一帧
- `DynamicAnimation` 用帧间 dt：物理引擎基于 dt 积分是数值最稳定的方式（变步长积分器自动适应帧率波动），但**丢帧时速度计算会包含跳变**——所以 SpringAnimation 不在乎"播到 X% 应该是什么值"，只在乎"从上次到这次经历了多少时间，速度位置该如何演化"

`removeNullEntries` 立即清理 vs `mListDirty` 异步清理的取舍：dynamicanimation 是面向单帧的物理求解，listener 数量通常很少（1-3 个），立即清理的开销可以忽略；而 core.animation 是通用动画框架，listener 可能几十个并迭代中嵌套修改，所以延后清理更安全。

SpringAnimation 继承的 `DynamicAnimation` 会用 §3 提到的 `dynamicanimation.animation.AnimationHandler` 走另一条独立的 Choreographer 调度链路——它的 `addAnimationFrameCallback` 第二参数是 `delayedStartTime`（毫秒），可以延迟注册 Choreographer。

**两套 AnimationHandler 并存**：
- `core.animation.AnimationHandler` 跑 `OplusSpringObjectAnimator.this`（extends ValueAnimator）和内部的 `mObjectAnimator`
- `dynamicanimation.animation.AnimationHandler` 跑 `mSpring`（`SpringAnimation`）

切换点 `switchToSpring()` 后，`mObjectAnimator` 仍按原计划跑完（每次 setValue 都通过 `SpringProperty.setValue` 转发到 Spring），同时 Spring 接管实际的视觉推进。

### 6.5 OplusValueAnimator —— timeController 委托模式 + 续行动画

文件：`com/oplus/quickstep/utils/OplusValueAnimator.java`

#### 6.5.1 设计动机：续行动画（continuation anim）

Launcher 里很多手势动画需要"中断后续行"模式：

```
[用户手指拖动 → 拖动跟随 (1:1 时序) — 在 OplusValueAnimator 内部]
[抬手 → generateContinuationAnim(currentFraction, duration) — 复用一个 anim 状态]
        │
        └─ 从 currentFraction 播到 1.0
```

和 `OplusSpringObjectAnimator` 不同的是，`OplusValueAnimator` 是**纯粹的时序动画 wrapper**——它**自己不跑动画**，而是把 start/cancel/end/listener 全部委托给传入的 `ObjectAnimator timeController`。`this` 自身仅作为"命名 + 日志 + AnimParam 持有"的壳。

#### 6.5.2 完整实现

```java
public final class OplusValueAnimator<T> extends ValueAnimator {
    private final String name;                  // 动画名字（用于日志/trace）
    private final AnimParam<T> param;           // 持有 startValue, endValue, interpolator, duration 等
    private final ObjectAnimator timeController; // 真实的驱动器（可为 null）

    // CURRENT_FRACTION 是一个 FloatProperty<OplusValueAnimator<?>>，
    // 当 generateContinuationAnim 用 timeController 驱动 CURRENT_FRACTION 时
    // 会反过来回调 setCurrentFraction(fraction)
    private static final FloatProperty<OplusValueAnimator<?>> CURRENT_FRACTION = new FloatProperty<OplusValueAnimator<?>>() {
        @Override public Float get(OplusValueAnimator<?> anim) {
            AnimParam<?> p = anim.getParam();
            return p == null ? -1.0f : p.getCurrentFraction();
        }
        @Override public void setValue(OplusValueAnimator<?> anim, float f) {
            if (anim != null) anim.setCurrentFraction(f);
        }
    };

    // 核心委托模式: 所有生命周期操作都转发给 timeController（如果存在）
    @Override public void start() {
        LogUtils.i(getTag(), this.timeController == null ? "native start" : "timeController start");
        if (this.timeController != null) this.timeController.start();
        else super.start();
    }
    @Override public void cancel() { /* 同 start 的委托模式 */ }
    @Override public void end()    { /* 同 start 的委托模式 */ }
    @Override public void pause()  { /* 同 start 的委托模式 */ }

    // 自监听帧更新
    public OplusValueAnimator(String name, AnimParam<T> param, ObjectAnimator timeController) {
        this.name = name;
        this.param = param;
        this.timeController = timeController;
        addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator it) {
                param.getValueApplicator().applyValue(it.getAnimatedValue());   // ★ 应用值到 target
            }
        });
    }

    // 静态工厂：生成一个标准 OplusValueAnimator
    public static final <T> ValueAnimator generateAnim(String name, AnimParam<T> animParam) {
        return INSTANCE.generateAnim(name, animParam, null);
    }

    // 续行动画 API（最关键的工厂方法）
    public static final <T> OplusValueAnimator<T> generateContinuationAnim(OplusValueAnimator<T> anim, long continuationAnimDuration) {
        if (anim == null) return null;
        // ★ 从已有 anim 的 RecordInputInterpolator 拿到"用户输入到了哪一帧"
        RecordInputInterpolator rec = anim.getParam().getInterpolator() instanceof RecordInputInterpolator
            ? (RecordInputInterpolator) anim.getParam().getInterpolator() : null;
        if (rec != null) anim.getParam().setCurrentFraction(rec.getInputed());

        float currentFraction = anim.getParam().getCurrentFraction();
        if (currentFraction < 0.0f || currentFraction >= 1.0f) {
            LogUtils.i("", "generateContinuationAnim fail, currentFraction is " + currentFraction);
            return null;
        }
        ObjectAnimator objectAnimator = new ObjectAnimator();
        OplusValueAnimator<T> newAnim = generateAnim("Continuation-" + anim.getName(), AnimParam.copy(anim.getParam()), objectAnimator);
        // ★ 用 timeController 驱动 CURRENT_FRACTION 属性（即反过来修改新 anim 的 fraction）
        objectAnimator.setTarget(newAnim);
        objectAnimator.setProperty(OplusValueAnimator.INSTANCE.getCURRENT_FRACTION());
        objectAnimator.setFloatValues(currentFraction, 1.0f);                  // 从当前播到 1.0
        if (continuationAnimDuration > 0) objectAnimator.setDuration(continuationAnimDuration);
        objectAnimator.setInterpolator(new LinearInterpolator());
        return newAnim;
    }
}
```

#### 6.5.3 AnimParam —— Kotlin data class 的力量

`OplusValueAnimator` 用 Kotlin data class 把"动画的所有配置"封装成一个值对象：

```java
public static final /* data */ class AnimParam<E> {
    private final E startValue;
    private final E endValue;
    private final TypeEvaluator<E> typeEvaluator;
    private final ValueApplicator valueApplicator;
    private float currentFraction = -1.0f;
    private long duration;
    private TimeInterpolator interpolator;

    public AnimParam(E start, E end, TypeEvaluator<E> evaluator, ValueApplicator applicator) { ... }

    // data class copy() 关键作用: 续行动画时复用参数但生成新对象
    public static final <F> AnimParam<F> copy(AnimParam<F> ap) {
        AnimParam<F> animParam = new AnimParam<>(ap.getStartValue(), ap.getEndValue(), ap.getTypeEvaluator(), ap.getValueApplicator());
        animParam.setDuration(ap.getDuration());
        animParam.setInterpolator(ap.getInterpolator());
        animParam.setCurrentFraction(ap.getCurrentFraction());
        return animParam;
    }
}
```

**`AnimParam.copy()` 的妙处**：`generateContinuationAnim` 复用原 anim 的所有配置（interpolator / duration / evaluator / value applicator），只换 fraction——这是 Kotlin data class `copy()` 在 Java 反编译后的呈现，业务代码无需关心"复制时要带哪些字段"。

#### 6.5.4 ValueApplicator —— 自定义值的应用

```java
public interface ValueApplicator {
    void applyValue(Object value);   // ★ 业务方实现: 怎么把动画值应用到自己想要的目标
}
```

这个 lambda 接口让业务方自定义"每帧值怎么用"——比如同时更新 Translation X 和 Y、或者既更新 UI 还要更新模型层。比 `PropertyValuesHolder` 灵活得多。

#### 6.5.5 与 §6.4 OplusSpringObjectAnimator 的核心区别

> **核心洞察（Agent 4 独立分析的贡献）**：
> 这两个 Oplus 类**都在解决"手势中断后续行"问题**，但走的路径**本质不同**——
> - `OplusSpringObjectAnimator` 是**同一对象内 swap 驱动源**（SpringProperty 装饰器切 Spring）
> - `OplusValueAnimator` 是**两对象接力**（老对象已完成 fraction → 新对象 timeController 从该 fraction 跑到 1.0）

| 维度 | `OplusValueAnimator` | `OplusSpringObjectAnimator` |
|---|---|---|
| **`this` 角色** | **wrapper**：把操作委托给 timeController | **真正的 ValueAnimator**，自身参与帧推进 |
| **真实驱动器** | `timeController` (ObjectAnimator)；null 时 this 自身跑 | `mObjectAnimator` (ObjectAnimator) **始终在跑**；切换期间 `mSpring` 接管 |
| **委托模式** | **完整委托**（start/cancel/end/listener） | **部分委托**（仅通过 SpringProperty.setValue 转发） |
| **Spring 集成** | 无（纯时序） | 有（Spring 接管 setValue） |
| **核心 API** | `generateContinuationAnim()` 实现两对象接力 | `switchToSpring()` 实现同一对象 swap |
| **驱动切换路径** | 老对象 → `RecordInputInterpolator.getInputed()` 拿当前 fraction → 新对象 timeController `setTarget(newAnim)` + `setProperty(CURRENT_FRACTION)` | `mProperty.switchToSpring()` 切布尔门 → 下一帧起 ObjectAnimator 的 setValue 走 `mSpring.animateToFinalPosition(newTarget)` |
| **跨两套 AnimationHandler** | 否（timeController 单一驱动） | **是**（mObjectAnimator 跑 core，mSpring 跑 dynamicanimation） |
| **位置表达** | 线性 `lerp(start, end, t)` 从 fraction → 1.0 | 物理 `x(t)` 收敛到 finalPosition（弹簧动力学） |
| **命名机制** | 每个动画有 `name`，日志里直接看到 `${hash}-${name}` | 没有内置 name |
| **View 写入器** | `AnimParam.valueApplicator.applyValue(value)`（完全可控） | 通过原 `FloatProperty` 或 Spring 自带 `setPropertyValue` |
| **Listener 来源** | 业务方注册 → 直接转发到 `timeController.addListener` | 业务方注册到 `this.mListeners`，内部 Adapter 转发（`tryEnding` 双标志收口） |
| **运行线程** | 主线程（timeController 由 AOSP 调度） | 主线程（`Handler(Looper.getMainLooper()).postDelayed` 触发 mSpring） |

**完整的链路差异图（Agent 4 还原）**：

```
OplusSpringObjectAnimator（同一对象 swap）：
AOSP ObjectAnimator 每帧
    ↓ ObjectAnimator.ofFloat(t, springProperty, fArr) 触发的 setValue(t, f)
SpringProperty.setValue()
    ├─ if (useSpring)        // 切换后
    │     mSpring.animateToFinalPosition(f)
    │         ↓ SpringAnimation 内部帧循环 → 物理积分 → setPropertyValue(v)
    │             → mProperty.setValue(target, v)         (SpringAnimation 通过 androidx 间接写目标)
    └─ else                  // 默认
          mProperty.setValue(t, f)                          (业务方原 FloatProperty 直写)


OplusValueAnimator（两对象接力）：
timeController (ObjectAnimator, setter=CURRENT_FRACTION, target=newAnim)
    ↓ 每帧调用 setValue(newAnim, fraction_in_[f,1])
OplusValueAnimator.CURRENT_FRACTION.setValue(newAnim, f)
    ↓ 转发
newAnim.setCurrentFraction(f)
    ├─ super.setCurrentFraction(f) → AOSP 触发 onAnimationUpdate(value)
    │       ↓ addUpdateListener 的 lambda
    │       newAnim.param.getValueApplicator().applyValue(it.getAnimatedValue())
    │           ↓ 业务方把写值落进视图
    └─ param.setCurrentFraction(f) → 备份
```

**关键的设计哲学差异**：
- `OplusSpringObjectAnimator`：**driver swap**，**不**重启 ObjectAnimator。ObjectAnimator 还在跑、还在每帧调 `setValue`，但每个 setValue 都直接喂给 spring 的目标。这是 OPPO 的精巧设计——完全不需要重启动画。
- `OplusValueAnimator.generateContinuationAnim`：**对象接力**，老对象的 fraction 通过 `RecordInputInterpolator.getInputed()`（纯副作用缓存的 input）取出，新建对象用 timeController 写 `CURRENT_FRACTION` FloatProperty → `setCurrentFraction` → AOSP `onAnimationUpdate` → lambda 转给 `valueApplicator.applyValue`。
- **`RecordInputInterpolator`**（`com/oplus/quickstep/utils/RecordInputInterpolator.java`）：纯副作用缓存，`getInterpolation(input)` 时把 input 写到 `this.inputed`，`getInputed()` 让续行方读取。这是 OPPO 设计的"手势跟随 → 续行"输入记录器。

**为什么会并存两种模式**：
- **`OplusValueAnimator`** 解决"抬手后续行到某位置"这种**续行动画**场景——核心是 fraction 复用 + AnimParam copy + 纯线性播放
- **`OplusSpringObjectAnimator`** 解决"手势中断后切物理收敛"这种**物理接管**场景——核心是 Spring 接管 setValue + 弹性手感

两者职责正交，按需选用；前者适合"速度为零的精确续行"，后者适合"有惯性要 Spring 收尾"。

#### 6.5.6 三种"双驱动"模式总览

> 把两个 Oplus 类的设计哲学 + 原生 SpringAnimation 合并到一个视角看。

| 模式 | 场景 | 关键代码 | 文件:行 |
|---|---|---|---|
| **① Spring 切换**（同一对象 swap） | 手势中断 → 让弹簧接管收敛 | `OplusSpringObjectAnimator.startSpring()` → `mProperty.switchToSpring()` + `postDelayed(animateToFinalPosition)` | `OplusSpringObjectAnimator.java:290-307` |
| **② timeController 委托**（两对象接力） | 续行动画：从已积攒的 fraction 跑到 1.0 | `OplusValueAnimator.generateContinuationAnim()` → `timeController.setTarget(newAnim)` + `setProperty(CURRENT_FRACTION)` | `OplusValueAnimator.java:88-120` |
| **③ Spring 直接驱动** | 独立弹簧动画（业务直接 `new SpringAnimation(...)`） | `SpringAnimation.animateToFinalPosition()` + 物理积分循环 | `SpringAnimation.java:46-54` + `:117-153` |
| **④ 原生 ValueAnimator 兜底** | OplusValueAnimator 业务方传 `timeController=null` | 直接 `super.start()` / `super.cancel()` | `OplusValueAnimator.java:218, 214` |
| **⑤ AOSP 续行** | 标准 AOSP ValueAnimator 续行 | `setCurrentFraction(f)` / `setCurrentPlayTime(ms)` | inherited from ValueAnimator |

### 6.6 ChoreographerUtil —— N 帧延迟回调

文件：`com/oplus/quickstep/utils/ChoreographerUtil.java`

```kotlin
public final class ChoreographerUtil {
    @JvmStatic
    public static final void postFrameCallbackDelay(final Runnable callback, int count) {
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long j8) {
                if (--count <= 0) {
                    callback.run();
                } else {
                    Choreographer.getInstance().postFrameCallback(this);   // 自己再 post 一次
                }
            }
        });
    }
}
```

**5 行代码实现"等 N 帧"**：

Launcher 里很多场景需要精确对齐 VSYNC 的延迟：
- 等首帧画完再触发动画（避免视觉突变）
- 等 Spring 收敛完成再隐藏元素
- 等 Recents 转场"位置稳定"后再开始下一段

比 `Handler.postDelayed(runnable, n*16)` 更准时——后者会按 wall clock 而不是 VSYNC 触发，可能比期望早/晚。

### 6.7 LauncherAnimationRunner —— Binder → UI 跨进程动画入口

文件：`com/android/launcher3/LauncherAnimationRunner.java`

#### 6.6.1 角色

`LauncherAnimationRunner` 是 Launcher **作为 system_server 的远程动画执行者**的入口。流程：

```
system_server (WindowManager)
       │ onAnimationStart(transit, targets, finishedCallback)
       ▼ (Binder)
LauncherAnimationRunner.onAnimationStart (BinderThread)
       │
       ▼ post 到 mHandler (UIThread)
lambda$onAnimationStart$4
       │
       ▼ 构造 AnimationResult + 调用 Factory.onCreateAnimation
RemoteAnimationFactory.onCreateAnimation
       │
       ├ 创建 AnimatorSet
       └ AnimationResult.setAnimation(animatorSet, ...)
              │
              ▼ AnimatorSet.start() → ... → Choreographer.doFrame
```

#### 6.6.2 AnimationResult —— 一次动画的"容器"

```java
public static final class AnimationResult {
    private final Runnable mASyncFinishRunnable;
    private AnimatorSet mAnimator;
    private LooperExecutor mAsyncFinishExecutor;       // ★ finish 线程可指定
    private RemoteAnimationTargetCompat.BreakParam mBreakParam;
    private boolean mFinished;
    private boolean mInitialized;
    private MultiAnimatorSet mMultiAnimatorSet;
    private Runnable mOnCompleteCallback;
    private final Runnable mSyncFinishRunnable;

    @UiThread
    private void finish() {
        if (this.mFinished) return;
        this.mSyncFinishRunnable.run();
        LooperExecutor ux_task_executor = this.mAsyncFinishExecutor;
        if (ux_task_executor != null) {
            ux_task_executor.execute(this.mASyncFinishRunnable);   // ★ 在指定线程上异步 finish
        } else {
            this.mASyncFinishRunnable.run();                       // ★ 默认同步 finish
        }
        this.mFinished = true;
    }

    @UiThread
    public void setAnimation(AnimatorSet animatorSet, Context context, Runnable runnable, boolean z8) {
        if (this.mInitialized) throw new IllegalStateException("Animation already initialized");
        this.mInitialized = true;
        this.mAnimator = animatorSet;
        this.mOnCompleteCallback = runnable;
        try {
            if (animatorSet == null) { finish(); return; }
            if (this.mFinished) {
                animatorSet.start();                // ★ 已经被 finish，要求立刻启动并结束
                ...
            } else {
                animatorSet.start();                // ★ 正常启动
                ...
            }
        } finally {
            Trace.endSection();
        }
    }
}
```

**关键设计**：
- **finish 线程可指定**（`setAsyncFinishExecutor`）：如果动画跑在某个子 LooperExecutor 上，finish 也可以在该 Looper 上跑，避免主线程被阻塞。
- **`mFinished` 已置位才 setAnimation**：`animatorSet.start()` 后会被立即被后面接的 finish 收尾——这是"动画过程中 system_server 提前结束转场"的恢复路径。

#### 6.6.3 BinderThread → UiThread 切换

```java
@Override
@BinderThread
public void onAnimationStart(int i, RemoteAnimationTarget[] t1, RemoteAnimationTarget[] t2, RemoteAnimationTarget[] t3,
                              Runnable runnable, RemoteAnimationTargetCompat.BreakParam breakParam) {
    Runnable marshal = () -> lambda$onAnimationStart$4(i, t1, runnable, breakParam, t2, t3);
    if (this.mStartAtFrontOfQueue && this.mHandler.getLooper() == Looper.myLooper()) {
        marshal.run();                  // 立即执行（前台队列模式）
    } else {
        Utilities.postAsyncCallback(this.mHandler, marshal);   // post 到 UI thread
    }
}

@Override
@BinderThread
public void onAnimationCancelled() {
    Utilities.postAsyncCallback(this.mHandler, () -> {
        finishExistingAnimation();
        getFactory().onAnimationCancelled();
    });
}
```

**为什么必须 marshal 到 UI thread**：
1. UI 操作必须在主线程（修改 View 属性、StartTouchTarget、布局等）。
2. `AnimatorSet.start()` 在 UI thread 上调，后续的 Choreographer 回调就在 UI thread 的 Looper 上跑——动画连贯性得到保证。

### 6.8 AnimationController —— 转场状态机

文件：`com/oplus/quickstep/utils/AnimationController.java`

#### 6.8.1 设计动机

Launcher 里 Recents 转场涉及多个动画源（Launcher 自己的、SystemUI 的、Activity 自己的），需要统一状态管理：
- 当前是打开还是关闭？
- 是否有动画在跑？
- 抬手时该 cancel 当前动画还是 reverse？
- 多个动画同时跑时如何协调超时？

`AnimationController` 是个 `extends DefaultAnimationController` 的状态机：

```java
public enum AnimationState {
    NONE(false, false),
    OPEN(false, false),
    REVERSE_OPEN(true, false),
    CLOSE(true, true),
    MULTI_OPEN(false, false),
    MULTI_REVERSE_OPEN(true, false),
    MULTI_CLOSE(true, true),
    WAITING(...),
    MULTI_WAITING(...),
    UNKNOWN(...),
    SWIPE_UP_TO_CAPSULE(...),
    SWIPE_UP_TO_SPLIT_OR_FLOATING(...)
}
```

每个状态用两个 boolean 描述：
- `withTaskbarAlignment`: 是否与 taskbar 位置变化同步
- `taskbarAlignmentToLauncher`: 位置变化方向

#### 6.8.2 关键 API（按使用频率排序）

```java
public boolean isOpeningAnim() { ... }
public boolean isClosingAnimAndAnimClosed() { ... }
public boolean isAppWindowAnimRunning() { ... }   // state != NONE && state != UNKNOWN
public boolean hasRecentsAnim() { return mRecentsAnims.size() > 0; }

public void addRecentsAnim(CustomRectFSpringAnim anim, RecentsAnimationController recentsController, RemoteAnimationTargetCompat[] targets) {
    // 把新 recents 动画加入 mRecentsAnims 列表，绑定 finish 时机
}
public boolean allRecentsAnimationEnd() { return mRecentsAnims.isEmpty(); }

public void cleanUpRecentsAnim() { ... }
public void reset() { updateAnimState(AnimationState.NONE); ... }

public void registerOverviewContinuationTimeOutListener(long timeout) {
    // 用 ChoreographerUtil.postFrameCallbackDelay 实现的超时监听器
}
```

#### 6.8.3 超时控制（基于 ChoreographerUtil）

```java
public boolean delayStartActivityIfNeed(Context context, Intent intent, Supplier<Boolean> call, Runnable runnable) {
    if (!OplusAnimManager.INSTANCE.supportInterruption()) return false;
    ...
    ChoreographerUtil.postFrameCallbackDelay(() -> {
        // 如果超时还没结束，就强制开始
        runnable.run();
    }, 100);   // 100 帧 ≈ 1.6s @60Hz
    return true;
}
```

**Handler + ChoreographerUtil 的混合超时机制**：
- `RECENT_ANIM_FINISH_TIME_OUT_DURATION = 1500ms`（Handler.sendMessageDelayed 实现，看下面的 mHandler$lambda$0）
- `LAND_SPACE_RECENT_ANIM_TIME_OUT_DURATION = 2500ms`
- `APP_TO_OVERVIEW_CONTINUATION_TIME_OUT_DURATION = 100ms`（基于 ChoreographerUtil.postFrameCallbackDelay(100)）

**为什么两种超时都存在**：
- Handler 超时：用于"绝对时间"控制（如 1.5s 必须结束）
- ChoreographerUtil 帧数超时：用于"对齐下一帧"控制（避免动画抖动）

#### 6.8.4 Handler 字段

```java
private static final int MESSAGE_RELEASE_TOUCH = 101;
private static final Handler mHandler = new Handler(Looper.getMainLooper(), new Handler.Callback() {
    @Override
    public final boolean handleMessage(Message message) {
        return AnimationController.mHandler$lambda$0(this.f11185a, message);
    }
});
```

注意 `mHandler` 是 `static`——所有 `AnimationController` 实例共享同一个 Handler（在主线程），确保不会有两个同时跑的"释放触摸"超时逻辑。

#### 6.8.5 三种超时监听器（重要！）

`AnimationController` 维护**三种不同语义的超时监听器**，对应不同场景：

```java
private TaskStateHelper.TaskStateChangeTimeOutListener mOverviewContinuationTimeOutListener;
private TaskStateHelper.TaskStateChangeTimeOutListener mSpecialSceneExitTimeOutListener;
private TaskStateHelper.TaskStateChangeTimeOutListener mTransitionFinishTimeOutListener;

private long mSpecialSceneExitTimeOutMaxTime = -1;
private long mOverviewContinuationTimeOutMaxTime = -1;
```

**三种监听器的触发场景**：

| 监听器 | 触发场景 | 关键判断 |
|---|---|---|
| `mSpecialSceneExitTimeOutListener` | 横屏/分屏退出场景 | `isTimeOut = SystemClock.uptimeMillis() > mSpecialSceneExitTimeOutMaxTime`<br>+ `isLandScapeGesture` / `isSplitScreenGesture` / `IsNavModeLandScapeOnRemoteClose` 等多重判断 |
| `mTransitionFinishTimeOutListener` | 特殊应用场景 | `isSpecialAppScene(intent)` / `isSplitScreenGesture` / call.get() 等 |
| `mOverviewContinuationTimeOutListener` | App swipe-to-recent 续行动画 | `AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning()` |

**`delayStartActivityIfNeed` 的三分支决策树**（这是状态机的入口）：

```java
public boolean delayStartActivityIfNeed(Context context, Intent intent, Supplier<Boolean> call, Runnable runnable) {
    this.startActivityRunnable = null;
    ...
    if (this.mSpecialSceneExitTimeOutListener != null) {
        // 分支 1: 等待特殊场景退出
        if (isTimeOut) return false;             // 超时 → 强制继续
        if (isLandScapeGesture && !ScreenUtils.isTablet() ||
            (mIsNavModeLandScapeOnAppExit && mIsBetweenAppExitTransitionEndAndFinish) ||
            isSplitScreenGesture) {
            startActivityRunnable = runnable;     // 还在等 → 缓存 runnable
            return true;                          // 等待
        }
    } else if (this.mTransitionFinishTimeOutListener != null) {
        // 分支 2: 等待特殊应用转场完成
        if (isSpecialAppScene(intent) || isSplitScreenGesture || call.get()) {
            startActivityRunnable = runnable;
            return true;
        }
    } else if (this.mOverviewContinuationTimeOutListener != null) {
        // 分支 3: 等待 App swipe-to-recent 续行动画结束
        if (AppSwipeToRecentContinuationHelper.isAppSwipeToRecentContinuationRunning()) {
            startActivityRunnable = runnable;
            return true;
        }
    }
    // 都不满足 → 清理 listener, 允许启动 Activity
    ...
}
```

**设计精妙**：
- **`null` 监听器 = 无对应场景**：用 `if (mXxxListener != null)` 做场景判断，避免引入额外状态变量。
- **每个监听器独立超时**：三个监听器各自带 `mXxxTimeOutMaxTime`，独立超时——避免一个场景超时拖累另一个场景。
- **三层优先级**：特殊场景退出 → 特殊应用转场 → 续行动画，按"影响范围"从大到小排列优先级。

#### 6.8.6 `addRecentsAnim` 状态机转换

```java
public void addRecentsAnim(CustomRectFSpringAnim recentAnim, RecentsAnimationController recentsController, RemoteAnimationTargetCompat[] targets) {
    ...
    this.mRecentsAnims.add(recentAnim);
    recentAnim.addAnimatorListener(new AnonymousClass1(recentAnim, this, targets, recentsController));

    // 状态机转换: 根据当前 mAnimState 决定下一步
    switch (WhenMappings.$EnumSwitchMapping$0[this.mAnimState.ordinal()]) {
        case 1: case 3: case 8: case 9:    // NONE/REVERSE_OPEN/WAITING/UNKNOWN
            updateAnimState(AnimationState.CLOSE);
            break;
        case 2: case 6: case 7:             // OPEN/MULTI_REVERSE_OPEN/MULTI_WAITING
            updateAnimState(AnimationState.MULTI_CLOSE);
            break;
        case 4: case 5:                     // CLOSE/MULTI_OPEN
        default:
            updateAnimState(AnimationState.UNKNOWN);   // 错误态
            break;
    }
}
```

`WhenMappings` 是 Kotlin `when` 表达式编译后生成的 switch 表。这种 **enum → enum 的状态转换**非常适合用 `when` 表达，Java 反编译后就是这种 ordinal switch 模式。

### 6.9 AnimationSeqHelper —— Recents 动画的 SeqId 防抖

文件：`com/oplus/quickstep/utils/AnimationSeqHelper.java`

#### 6.9.1 解决的问题

Launcher 里用户可以**极快地多次点击应用图标**，每次点击都触发 `RecentsAnimationController` 跨进程请求转场动画。如果上一轮动画还没结束就触发新一轮，会出现：

- 视觉撕裂（多个动画同时跑在同一个 TaskView 上）
- 时序错乱（finish callback 在错误的顺序触发）
- 资源泄漏（多个 AnimatorSet 引用同一个 controller）

`AnimationSeqHelper` 用**全局单调递增 seqId + 500ms 防抖窗口**解决这两个问题。

#### 6.9.2 完整实现

```java
public final class AnimationSeqHelper extends DefaultAnimationSeqHelper {
    public static final long MAX_DELAY_TIME = 500;
    public static final long MAX_GO_NORMAL_DELAY_TIME = 200;
    public static final long MAX_INTERCEPT_GESTURE_DELAY_TIME = 300;
    private static final int MSG_EXC_RUNNABLE = 1;

    private long seqId = 0;                                    // ★ 全局单调递增
    private f4.l<? extends RecentsAnimationController, Long> nextFinishSeqId;  // (controller, seqId) 映射
    private Runnable delayRunnable;                            // 待延迟执行的 finish runnable
    private final Handler handler = new Handler(Looper.getMainLooper(), ...);

    // Bundle 里传递的 key
    private static final String KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID = "interrupt.transition.startActivity.seqId";

    private long updateSeqId() {
        long j = this.seqId + 1;
        this.seqId = j;
        return j;
    }

    // ★ 每次 startActivity 时分配 seqId 写到 Bundle（用于跨进程同步）
    @Override
    public void addSeqId(Bundle bundle) {
        if (!OplusAnimManager.INSTANCE.supportInterruption()) return;
        long id = updateSeqId();
        bundle.putLong(KEY_INTERRUPT_TRANSITION_START_ACTIVITY_SEQ_ID, id);
    }

    // ★ 500ms 内刚结束过 recent → 不允许 finish
    @Override
    public boolean canFinishRecent() {
        if (AppFeatureUtils.INSTANCE.isSupportStartingSurface()
            && OplusAnimManager.INSTANCE.supportInterruption()
            && AnimSeqTimeStamp.getTimeGapToLastRecentFinishTime() <= 500) {
            return false;
        }
        return true;
    }

    // ★ 300ms 内刚启动应用 → 不允许拦截手势
    @Override
    public boolean canInterceptGesture() {
        if (AppFeatureUtils.INSTANCE.isSupportStartingSurface()
            && OplusAnimManager.INSTANCE.supportInterruption()
            && AnimSeqTimeStamp.getTimeGapToLastStartAppTime() <= 300) {
            return false;
        }
        return true;
    }

    // ★ 延后 finish 500ms 防抖
    @Override
    public boolean delayFinishRecents(Runnable runnable) {
        if (canFinishRecent()) {
            runnable.run();
            return false;
        }
        clearFinishRecentsRunnable();
        this.delayRunnable = runnable;
        // 500ms 内必须 finish（距离上次 recent finish 的时间差决定了延迟多久）
        this.handler.sendEmptyMessageDelayed(1, 500 - AnimSeqTimeStamp.getTimeGapToLastRecentFinishTime());
        return true;
    }

    @Override
    public void updateNextFinishSeqIdIfNeed(RecentsAnimationController recentsAnimationController) {
        if (!OplusAnimManager.INSTANCE.supportInterruption()) return;
        f4.l<? extends RecentsAnimationController, Long> lVar = this.nextFinishSeqId;
        if (lVar == null || !recentsAnimationController.equals(lVar.f12314a)) {   // f4.l = Kotlin Pair<A, B>
            long id = updateSeqId();
            this.nextFinishSeqId = new f4.l<>(recentsAnimationController, Long id);
        }
    }
}
```

#### 6.9.3 关键设计

1. **全局单调 seqId**：`seqId++` 永远递增，用于跨进程时序对齐（Bundle 跨进程传递 seqId）。

2. **三档时间窗口**：
   - `MAX_DELAY_TIME = 500ms` — Recents finish 防抖
   - `MAX_GO_NORMAL_DELAY_TIME = 200ms` — 普通流程
   - `MAX_INTERCEPT_GESTURE_DELAY_TIME = 300ms` — 手势拦截防抖

3. **`nextFinishSeqId` 映射 `(controller, seqId)`**：通过 `f4.l<A, B>`（Kotlin Pair）记录每个 controller 对应的 finish seqId——确保同一 controller 的多次 finish 按 seqId 顺序处理。

4. **`AnimSeqTimeStamp` 跨模块时间戳**：`com.android.systemui.shared.system.AnimSeqTimeStamp` 维护"上次 recent finish 时间戳"、"上次 startApp 时间戳"。`AnimationSeqHelper` 不自己记时间，查询 systemui 的全局时间戳——这让 systemui 和 launcher 能协调多模块时序。

5. **`Handler.sendEmptyMessageDelayed` 延后执行**：`canFinishRecent()=false` 时，runnable 被存到 `delayRunnable`，500ms 后通过 Handler 在主线程执行。

### 6.10 OplusAnimManager —— feature flag 驱动的工厂单例

文件：`com/oplus/quickstep/utils/OplusAnimManager.java`

#### 6.10.1 设计动机

Launcher 里有 6 个 helper 类（`AnimationController`、`AnimationSeqHelper`、`AppOpenAnimMergeHelper`、`InterceptKeyEventHelper`、`MultiAppAnimMergeHelper`、`MultiOpenPreStartHelper`）。它们都有"完整实现"和"no-op 默认实现"两套，业务方调用入口却只希望看到一个统一名字——

`OplusAnimManager` 解决了这个需求：**根据运行时 feature flag，决定 6 个 helper 是返回完整实现还是 no-op 实现**。业务代码统一用 `OplusAnimManager.INSTANCE.getAnimController()`，不需要 if/else。

#### 6.10.2 完整实现

```java
public final class OplusAnimManager {
    public static final OplusAnimManager INSTANCE;       // Kotlin object 单例
    private static final boolean SUPPORT_INTERRUPT = true;  // ← feature flag

    // 6 个 lazy delegate
    private static final t4.b mAnimationController;       // DefaultAnimationController
    private static final t4.b mAnimationSeqHelper;       // DefaultAnimationSeqHelper
    private static final t4.b mAppOpenAnimMergeHelper;   // DefaultAppOpenAnimMergeHelper
    private static final t4.b mInterceptKeyEventHelper;   // DefalutInterceptKeyEventHelper
    private static final t4.b mMultiAppAnimMergeHelper;  // DefaultMultiAppAnimMergeHelper
    private static final t4.b mMultiOpenPreStartHelper;  // DefaultMultiOpenPreStartHelper

    static {
        INSTANCE = new OplusAnimManager();
        // static init 时按 feature flag 创建实现
        mAnimationController = observable(... new AnimationController()  (SUPPORT_INTERRUPT=true)
                                       : new DefaultAnimationController() ...);
        mAnimationSeqHelper = observable(... new AnimationSeqHelper()    : new DefaultAnimationSeqHelper() ...);
        ...
    }

    // 6 个工厂方法
    private final DefaultAnimationController createAnimationController() {
        return supportInterruption() ? new AnimationController() : new DefaultAnimationController();
    }
    private final DefaultAnimationSeqHelper createAnimationSeqHelper() {
        return supportInterruption() ? new AnimationSeqHelper() : new DefaultAnimationSeqHelper();
    }
    private final DefaultAppOpenAnimMergeHelper createAppOpenAnimMergeHelper() {
        return supportInterruption() ? new AppOpenAnimMergeHelper() : new DefaultAppOpenAnimMergeHelper();
    }
    private final DefalutInterceptKeyEventHelper createInterceptKeyEventHelper() {
        return supportInterruption() ? new InterceptKeyEventHelper() : new DefalutInterceptKeyEventHelper();
    }
    private final DefaultMultiAppAnimMergeHelper createMultiAppAnimMergeHelper() {
        return supportInterruption() ? new MultiAppAnimMergeHelper() : new DefaultMultiAppAnimMergeHelper();
    }
    private final DefaultMultiOpenPreStartHelper createMultiOpenPreStartHelper() {
        return (supportInterruption() && AppFeatureUtils.INSTANCE.isSupportPreStart())
            ? new MultiOpenPreStartHelper() : new DefaultMultiOpenPreStartHelper();
    }

    public final boolean supportInterruption() {
        return SUPPORT_INTERRUPT;
    }

    // 6 个 getter
    public static final DefaultAnimationController getAnimController() {
        return INSTANCE.getMAnimationController();
    }
    public final DefaultAnimationSeqHelper getAnimationSeqHelper() { ... }
    public final DefaultAppOpenAnimMergeHelper getAppOpenAnimMergeHelper() { ... }
    ...
}
```

#### 6.10.3 关键设计

1. **Kotlin object 单例**：用 `INSTANCE` 静态字段实现单例；`mAnimationController` 等用 `t4.b` (lazy delegate) 实现线程安全延迟初始化。

2. **`Default*` 抽象基类**：所有 6 个 helper 都有 `Default*` 基类版本，里面所有方法都是 no-op 或返回 false/null（见 `DefaultAnimationController` —— `addRecentsAnim` 什么都不做，`canFinishRecentsAnim` 返回 true 等）。**这样关闭功能时整个调用链自动降级为 no-op**，0 行为变更。

3. **feature flag 二选一**：`SUPPORT_INTERRUPT = true` 时 6 个 helper 都返回 `*Impl` 完整实现；否则返回 `Default*` no-op 实现。

4. **`observable(...)` 包装**：`t4.a` 是 Kotlin 的 `Delegates.observable`，字段值改变时触发回调——这里 `afterChange` 是空实现，因为 Oplus 不需要监听"helper 实现换了"的场景。

### 6.11 AnimationFeatureHelper —— 远程灰度配置

文件：`com/oplus/quickstep/utils/AnimationFeatureHelper.java`

#### 6.11.1 设计动机

不同地区、不同机型、不同用户分群可能需要不同的动画行为：
- 低端机：关闭 1px 微动效（耗电）
- 某些游戏场景：关闭 RT 解锁（避免卡顿）
- 灰度发布：新功能先开 10% 用户

`AnimationFeatureHelper` 通过 OPPO 内部的 **`RusConfig`（Remote User-config System）远程灰度平台**，动态下发这些配置。

#### 6.11.2 完整实现

```java
public final class AnimationFeatureHelper {
    private static final String CONFIG_LIST_NAME_LAUNCHER_ANIM_FEATURE_CONFIG = "launcher_anim_prj_feature_config";

    // 6 个功能开关
    private static final String CONFIG_NAME_LAUNCHER_ANIM_FEATURE_ASYNC_ENABLE       = "launcher_anim_prj_feature_async_enable";
    private static final String CONFIG_NAME_LAUNCHER_ANIM_FEATURE_ICON_BLUR_ENABLE    = "launcher_anim_prj_feature_icon_blur_enable";
    private static final String CONFIG_NAME_LAUNCHER_ANIM_FEATURE_MULTI_APP_BLOCK_ENABLE = "launcher_anim_prj_feature_multi_app_block_enable";
    private static final String CONFIG_NAME_LAUNCHER_ANIM_FEATURE_RT_UNLOCK_ENABLE   = "launcher_anim_prj_feature_rt_unlock_enable";
    private static final String CONFIG_NAME_LAUNCHER_ANIM_FEATURE_1PX_ENABLE         = "launcher_anim_prj_feature_1px_enable";
    private static final String CONFIG_ANIM_INTERRUPT_THRESHOLD                       = "launcher_anim_interrupt_threshold";

    // volatile 字段: 跨线程立即可见
    private volatile int mAsyncEnable = -1;
    private volatile int mRTUnlockEnable = -1;
    private volatile int mMultiAppBlockEnable = -1;
    private volatile int mIconBlurEnable = -1;
    private volatile int m1pxEnable = -1;
    private volatile float mInterruptThreshold = 1.0f;
    private volatile int mLimtSize = -1;
    private volatile List<String> m1pxPkgDisableList = new ArrayList();
    private volatile List<Integer> m1pxCardDisableList = new ArrayList();

    private RusBaseConfigManager.RusConfigChangedListener mRusConfigChangedListener;

    public AnimationFeatureHelper() {
        updateRusConfig();   // 启动时拉一次
        RusBaseConfigManager.RusConfigChangedListener listener = new RusBaseConfigManager.RusConfigChangedListener() {
            @Override
            public void onRusConfigChanged() {
                AnimationFeatureHelper.this.updateRusConfig();   // ★ 配置变更时自动重读
            }
        };
        this.mRusConfigChangedListener = listener;
        LauncherCommonConfigManager.INSTANCE.getInstance().registerRusConfigChangedListener(listener);
    }

    private void updateRusConfig() {
        Iterator<RusBaseXmlType1ConfigManager.ConfigList> it =
            LauncherCommonConfigManager.INSTANCE.getInstance().getChangedRusConfig().getConfigLists().iterator();
        while (it.hasNext()) {
            RusBaseXmlType1ConfigManager.ConfigList next = it.next();
            if (Intrinsics.areEqual(next.getName(), CONFIG_LIST_NAME_LAUNCHER_ANIM_FEATURE_CONFIG)) {
                for (RusBaseXmlType1ConfigManager.Config config : next.getList()) {
                    switch (config.getName()) {
                        case CONFIG_NAME_LAUNCHER_ANIM_FEATURE_ASYNC_ENABLE:
                            setAsyncEnable(Integer.parseInt(config.getValue()));
                            break;
                        case CONFIG_NAME_LAUNCHER_ANIM_FEATURE_ICON_BLUR_ENABLE:
                            setIconBlurEnable(Integer.parseInt(config.getValue()));
                            break;
                        // ... 其他 4 个配置 ...
                    }
                }
            }
        }
    }

    private final synchronized void setAsyncEnable(int i9) { this.mAsyncEnable = i9; }
    private final synchronized void setIconBlurEnable(int i9) { this.mIconBlurEnable = i9; }
    private final synchronized void setInterruptThreshold(float f9) {
        this.mInterruptThreshold = f9;
        if (LauncherAnimConfig.isAdaptiveAnimation()) {
            this.mInterruptThreshold = 1.0f;  // 自适应动画模式强制阈值
        }
    }
    ...
}
```

#### 6.11.3 关键设计

1. **volatile 字段**：所有开关都是 `volatile`，保证 UI 线程写、动画线程读时立即可见——避免使用锁的开销。

2. **RusConfig 远程下发**：通过 `LauncherCommonConfigManager` 注册 `RusConfigChangedListener`，配置变更时自动 `updateRusConfig()`。

3. **自适应模式保护**：`mInterruptThreshold` 在 `LauncherAnimConfig.isAdaptiveAnimation()` 开启时强制设为 1.0——这是兜底逻辑，防止某些意外配置把动画中断阈值调到 0（=完全不允许中断）。

4. **同步 setter**：`setAsyncEnable` 等用 `synchronized` 修饰，因为 `updateRusConfig` 在 `onRusConfigChanged` 回调里跑——可能与业务线程并发读写。

#### 6.11.4 与 §6.10 OplusAnimManager 的关系

`OplusAnimManager` 的 `SUPPORT_INTERRUPT = true` 是**编译期常量**——即"是否支持中断"这个 feature 是代码层决定的（默认支持）。

`AnimationFeatureHelper` 的各种开关是**运行时远程配置**——粒度更细，可以按用户/地区/灰度动态调整。

两者职责正交：
- `OplusAnimManager`: 选"用哪套实现"（Default no-op vs Impl）
- `AnimationFeatureHelper`: 调"实现的参数"（threshold、enable flags）

---

## 7. 端到端时序分析：一次完整的 Recents 转场

把上面所有部件串起来，看一次完整的"应用进入 Recents"：

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│ 系统进程 system_server                                                          │
│                                                                                 │
│  onAnimationStart(transit=OPEN, targets, finishCb)                             │
│      │ (Binder call)                                                            │
└──────┼──────────────────────────────────────────────────────────────────────────┘
       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ LauncherAnimationRunner.onAnimationStart (BinderThread)                         │
│      │ post 到 mHandler (UI thread)                                            │
└──────┼──────────────────────────────────────────────────────────────────────────┘
       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ UI thread                                                                     │
│                                                                                 │
│  lambda$onAnimationStart$4                                                    │
│      ├─ finishExistingAnimation()                                              │
│      ├─ 构造 AnimationResult (含 Sync/Async finishRunnable)                    │
│      ├─ 调 RemoteAnimationFactory.onCreateAnimation(...)                        │
│      │     └─ RecentsAnimationFactory (or similar): 创建 AnimatorSet           │
│      │        AnimatorSet 包含：                                               │
│      │           - CustomRectFSpringAnim (extends AsyncValueAnimator)          │
│      │           - 多个 ObjectAnimator (Alpha, Scale, Translation)             │
│      │                                                                              │
│      └─ AnimationResult.setAnimation(animatorSet, ctx, ...)                     │
│             ├─ 包装成 AnimatorSet.start()                                       │
│             │     └─ 启动每个子 Animator                                        │
│             │           ├─ AsyncValueAnimator.start()                          │
│             │           │     └─ marshal 到 mAnimLooperExecutor               │
│             │           │           └─ super.start() → addAnimationCallback() │
│             │           │                 └─ AnimationHandler.addAnimationFrameCallback(this) │
│             │           │                       └─ (首次) Choreographer.postFrameCallback │
│             │           │                                                            │
│             │           └─ ObjectAnimator.start()                                 │
│             │                 └─ ValueAnimator.start() → addAnimationCallback() │
│             │                                                                       │
│             ├─ AnimatorSet 注册 listener 到 onAnimationEnd:                         │
│             │     └─ AnimationResult.mSyncFinishRunnable.run()                    │
│             │           └─ 触发 system_server 回调, 转场结束                       │
│             │                                                                       │
│             └─ 准备异常处理：setBreakParam / setAsyncFinishExecutor               │
└─────────────────────────────────────────────────────────────────────────────────┘
       ▼ (Choreographer 注册完毕)
┌─────────────────────────────────────────────────────────────────────────────────┐
│ 每个 VSYNC @16ms                                                               │
│                                                                                 │
│  Choreographer.doFrame(t_nanos)                                                │
│      └─ FrameCallbackProvider16.doFrame()                                       │
│           └─ AnimationHandler.onAnimationFrame(t_ms)                            │
│                ├─ doAnimationFrame(t_ms)                                         │
│                │     ├─ for each callback in mAnimationCallbacks:               │
│                │     │     callback.doAnimationFrame(t_ms)                      │
│                │     │       ├─ ValueAnimator.doAnimationFrame                   │
│                │     │       │     ├─ animateBasedOnTime(t_ms - startTime)     │
│                │     │       │     ├─ animateValue(fraction)                   │
│                │     │       │     │     ├─ Interpolator.getInterpolation()    │
│                │     │       │     │     ├─ PVH.calculateValue() → View.setX/Y  │
│                │     │       │     │     └─ onAnimationUpdate(listeners)       │
│                │     │       │     │           └─ AnimatorPlaybackController.onAnimationUpdate │
│                │     │       │     │                 └─ for each Holder.setProgress │
│                │     │       │     │                       └─ holder.anim.setCurrentFraction │
│                │     │       │     │                             └─ 子 ValueAnimator.animateValue │
│                │     │       │     │                                  └─ (recursively) View 变化 │
│                │     │       │     └─ if (结束) removeCallback()               │
│                │     │                                                           │
│                │     └─ 单独跑 SpringAnimation 的:                              │
│                │           └─ dynamicanimation.AnimationHandler               │
│                │                 └─ SpringAnimation.updateValueAndVelocity     │
│                │                       └─ Property.setValue(target, position) │
│                │                                                                   │
│                └─ if (mAnimationCallbacks.size() > 0)                            │
│                      postFrameCallback()                                          │
│                                                                                    │
│  View 树最终会触发 RenderThread 重绘 → SurfaceFlinger 合成 → 上屏              │
└─────────────────────────────────────────────────────────────────────────────────┘
       ▼ (动画时间到)
┌─────────────────────────────────────────────────────────────────────────────────┐
│  animatorSet → onAnimationEnd                                                  │
│      └─ AnimationResult.mSyncFinishRunnable.run()                               │
│            └─ Binder 回调 system_server, 转场结束                              │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 7.1 一次 Launcher 转场：AllApps ↔ Workspace

> 来源：基于 Agent 3 独立分析的贡献。把整个 6 层（Choreographer → APC → PendingAnimation → StateHandler → AnimatorSet）的"业务级转场"完整链路展开。

#### 7.1.1 触发点

`StateManager.goToState(STATE_TYPE, boolean)` 在 `com/android/launcher3/statemanager/StateManager.java` 内被外部调起（典型调用：AllAppsTransitionController / WorkspaceStateHandler 等下游 StateHandler 链路）。

```
Launcher.onHomeKey() / gesture
    ↓
StateManager.goToState(LauncherState.NORMAL, /* animated= */ true)
    ↓ (StateManager.java:706)
goToState(state, z8, j8, animatorListener)  [内部重载]
    ↓ cancelAnimation(); if (zAreAnimatorsEnabled) ... else ...
goToStateAnimated(state_type, state_type2, animatorListener)
```

#### 7.1.2 Build 阶段

`StateManager.java:349-363`：

```java
public void goToStateAnimated(STATE_TYPE state_type, STATE_TYPE state_type2, ...) {
    this.mConfig.duration = state_type.getTransitionDuration(...);
    prepareForAtomicAnimation(...);
    AnimatorSet animatorSet = createAnimationToNewWorkspaceInternal(state_type).buildAnim();
    ...
    new StartAnimRunnable(animatorSet).run();   // ← 启动
}

private PendingAnimation createAnimationToNewWorkspaceInternal(STATE_TYPE state_type) {
    PendingAnimation pa = new PendingAnimation(this.mConfig.duration);
    getStateManagerInjector().setBackToAllAppsFlagIfNeed(state_type);
    for (StateHandler stateHandler : getStateHandlers()) {
        stateHandler.setStateWithAnimation(state_type, this.mConfig, pa);  // ← 业务往里 addXxx
    }
    pa.addListener(createStateAnimationListener(state_type));    // 挂 state-level listener
    this.mConfig.setAnimation(pa.buildAnim(), state_type);
    return pa;
}
```

#### 7.1.3 业务 StateHandler 往 PendingAnimation 注入子动画

以 `AllAppsTransitionController.setStateWithAnimation`（`com/android/launcher3/allapps/AllAppsTransitionController.java:265-312`）为例：

```java
public void setStateWithAnimation(LauncherState launcherState, StateAnimationConfig cfg, PendingAnimation pa) {
    if (LauncherState.NORMAL.equals(launcherState) && this.mLauncher.isInState(LauncherState.ALL_APPS)) {
        if (pa != null) {
            pa.addEndListener(newd(this, 0));        // ← end 回调
        }
    }
    float verticalProgress = launcherState.getVerticalProgress(this.mLauncher);
    ...
    Animator animator = createSpringAnimation(this.mProgress, verticalProgress);
    animator.addListener(getProgressAnimatorListener());
    if (pa != null) {
        pa.add(animator);                              // ← 主要弹簧动画入队
    }
    setAlphas(launcherState, cfg, pa);                 // ← 透明度等附加属性
}
```

`setAlphas` 内部调用 `pa.setViewAlpha(...)`（`PendingAnimation.java:187`），这些 `addXxx` 都累积在 `mAnimHolders` 和 `mAnim.play()` 队列里。

#### 7.1.4 Build → Wrap

`PendingAnimation.buildAnim()`（`PendingAnimation.java:137-145`）：
```java
public AnimatorSet buildAnim() {
    ValueAnimator valueAnimator = this.mProgressAnimator;
    if (valueAnimator != null) {
        add(valueAnimator);                             // 把 mProgressAnimator 当子动画加进 mAnim
        this.mProgressAnimator = null;
    }
    if (this.mAnimHolders.isEmpty()) {
        add(ValueAnimator.ofFloat(0.0f, 1.0f).setDuration(this.mDuration));  // 占位
    }
    return this.mAnim;
}
```

对于 **user-controlled** 转场（手势驱动），路径走 `createAnimationToNewWorkspace(state_type, stateAnimationConfig)`（`StateManager.java:794-823`）：

```java
public AnimatorPlaybackController createAnimationToNewWorkspace(STATE_TYPE state_type, StateAnimationConfig cfg) {
    cfg.userControlled = true;
    cancelAnimation();
    cfg.copyTo(this.mConfig);
    this.mConfig.playbackController = createAnimationToNewWorkspaceInternal(state_type).createPlaybackController();
    return this.mConfig.playbackController;
}
```

`PendingAnimation.createPlaybackController()`（`PendingAnimation.java:147-151`）：
```java
public AnimatorPlaybackController createPlaybackController() {
    if (this.mAnimatorPlaybackController == null) {
        this.mAnimatorPlaybackController = new AnimatorPlaybackController(
            buildAnim(), this.mDuration, this.mAnimHolders);
    }
    return this.mAnimatorPlaybackController;
}
```

实际走的是 `new AnimatorPlaybackController(...)` 直接构造（而不是 `wrap()`），效果一样：拿到 `AnimatorSet + duration + Holder[]`，构造 `mAnimationPlayer`（LINEAR 0..1），挂 `OnAnimationEndDispatcher` 和 update listener。

#### 7.1.5 播放（手势驱动 / 自动）

返回的 `playbackController` 被存到 `mConfig.playbackController`，由手势 controller 调用：

| 调用 | 行为 |
|---|---|
| `setPlayFraction(f)` | 立即把 f 广播给所有 Holder（**核心：手势拖动时**） |
| `start()` | 启动 mAnimationPlayer 从 mCurrentFraction 跑到 1 |
| `reverse()` | 启动 mAnimationPlayer 从 mCurrentFraction 跑到 0 |
| `pause()` | 复位所有 Holder + mAnimationPlayer.cancel() |

例如 AllApps → Workspace 完全展开：`playbackController.start()`。

#### 7.1.6 帧循环（Choreographer → APC → 子动画）

```
Choreographer.doFrame()
    ↓
ValueAnimator.animateValue()  ← mAnimationPlayer（LINEAR 0..1）
    ↓
mAnimationPlayer.notifyAnimatorUpdateListeners()
    ↓
AnimatorPlaybackController.onAnimationUpdate(this)
    ↓
setPlayFraction(animatedValue)
    ↓
for each Holder: holder.setProgress(f)  ← 全部同步推进
    ↓
holder.anim.setCurrentFraction(mapper.getProgress(f, globalEndProgress))
    ↓
每个子动画的实际属性变更（ObjectAnimator → View.setAlpha / setTranslationX 等）
```

#### 7.1.7 End 阶段

mAnimationPlayer 跑到 `f=1` 后 fire `onAnimationEnd`：

1. `OnAnimationEndDispatcher.onAnimationSuccess(anim)` → `APC.dispatchOnEnd()`（递归触发每个子 Animator 的 listener.onAnimationEnd）→ 跑 `mEndActionMap` 中的 Runnables → `mDispatched = true`
2. 同时 `mProgressAnimator`（被包成 mAnim 的子动画）也 end，触发业务 `addEndListener`（`Consumer<Boolean>`）
3. `PendingAnimation.mAnim` 的 listener 链 fire（设置 `isAnimFinished = true`）
4. `AnimationState.onAnimationEnd(animator)` 被触发（`StateManager.java:581-590`），清空 `currentAnimation`、`playbackController`
5. `createStateAnimationListener.onAnimationSuccess`（`StateManager.java:179-188`）调 `StateManager.onStateTransitionEnd(state_type)`，通知 StateListener 们

#### 7.1.8 完整调用链时序图

```
┌──────────────────────────────────────────────────────────────────────────┐
│  触发 (用户手势 / Home 键)                                                 │
│    StateManager.goToState(NORMAL, true)                                  │
│      → createAnimationToNewWorkspace()                                  │
│        → new PendingAnimation(duration)                                  │
│        → for StateHandler:                                              │
│             AllAppsTransitionController.setStateWithAnimation()          │
│               pa.add(animatorCreateSpringAnimation(...))                 │
│               pa.setViewAlpha(...)                                       │
│             WorkspaceStateHandler.setStateWithAnimation()                │
│               pa.addFloat(workspace, Y, ..., DEACCEL)                    │
│        → PendingAnimation.buildAnim()                                   │
│            → add(mProgressAnimator) [若有 end/onFrame listener]          │
│        → PendingAnimation.createPlaybackController()                    │
│            → new AnimatorPlaybackController(mAnim, mDur, mAnimHolders)  │
│                → mAnimationPlayer = ValueAnimator.ofFloat(0,1,LINEAR)   │
│                → addListener(OnAnimationEndDispatcher)                  │
│                → addUpdateListener(this)                                │
│        → 存到 mConfig.playbackController                                 │
│      返回 APC 给手势 controller                                           │
│                                                                          │
│  播放 (user-controlled / 自动)                                            │
│    playbackController.setPlayFraction(f)  // 手势拖动                     │
│      → setPlayFraction(f)                                                │
│        → for each Holder: holder.setProgress(f)                         │
│          → anim.setCurrentFraction(mapper.getProgress(f, gEP))          │
│            → 子动画属性变更                                               │
│                                                                          │
│  收尾 (放手后完全展开)                                                     │
│    playbackController.start()                                            │
│      → mAnimationPlayer.start()                                         │
│        → Choreographer 帧驱动 → onAnimationUpdate → setPlayFraction → ... │
│        → mAnimationPlayer.end 触发                                        │
│          → OnAnimationEndDispatcher.onAnimationSuccess                   │
│            → dispatchOnEnd() [递归到 mAnim 每个 listener.onAnimationEnd] │
│            → mEndActionMap.forEach(run)                                  │
│            → mDispatched = true                                          │
│          → AnimationState.onAnimationEnd                                │
│            → playbackController = null                                   │
│          → StateManager.onStateTransitionEnd → StateListeners           │
└──────────────────────────────────────────────────────────────────────────┘
```

### 7.2 两种转场的对比

| 维度 | Recents 转场（§7 顶部） | AllApps ↔ Workspace 转场（§7.1） |
|---|---|---|
| **触发源** | system_server Binder 回调 | 用户手势 / Home 键 / Launcher 内部 |
| **入口** | `LauncherAnimationRunner.onAnimationStart` | `StateManager.goToState(state, animated)` |
| **同步机制** | `AnimationResult.mSyncFinishRunnable` 回传 Binder 回调 | `StateManager.onStateTransitionEnd` 通知 StateListeners |
| **主驱动器** | 业务 AnimatorSet（含 SpringAnimation 等） | `mAnimationPlayer`（LINEAR 0..1），统一推动所有 Holder |
| **APC 角色** | 不参与（业务直接 start AnimatorSet） | **核心**——所有子动画通过 Holder 同步推进 |
| **状态机** | 由 `AnimationController.mAnimState` 管理 | 由 `LauncherState` enum 管理（StateManager 内） |
| **超时机制** | 三种独立 listener + Handler.sendEmptyMessageDelayed | n/a（直接走 mAnimationPlayer.end） |
| **线程切换** | BinderThread → UIThread（`Utilities.postAsyncCallback`） | 全程 UIThread |

---

## 8. 关键设计原则（OPPO 这次实现的精髓）

### 8.1 单一调度入口

无论上层怎么套，最终所有动画都通过 `Choreographer.getInstance().postFrameCallback(provider)` 这一行进入系统 VSYNC 调度。在项目里能看到所有 Choreographer 注册代码：

| 位置 | 目的 |
|---|---|
| `AnimationHandler.FrameCallbackProvider16.postFrameCallback()` | **核心**：所有 `ValueAnimator` 共享这一个回调 |
| `dynamicanimation.AnimationHandler.FrameCallbackProvider16.postFrameCallback()` | 所有 `SpringAnimation` 共享 |
| `ChoreographerUtil.postFrameCallbackDelay` | "等 N 帧"工具 |
| `ChoreographerCompat` (在 `com/oplus/physicsengine`) | 物理引擎的 Choreographer 适配 |

**好处**：避免回调爆炸，系统 VSYNC 调度一次就能驱动所有动画。

### 8.2 线程局部化

- `AnimationHandler` 是 ThreadLocal
- `Choreographer` 也是 ThreadLocal
- 因此整个动画调度链路天然无锁、无竞争

**代价**：动画必须跑在有 Looper 的线程，且 start 和后续帧必须在同一线程。这正是 `AsyncValueAnimator` 存在的根本原因。

### 8.3 延迟删除

`AnimationHandler.removeCallback` 永远只把 list 元素置 null + 打 `mListDirty` 标志，不真正移除。下次帧开头才 `cleanUpList()`。

**好处**：避免在 `doAnimationFrame` 迭代 `mAnimationCallbacks` 时回调里又 `removeCallback` 触发 `ConcurrentModificationException`。

### 8.4 进度驱动模式（`AnimatorPlaybackController`）

整个 Launcher 转场动画的核心抽象：
- 用一个 [0,1] 主 ValueAnimator 当"时间轴"
- 所有子动画映射成 `[0, globalEndProgress]` 的子进度
- 帧回调把主进度同步推给所有子动画

**好处**：
- 天然支持 reverse（setFloatValues(current, 0) + start）
- 天然支持 pause/resume（暂停主 ValueAnimator 即可）
- 天然支持多 Listener（只要挂在主 ValueAnimator 上）
- 子动画的 Interpolator 独立保留（动画手感不被强制线性化）

### 8.5 Spring 混合驱动

`OplusSpringObjectAnimator` 用 `SpringProperty` 装饰器做"渐进切换"：
- 默认走 `ObjectAnimator`（时序精确）
- `switchToSpring()` 后改走 `SpringAnimation`（物理精确）
- 切换发生在 `ObjectAnimator.animateValue` 内部，无需停掉动画

**两套独立的 AnimationHandler 让这件事可行**——它们各自管自己的 Choreographer 调度，互不冲突。

### 8.6 跨线程 listener 安全（`AsyncAnimCallbacks`）

业务 listener 可能在任意线程 register，但动画 listener 在动画 Looper 上触发。`AsyncAnimCallbacks` 把 listener 调用统一 marshal 到主线程执行，确保 listener 不需要自己处理线程切换。

**配合 `Trace.traceBegin/End` 做动效溯源**，每条动画事件都有 `#mAnimationId-mAnimType` 前缀，方便 Perfetto 分析。

### 8.7 状态机 + 超时混合（`AnimationController`）

业务级状态机 (`AnimationState`) + 两种超时机制：
- Handler.sendMessageDelayed：绝对时间超时（如 1.5s）
- ChoreographerUtil.postFrameCallbackDelay(N)：帧数超时（精确对齐 VSYNC）

**为什么两种**：绝对时间用于"必须结束"的兜底；帧数超时用于"对齐下一帧"的精确控制，避免动画在帧中间抖动。

---

## 9. 几个值得借鉴的模式（如果你要自己写动画框架）

1. **单一 Choreographer 入口 + ThreadLocal AnimationHandler**：避免回调爆炸，免锁。

2. **Holder 进度映射模式**：用主 ValueAnimator + Holder 数组，把"一套动画同步推进"做到极致。

3. **延迟删除**：`mListDirty` + `cleanUpList`（core.animation）vs `removeNullEntries`（dynamicanimation）—— 根据 listener 规模和迭代模式选择"异步清理"或"立即清理"。

4. **统一动画生命周期派发**（`AsyncAnimCallbacks`）：把 listener marshal 到指定线程，免去业务侧的线程切换代码；配合 `Trace.traceBegin/End` 做动效溯源。

5. **SpringProperty 装饰器**（`OplusSpringObjectAnimator`）：把"切换驱动源"做在 `FloatProperty` 层，业务代码完全无感。

6. **timeController 委托模式**（`OplusValueAnimator`）：wrapper 把所有生命周期操作委托给内部 `ObjectAnimator`，实现"续行动画"——配合 Kotlin `data class copy()` 复用配置。

7. **`postFrameCallbackDelay` 工具**：比 Handler.postDelayed 更准的延迟，对齐 VSYNC。

8. **`AnimationResult.finish` 双路径**：同步 + 异步 finish，支持转场提前结束。

9. **Feature flag 工厂模式**（`OplusAnimManager`）：Kotlin object 单例 + lazy delegate + Default/Impl 二选一，关闭功能时整个调用链自动降级为 no-op。

10. **远程灰度配置**（`AnimationFeatureHelper`）：通过 `RusConfig` 远程下发 + volatile 字段 + `synchronized` setter，动态调整动画行为无需发版。

11. **全局单调 SeqId 防抖**（`AnimationSeqHelper`）：Bundle.putLong 跨进程同步 + Handler 500ms 防抖窗口 + 三档时间阈值。

12. **dt vs 绝对时间的取舍**：`ValueAnimator` 用绝对时间保证进度准确，`DynamicAnimation` 用帧间 dt 让物理积分更稳定——根据动画类型选择。

13. **SpringAnimation 2 阶段过渡**：动画中改 finalPosition 时，把本帧 dt 拆成两半，先用旧 finalPosition 走 dt/2，再切到新 finalPosition 走 dt/2——避免位置跳变。

14. **`null` listener = 无场景**（`AnimationController`）：用 `if (mXxxListener != null)` 判断当前是否处于某个特殊场景，省去显式状态变量。

15. **三种超时监听器独立超时**：`mSpecialSceneExitTimeOutListener` / `mTransitionFinishTimeOutListener` / `mOverviewContinuationTimeOutListener` 各自带独立超时字段，互不干扰。

---

## 10. 修订记录

本文档基于代码片段 + Android 公开 API 知识整理。后续在第二轮精确分析时（通过 Python 读取完整源码），发现并修订了以下内容：

| 第一轮基于片段的推测 | 第二轮基于完整源码的修正 |
|---|---|
| `OplusValueAnimator` 是"统一插桩" ValueAnimator | 实为 **timeController 委托模式 wrapper**，核心是 `generateContinuationAnim` 续行动画 API |
| `OplusSpringObjectAnimator` 和 `OplusValueAnimator` 都是"双驱动" | 两者是**完全不同的双驱动模式**：`SpringProperty` 装饰器 vs `timeController` 委托 |
| `DefaultAnimationController` 是抽象类 | 实为**基类**，所有方法默认 no-op，由 `OplusAnimManager` feature flag 决定返回基类还是 Impl |
| `AnimationController` 顶层是简单的"1 种超时" | 实际有**3 种超时监听器**（mSpecialSceneExitTimeOut / mTransitionFinishTimeOut / mOverviewContinuationTimeOut），对应 3 种不同场景 |
| `LooperExecutor` 是普通 Executor | 实为 `AbstractExecutorService` 子类，封装 Handler + Looper，`execute()` 自动判断线程 |
| `SpringAnimation.updateValueAndVelocity` 简单物理积分 | 实为**2 阶段过渡软切**，dt 拆半处理"动画中改 finalPosition" |
| `dynamicanimation.AnimationHandler` 与 core 的差异只有类名 | 还有 **5 大差异**（时间基准/线程约束/listener 清理/addAnimationFrameCallback 签名/OnAnimationEnd 签名） |
| 缺少 `AnimationSeqHelper` 章节 | 补充完整章节：SeqId 防抖 + 三档时间窗口 + `nextFinishSeqId` controller 映射 |
| 缺少 `OplusAnimManager` 章节 | 补充完整章节：feature flag 工厂 + Default no-op vs Impl 二选一 |
| 缺少 `AnimationFeatureHelper` 章节 | 补充完整章节：RusConfig 远程灰度 + volatile 字段 + 自适应模式保护 |

**仍然未深入的盲区**（已能读但未在本轮分析）：
- `RecordInputInterpolator`（在 OplusValueAnimator 中提到，记录用户输入进度）—— **第三轮已部分展开**，作为 §6.5.5 链路图的一部分
- `AppSwipeToRecentContinuationHelper`（在 §6.8.5 提到的续行动画判定逻辑）
- `TaskStateHelper.TaskStateChangeTimeOutListener`（§6.8.5 的三种超时监听器接口）
- `RemoteAnimInterrupter`（在 `cleanUpRecentsAnim` 中调用，用于记录/恢复 Recents 进度）
- `DefaultAnimationSeqHelper`（`AnimationSeqHelper` 的基类，可能定义了额外接口）
- `AppOpenAnimMergeHelper` / `MultiAppAnimMergeHelper` / `MultiOpenPreStartHelper` / `InterceptKeyEventHelper`（OplusAnimManager 管理的另外 4 个 helper，各自独立但本文档未展开）

### 第三轮（5 agent 并行独立分析后）

第三轮启动 5 个独立 agent 从 5 个不同维度并行分析，相互印证、相互补充。基于 5 agent 的发现，文档修订如下：

| 第三轮发现 | 修订章节 |
|---|---|
| Agent 2：两套 AnimationHandler 的 11 项字段级对比 | §3 → 新增 §3.1 完整字段级对比表 |
| Agent 2：3 项接口差异的工程原因（doAnimationFrame 返回 boolean、AnimationCallbackDispatcher 中间层、delayedStartTime 参数） | §3 → 新增 §3.2 三个工程原因详解 |
| Agent 2：ValueAnimator vs DynamicAnimation 的 10 项实现层对比 | §3 → 新增 §3.3 实现层对比表 |
| Agent 4：明确两个 Oplus 类的核心洞察（同一对象 swap vs 两对象接力） | §6.5.5 → 扩展为 11 项对比表 + 完整链路差异图 |
| Agent 4：三种"双驱动"模式总览（Spring 切换 / timeController 委托 / Spring 直接驱动 / 原生 ValueAnimator 兜底 / AOSP 续行） | §6.5 → 新增 §6.5.6 三种模式总览表 |
| Agent 3：完整 AllApps → Workspace 转场调用链（StateManager → PendingAnimation → APC → Choreographer） | §7 → 新增 §7.1 完整转场链路（含 build/wrap/play/end 4 个阶段 + 8 步段时序图）|
| Agent 3：Recents 转场 vs AllApps 转场的对比 | §7 → 新增 §7.2 两种转场对比表 |

**第三轮后的独立 agent 互相印证总结**：
- 4 层独立调度框架（Agent 1+2+4+5 印证）
- ThreadLocal+懒删除是核心不变式（Agent 1+2）
- 进度驱动模式是 Launcher 关键创新（Agent 3）
- 双驱动 ValueAnimator 两种本质不同设计（Agent 4 单独分析）
- Spring 2 阶段过渡（Agent 2+4）
- 11 状态 + 3 超时是业务级协调关键（Agent 5）
- SeqId 防抖（Agent 5）
- Feature flag 工厂 + 远程灰度（Agent 5）

如果需要进一步深入上述盲区，可以继续扩展。

---

## 11. 一句话总结

> **OPPO Launcher 的动画体系 = AndroidX 标准 ValueAnimator（Choreographer 驱动）+ AnimatorPlaybackController（统一进度同步）+ AsyncValueAnimator（跨线程）+ OplusSpringObjectAnimator/OplusValueAnimator（双驱动 wrapper）+ Spring 混合 + 状态机 + 三种超时监听器 + SeqId 防抖 + Feature flag 工厂 + 远程灰度配置。** 设计核心：**单一调度入口 + 线程局部化 + 进度驱动模式 + 业务/feature 切换的默认降级**，让几十种转场、上百个并行动画能在 16ms 一帧里稳定跑完而不抖动、不掉帧、不竞争。

---

## 附录：本文用到的关键文件

| 文件 | 角色 |
|---|---|
| `androidx/core/animation/AnimationHandler.java` | core 动画调度中枢 |
| `androidx/core/animation/ValueAnimator.java` | 时序动画基类 |
| `androidx/core/animation/Animator.java` | 动画抽象基类 |
| `androidx/dynamicanimation/animation/AnimationHandler.java` | dynamicanimation 调度中枢 |
| `androidx/dynamicanimation/animation/SpringAnimation.java` | 弹簧动画实现 |
| `com/android/launcher3/anim/AnimatorPlaybackController.java` | 统一进度驱动 |
| `com/android/launcher3/anim/PendingAnimation.java` | 转场动画构建器 |
| `com/android/launcher3/anim/AnimationSuccessListener.java` | 区分 cancel/end 的 listener |
| `com/android/launcher3/anim/NullableAnimatorListenerAdapter.java` | listener 适配器 |
| `com/android/launcher3/LauncherAnimationRunner.java` | Binder→UI 跨进程入口 |
| `com/android/quickstep/util/animation/AsyncValueAnimator.java` | 跨 Looper 的 ValueAnimator |
| `com/android/quickstep/util/animation/AsyncAnimCallbacks.java` | 跨线程 listener 派发 |
| `com/oplus/quickstep/utils/ChoreographerUtil.java` | N 帧延迟工具 |
| `com/oplus/quickstep/utils/AnimationController.java` | Recents 动画状态机（含 3 种超时监听器） |
| `com/oplus/quickstep/utils/DefaultAnimationController.java` | AnimationController 的 no-op 基类 |
| `com/oplus/quickstep/utils/AnimationSeqHelper.java` | Recents 动画 SeqId 防抖 |
| `com/oplus/quickstep/utils/OplusAnimManager.java` | feature flag 工厂单例 |
| `com/oplus/quickstep/utils/AnimationFeatureHelper.java` | 远程灰度配置（通过 RusConfig） |
| `com/oplus/quickstep/anim/OplusSpringObjectAnimator.java` | SpringProperty 装饰器实现"渐进切换" |
| `com/oplus/quickstep/utils/OplusValueAnimator.java` | timeController 委托模式 + 续行动画 |
| `com/oplus/basecommon/thread/LooperExecutor.java` | 跨线程 Executor 封装（Handler + Looper） |
| `androidx/dynamicanimation/animation/DynamicAnimation.java` | 弹簧物理引擎基类（与 ValueAnimator 关键差异） |

> 修订建议——`AGENTS.md` §12 说本仓库是 read-only，但分析/教学文档不在限制内；本文档不修改任何 `.java`，仅做研究材料归档。
>
> **第 2 版说明**：本文档第一版基于 Grep 片段 + Android 公开 API 知识整理，第二版用 Python 读取了完整源文件，新增了 §6.5（OplusValueAnimator）、§6.9（AnimationSeqHelper）、§6.10（OplusAnimManager）、§6.11（AnimationFeatureHelper）四个章节，并修订了 §6.4（区分两种双驱动模式）、§6.8（补充 3 种超时监听器）、§10（修订记录）。