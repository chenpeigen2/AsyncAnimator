# OPPO Launcher 异步动画线程 — Perfetto trace 实证分析

> 数据：`C:\Users\peigen1.chen\Downloads\oppo_launcher_anim_async.pftrace`（29.4 MB，时长 10.0 s），场景 = **桌面点击图标冷启动应用（OPEN_FROM_HOME）**。
>
> 设备：MTK 平台（trace 中可见 `mali-event-hand`、`chooseCompositionStrategy for MTKDEV`）。**注意：该设备与反编译源码树（flavor `OPPOPallDomesticAall`）可能不是同一机型/版本**，差异处在 §6 如实标注。
>
> 工具：trace_processor v57.2 + perfetto Python API。本文与代码侧分析（`animation-thread-analysis-v4.md`）互相印证。

---

## 1. 结论（TL;DR）

代码侧 v4 分析的核心论断**被 trace 直接证实**：

1. **`launcher.anim` 线程真实存在并承担启动动画**：进程 `com.android.launcher`（pid 3124）内 tid 3342 即 `launcher.anim`。点击图标后，启动动画标记 `#26-OPEN_FROM_HOME-Start` **出现在 launcher.anim 线程上**；随后该线程连续执行 **77 个 doFrame**（58.3ms → 686.3ms，约 628ms），每帧做两件事：
   - `animation`（0.7~1.3ms）——动画值逐帧推进（对应代码侧 CustomRectFSpringAnim 的弹簧积分 + RectF 计算）；
   - 一次 **binder transaction → SurfaceFlinger**（0.2~0.4ms）——leash 的矩阵/裁剪/圆角逐帧提交（flow 证据见 §4）。
2. **主线程同时段是自由的**：动画的 630ms 里主线程只做触摸事件收尾、`activityPause/Stop`、自身 View 树绘制，大量时间空闲——"leash 矩阵变化在 launcher.anim 里跑、主线程可继续做其他事情"成立。
3. **帧节奏稳定**：77 帧间隔中位 8.26ms（120Hz），min 7.89 / max 8.70，全程无掉帧。
4. **一处与代码推断不符**：launcher.anim 与主线程的帧时钟相位**完全一致**（都对齐 VSYNC-app，中位延迟 0.33ms），代码中 `SfVsyncFrameCallbackProvider`（SF-vsync 帧源）在这条 trace 上**没有体现**。详见 §6——这不影响"独立线程执行 + 直发 SF"的架构结论，但说明帧源替换在该设备/版本上未生效或不必要。

## 2. 线程清单：trace 实证 vs 代码注册表

trace 中 `com.android.launcher` 进程的关键线程与 `com/oplus/basecommon/thread/` 代码一一对应：

| trace 线程（tid） | 代码中的 Executor | 代码出处 |
|---|---|---|
| `ndroid.launcher`（3124，主线程） | `Executors.MAIN_EXECUTOR` | Executors.java:54 |
| **`launcher.anim`（3342）** | `OplusExecutors.ANIM_EXECUTOR` | OplusExecutors.java:95 |
| `onlineUXThread`（3712） | `UX_TASK_EXECUTOR` | OplusExecutors |
| `UrgentTransacti`（3728） | `URGENT_TRANSACTION_EXECUTOR` | OplusExecutors |
| `WallpaperTransa`（3649） | `WALLPAPER_TRANSACTION_EXECUTOR` | OplusExecutors |
| `UiThreadHelper`（3343） | `UI_HELPER_EXECUTOR` | Executors.java |
| `launcher-loader`（3273） | `MODEL_EXECUTOR` | Executors.java |
| `RecentTasksList`（3702） | `RECENT_TASKS_EXECUTOR` | OplusExecutors |
| `FetchIcon`（3714） | `FETCH_ICON_EXECUTOR` | OplusExecutors |

（线程名在 trace 中被截断为 15 字符，如 `UrgentTransacti` = UrgentTransactionHelper。）

## 3. 启动场景完整时间线

基准 0ms = 主线程 `____cmz_appStart_p0____`（点击触发 app 启动标记）。点击坐标 (182, 2090)，即桌面图标（`OplusBubbleTextView`）。

