# Lumen

面向 Minecraft 1.8.9 的性能优化 mod。每一项优化都<b>可以单独关闭</b>，
总开关关掉后行为与未安装本 mod 完全一致。

## 优化项

### 1. 光照 —— 合并同一 tick 内的校验

原版 `World#setBlockState` 每改一个方块就立刻 `checkLight`，对天空光与方块光各做一次
6 邻域洪泛。爆炸、多方块结构生成、跑图填充会在同一 tick 内对同一批位置反复触发。

`LightingOptimizer` 把这些校验按 `BlockPos#toLong()` 去重后收集，在 tick 边界统一冲刷。
队列用自写的 `LongHashSet`（开放寻址 + 线性探测）替代 `HashSet<Long>`，
消除每次 `add` 的装箱分配。

- 配置：`core.enableLightBatching`
- 安全性：队列超过 8192 时放弃批处理、回退原版即时执行；冲刷发生在渲染之前。

### 2. 线程 —— 区块构建、异步寻路

**区块网格构建线程数自适应**（`threads.chunkBuilderThreads`，默认 -1 = 自动）。

> 公式来自对 Lunar Client 1.9 的实测反汇编：
> ```
> memoryBudget = max(1, 堆上限 * 0.3 / 10MB)
> threads      = max(1, min(核心数 - 2, memoryBudget / 5))
> builders     = max(1, min(threads * 8, memoryBudget))
> ```
> 用**堆大小反向约束线程数**是关键：每个构建器都持有按图层切分的顶点缓冲，
> 小内存机器上开满线程会频繁 GC 甚至 OOM，净帧率反而下降。
> `核心数 - 2` 则是留两个核给主线程与 GC。
>
> 实测参考：4核2G → 2线程；8核4G → 6线程；12核8G → 10线程；32核2G → 12线程（被内存压住）。
>
> ⚠️ 改线程数**必须**同时扩容构建器池。原版 `allocateRenderBuilder()` 是无条件的
> `BlockingQueue#take()`，池里只有 5 个构建器。worker 数一旦超过池容量，多出来的
> worker 就会永久阻塞在 `take()` 上 —— 不崩溃、不报错，只是跑图时区块一片片不加载，
> 帧数反而下降。配置项 `0` = 强制原版 2 线程，正数 = 强制指定值。

**这项优化不涉及区块「加载」。** 需要区分两个概念：

| | 区块**网格构建** | 区块**加载** |
|---|---|---|
| 做什么 | 方块数据 → 顶点缓冲 | 从磁盘/网络读 NBT、生成地形 |
| 本 mod | 多线程（如上） | **未改动，单线程** |
| 卡顿表现 | 转向时区块“白一下” | 跑图时前方一片虚空 |

**异步寻路**（`threads.asyncPathfinding`）：把 A* 挪到后台线程。

- 每个任务自带私有的 `PathFinder` / `WalkNodeProcessor`（两者都是有状态的，共享会互相踩数组）。
- 计算在后台，`setPath` 回主线程 —— 跨线程写 `currentPath` 会导致实体沿不存在的路径抽搐。
- 同一实体有冷却窗口合并高频请求。
- 队列满 / 导航器类型不符 / 功能关闭时**自动回退原版同步寻路**。

### 3. 剔除 —— 视锥 + 遮挡

**视锥**由 `FrustumCache` 从 `投影 × 模型视图` 矩阵直接提取世界空间平面。

> 不能复用原版 `Frustum`：它把包围盒减掉一个"摄像机位置"再交给 `ClippingHelper`，
> 而 `ClippingHelper` 的平面来自视图空间矩阵。两者只在摄像机正对 ±Z 轴时等价；
> 第三人称、躺下、视角摇晃都会让这个近似失真。用它做遮挡剔除会造成成片误剔。

**遮挡**用沿线段等距采样（固定 6 次方块查询，与距离无关），而非逐格 DDA 射线。

> 逐格 `rayTraceBlocks` 每条射线要遍历几十个方块，256 条射线就是上万次查询 ——
> 剔除本身会比它省下的渲染更贵。采样法的错误方向是安全的：漏判只是少省一点，
> 画面永远正确；误判由迟滞计数 + "只在整块不透明方块处中止"压住。

覆盖：
- 方块实体（TESR）遮挡剔除（`culling.tileEntityCulling`）
- 实体遮挡剔除（`culling.entityOcclusionCulling`）
- 粒子视锥剔除（`culling.particleCulling`）
- 射线预算（`culling.occlusionRayBudget`，用完即按"可见"处理，绝不让剔除自己变成瓶颈）

### 4. 渲染 —— HUD 文本烘焙成纹理

`HudTextureCache` 把静态 HUD 文本渲染进一张离屏纹理，之后每帧只画一个四边形。

> 不能直接 `glClear` 一个纹理 —— `glClear` 清的是当前绑定的 framebuffer，
> 纹理只是"被渲染目标"时才受影响。常见偷懒写法会让缓存图逐帧叠加成黑斑。

缓存键包含字体、Unicode 标志、GUI 缩放；LRU 淘汰时同步 `deleteTexture`（否则泄漏显存）。

- 配置：`render.hudTextureCache`、`render.hudCacheEntries`

### 5. 世界 —— 流体流向缓存

`BlockLiquid#getFlowVector` 做 4 次 `getBlockState` + 多次流向查询，而它被调用的频率
远高于水流真的变化的频率（每个泡在水里的实体每 tick 一次）。缓存按 tick 失效，
最多延迟 1 tick（50ms），玩家不可感知，但绝不可能长时间错。

- 配置：`world.fluidFlowCache`

## 构建期校验

`verifyMixinTargets`（挂在 `check` 上）会检查每个 mixin 的目标方法与注入点是否真的
存在于 mapped jar 里。

存在的理由：`require = 0` 会让"目标不存在"静默通过 —— 功能完全失效，但游戏照常启动、
日志干干净净。这类 bug 在运行时几乎无法定位，在构建期只需几毫秒。
开发期间它已经抓到两个真实问题：`RenderGlobal#renderTileEntities` 并不存在
（方块实体循环其实在 `renderEntities` 里），以及 `tryMoveToXYZ` 定义在
`PathNavigate` 而不是 `PathNavigateGround`。

## 调试

F3 界面左下角会显示：

```
Lumen: TESR 12/480  Entity 3/27  Part 100/1400  Ray 256  HUD 92%  Fluid 87%
queued=0 done=2 submitted=1532 delivered=1530 dropped=0 stale=0 failed=0 slow=3
path(sub=88,done=88,skip=0)
```

- `TESR` / `Entity` / `Part`：可见 / 总数
- `Ray`：本帧实际发射的遮挡射线数（受预算限制）
- `HUD` / `Fluid`：缓存命中率
- `dropped`：队列满被丢弃的任务数（持续增长说明队列容量偏小）
- `slow`：超出时间预算的任务数（持续增长说明某项计算退化了）

## 配置

首次启动后生成 `config/lumen.cfg`。所有开关见文件内注释。