| 时间 | 线程 | 事件 |
|---|---|---|
| -43.9ms | main | `dispatchInputEvent ACTION_DOWN` → `OplusBubbleTextView.onTouch`（38.6ms 触摸处理链） |
| -5.2ms | main | `ACTION_UP`（9.2ms） |
| 0ms | main | `____cmz_appStart_p0____`（2.9ms） |
| +3.4ms | system_server | `executeStartActivity`（35.9ms）→ `startActivityInner` |
| +41.4ms | main | `activityPause` / `performPause:com.android.launcher.Launcher` |
| +45.6ms | main | `android.os.Handler: com.android.launcher3.q1`（6.3ms） |
| **+51.9ms** | **launcher.anim** | **`#26-OPEN_FROM_HOME-Start`**（启动动画开始标记） |
| **+58.3ms** | **launcher.anim** | **第 1 个 doFrame**（动画逐帧执行开始） |
| … | launcher.anim | 连续 77 帧，每帧 `animation` + `binder transaction`→SF |
| +58.3~686.3ms | main | （并行）自身 doFrame 66 次：View 树绘制、workspace 侧动画；其余时间空闲 |
| +686.3ms | launcher.anim | 最后 1 个 doFrame |
| **+687.8ms** | **main** | **`#26-OPEN_FROM_HOME-End`**（5.3ms）+ `TracePrintUtil#notifyAnimationEnd` |
| +710.8ms | main | `activityStop` / `performStop` |

两个要点：

- **开始标记在 launcher.anim、结束标记在 main**，相隔约 636ms——这正是代码侧"动画时钟在 launcher.anim、结束回调经 AsyncAnimCallbacks marshal 回主线程"（`CustomRectFSpringAnim.runOnMainThread` → `Utilities.postAsyncCallback(MAIN_EXECUTOR)`）的运行时投影。
- 动画期间主线程并非停摆：它继续跑自己的 Choreographer（66 帧），处理 pause/stop 生命周期和桌面 View 树——跨线程并行是真实的。

## 4. 每帧结构：animation + binder → SurfaceFlinger

77 帧中的每一帧，launcher.anim 上的 slice 序列都是：

```
Choreographer#doFrame <id>            (0.7~1.3ms)
└─ animation                          (0.7~1.3ms)   ← 弹簧积分 + RectF/矩阵计算
binder transaction                    (0.2~0.4ms)   ← 本帧 leash 更新提交
```

binder transaction 的 args（每帧相同形态）：

- `destination process = 1286`、`destination name = binder:1286_5` —— pid 1286 即 **SurfaceFlinger**；
- `calling tid = 3342` —— 调用线程确为 launcher.anim；
- `code = 0x08`，`flags = 0x10`。

flow（binder 流向）追踪确认：`launcher.anim` 的每笔 binder transaction 的对端都是 `/system/bin/surfaceflinger` 的 `binder reply`。77 帧 × 77 笔，无一遗漏。

**含义**：leash 的 `SurfaceControl.Transaction`（窗口矩阵、裁剪、圆角——对应代码 `RectTransformHelper`/`IconLayerUpdater` 的逐帧写入）是在 launcher.anim 线程上算好并直接发给 SurfaceFlinger 的，**不经过主线程，也不等待主线程的 RenderThread 帧**。这是整条异步方案的性能本质：主线程的卡顿不会直接拖慢窗口弹簧动画的提交节奏。

## 5. 帧时钟分析：一个与代码推断不符的发现

代码侧（v4 §3）：`OplusExecutors.java:170` 在 launcher.anim 线程上执行 `AnimationHandler.getInstance().setProvider(new SfVsyncFrameCallbackProvider())`，推断该线程帧源为 SF-vsync。

trace 实测（全 77 帧窗口，对齐最近 vsync 的中位相位延迟）：

| doFrame 所在线程 | vs VSYNC-app | vs VSYNC-appSf | vs VSYNC-sf |
|---|---|---|---|
| launcher.anim | **0.33ms** | 5.26ms | 5.32ms |
| main | **0.31ms** | 5.25ms | 5.31ms |

两个线程的帧回调**同样紧跟 VSYNC-app**（app 相位 vsync），相位差中位数一致；与 SF 相位的 VSYNC-appSf / VSYNC-sf 都差 5.3ms 左右。帧间隔两线程同为 ~8.26ms（SurfaceFlinger 侧 `NextFrameInterval 121_Hz`）。

可能的解释（按可能性排序）：

1. **设备/版本差异**：本 trace 来自 MTK 机型，反编译树是 `OPPOPallDomesticAall`（15.8.24）。不同机型/版本的 launcher 构建可能未启用或改写了 SF-vsync provider 路径。
2. `SfVsyncFrameCallbackProvider` 在此 ColorOS 框架分支上的实际行为就是回落到 app 相位（provider 设置失败被静默吞掉，或框架实现与 AOSP 不同）。
3. 该启动场景走的分支没有触发 provider 生效的条件（例如动画实际由框架 ValueAnimator 路径驱动，而该路径在此线程上用了默认 provider）。

**对架构结论的影响**：有限。"独立动画线程 + 每帧直发 SurfaceFlinger"的收益（主线程解耦、提交节奏不被主线程卡顿拖慢）不依赖帧源相位；SF-vsync 对齐只是进一步优化（与 SF 合成时刻对齐可减少半帧延迟）。lib 重实现时，帧源可换应作为可选项而非必需项（见 v4 §8 第 1 条的修正）。

## 6. 代码论断 ↔ trace 证据对照表

| v4 代码侧论断 | trace 证据 | 判定 |
|---|---|---|
| `launcher.anim` 专用动画线程存在（OplusExecutors.java:95） | tid 3342 `launcher.anim` | ✅ 证实 |
| 窗口弹簧动画在该线程逐帧执行（CustomRectFSpringAnim mStartAsync 默认 true） | `#26-OPEN_FROM_HOME-Start` 在 launcher.anim；77 连续 doFrame，每帧 `animation` | ✅ 证实 |
| 每帧 leash 更新写 SurfaceControl.Transaction | 每帧 1 笔 binder → surfaceflinger（flow 对端 binder reply），calling tid=3342 | ✅ 证实 |
| 主线程并行处理其他工作 | 动画期间 main 跑自身 66 帧 + activityPause/Stop，大量空闲 | ✅ 证实 |
| 结束回调 marshal 回主线程（AsyncAnimCallbacks） | `-Start` 在 launcher.anim（+51.9ms）、`-End` 在 main（+687.8ms） | ✅ 证实（行为一致） |
| UX_TASK / UrgentTransaction / WallpaperTransaction 等辅助线程族 | trace 线程清单一一命中 | ✅ 证实 |
| launcher.anim 帧源 = SF-vsync（SfVsyncFrameCallbackProvider） | 两线程帧相位一致，均对齐 VSYNC-app | ⚠️ **未证实，实测为 app-vsync 相位**（§5） |
| 帧间隔对齐整数帧（DynamicAnimation SF 帧间隔对齐逻辑） | 帧间隔 7.89~8.70ms，中位 8.26ms，围绕 120Hz 周期小幅抖动 | ✅ 行为一致（无法区分是哪段代码的贡献） |

## 7. 对 AsyncAnimator lib/demo 的启示（基于 trace 修正）

1. **必须复刻的核心**：独立动画线程 + 每帧"计算 → 直接提交事务"的管道。demo 中可以用一个计数/日志桩模拟"每帧一笔 SF 提交"，断言 77 帧节奏下主线程 doFrame 不受动画线程工作量影响。
2. **帧源可换降级为可选**：真机 trace 显示原厂运行时两线程同相位也能获得全部收益；`TickScheduler` 的 per-thread 安装能力保留即可，不必强制模拟 SF-vsync。
3. **结束回调跨线程 marshal 是硬需求**：`-Start`(anim) → `-End`(main) 的 636ms 跨度证明 listener 回主线程的机制（AsyncAnimCallbacks）是真实路径，demo 测试应断言"帧回调在动画线程、end listener 在主线程"。
4. **性能数字可作参照**：原厂每帧动画计算 0.7~1.3ms、binder 提交 0.2~0.4ms，合计 <2ms/帧 @120Hz——lib 的 demo 实现可以此为性能预算基线。

---

## 附录：分析方法（可复现）

```bash
pip install perfetto   # venv 内；首次运行会自动下载 trace_processor_shell
# 关键查询（trace_processor SQL）：
# 线程:  SELECT utid,tid,name,is_main_thread FROM thread t JOIN process p ON t.upid=p.upid WHERE p.name='com.android.launcher'
# 帧:    SELECT ts,dur,name FROM slice s JOIN thread_track tt ON s.track_id=tt.id WHERE tt.utid=<anim_utid> AND name LIKE 'Choreographer#doFrame%'
# binder 对端: slice JOIN flow ON flow.slice_out=slice.id 查 binder transaction 的 reply 进程
# vsync 相位: counter JOIN counter_track WHERE name IN ('VSYNC-app','VSYNC-appSf','VSYNC-sf')，对 doFrame 时间戳做最近邻相位差
```

分析脚本（一次性的，未入库）：`pf_recon.py` / `pf_analyze.py` / `pf_analyze2.py` / `pf_align2.py`（位于 `%TEMP%`，如需复跑可重写）。
