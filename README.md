# Lumen

面向 Minecraft 1.8.9 的性能优化 mod。每一项优化都<b>可以单独关闭</b>，
总开关关掉后行为与未安装本 mod 完全一致。

## 优化项

### 1. 光照 —— Starlight 风格引擎重写

> **说明**：Starlight 官方不支持 1.8.9，它依赖 1.13+ 的区块存储接口
> （`PalettedContainer`、`SectionStorage`），1.8.9 只有 `NibbleArray`。
> 因此这里是**算法骨架的移植**，不是逐行照搬。

原版 `World#checkLightFor` 是「单点触发、整片重算」：每次调用都从触发点重新洪泛，
把整片连通区域的光值重新推导一遍。同一片区域被反复推导 —— 爆炸改 100 个方块
就是 100 次整片洪泛。

`LightEngine` 改成 Starlight 的**增量**模型，把增光与减光分开：

| 路径 | 做法 | 代价 |
|---|---|---|
| 增光 | BFS 把光往外推，遇到够亮的邻居就停 | 每方块最多入队一次 |
| 减光 | 先清空受影响区域并收集边界点，再统一重建 | 每方块最多访问一次 |

复杂度从 `O(改动数 × 区域面积)` 降到 `O(改动数 × 传播半径)`。

**与原版语义对齐的细节**（缺一就会出现细微画面差异）：
- 天空光 `canSeeSky` 时原始值为 15；
- `opacity >= 15 且发光 > 0` 时 opacity 折为 1（萤石/海晶灯的特例）；
- 传播衰减 `neighbor - max(1, opacity)`；
- 曼哈顿距离 < 17 的传播上限。

**零分配**：原版洪泛每个方块要 `new BlockPos` + `new MutableBlockPos`，
这里全程用打包成 `int` 的坐标与复用的 `MutableBlockPos`。

- 配置：`world.starlightLightEngine`（默认开）
- **冲突自动降级**：检测到 OptiFine 等整体接管光照的 mod 时自动关闭引擎
  并退回批处理；检测到光影/动态光源这类「消费光照」的 mod 时只关闭区段折叠。
  冲突原因会打进日志。这部分不做的话，两个光照引擎会互相覆盖结果，
  表现为光照闪烁/残留亮斑，且**不崩溃** —— 几乎无法归因。
- 引擎启用时 `LightingOptimizer` 的批处理会完全让路（两者同时生效会互相破坏）。

### 1b. 光照批处理（引擎关闭时的回退路径）

`LightingOptimizer` 把 checkLight 按 `BlockPos#toLong()` 去重后收集，
在 tick 边界统一冲刷，并额外做**区段级折叠**（同一 16³ 区段改动过多时整段重算）。
队列用自写的 `LongHashSet`（开放寻址 + 线性探测）替代 `HashSet<Long>`。

- 配置：`core.enableLightBatching`

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

**异步遮挡剔除**（参考 EntityCulling，`culling.asyncOcclusion`）：把射线判定挪到 worker。

> 关键在于**后台线程绝不能碰 `World`** —— 1.8.9 的 World 完全不是线程安全的，
> 后台读方块会与主线程的区块加载竞争，结果是偶发崩溃或静默的数据损坏。
> 因此主线程只做「快照射线沿途的方块是否挡光」这点数据，worker 处理纯数组。
> 结果有 1~2 帧延迟，期间按「可见」处理（安全方向）。

**MoreCulling 风格增强**（`culling.cullLeavesOcclusion` / `beaconBeamCulling` / `mapSignCulling`）：
- 树叶参与遮挡判定（森林收益明显，代价是被厚树叶挡住的实体会提前消失）
- 信标光柱独立视锥剔除（原版几乎从不剔除光柱）
- 六面被完全包围的地图/告示牌整块跳过

> MoreCulling 的「完整方块（完整化）」功能**没有实现**：它依赖 1.13+ 的
> `BlockState#isSideInvisible` 逐面可见性，1.8.9 的 `Block#isFullCube()` 只有方块级布尔值。
> 强行实现会改变外观 —— 与其做一个让人看出来的「优化」，不如不做。

### 3b. 方块实体 —— 渲染包围盒缓存（参考 Optimized Block Entities）
原版 `RenderGlobal#renderEntities` 的方块实体循环里 `getRenderBoundingBox()` 被调用**三次**，
而默认实现是 `return new AxisAlignedBB(...)`。500 个箱子的仓库每帧产生 1500 个短命对象，
持续推高年轻代 GC 频率 —— 而 GC 暂停正是帧时间抖动的主因。

`BlockEntityOptimizer` 按「实例 + 位置」缓存包围盒。**只缓存恰好等于位置处单位立方体的**
包围盒（显然只依赖位置），信标光柱这类会变的不缓存，行为与原版一致。

- 配置：`render.blockEntityOptimization`

### 4. 渲染 —— HUD 文本烘焙成纹理

`HudTextureCache` 把静态 HUD 文本渲染进一张离屏纹理，之后每帧只画一个四边形。

> 不能直接 `glClear` 一个纹理 —— `glClear` 清的是当前绑定的 framebuffer，
> 纹理只是"被渲染目标"时才受影响。常见偷懒写法会让缓存图逐帧叠加成黑斑。

缓存键包含字体、Unicode 标志、GUI 缩放；LRU 淘汰时同步 `deleteTexture`（否则泄漏显存）。

- 配置：`render.hudTextureCache`、`render.hudCacheEntries`

**主菜单移除 Realms 按钮**（`render.removeRealmsButton`）：在 `List#add` 这一层拦掉，
按钮根本不进列表，同时把 Mods 按钮从右半宽改成整宽居中，否则左边会空出 100px 的洞。

> 判断依据用 `id == 14 && width == 98` 双重确认：单看 id 已经够，加上宽度是为了避免
> 别的 mod 恰好复用 id 14 时误删一个无辜的按钮 —— 误删比留着一个 Realms 按钮糟得多。

### 5. 世界 —— 流体流向缓存

`BlockLiquid#getFlowVector` 做 4 次 `getBlockState` + 多次流向查询，而它被调用的频率
远高于水流真的变化的频率（每个泡在水里的实体每 tick 一次）。缓存按 tick 失效，
最多延迟 1 tick（50ms），玩家不可感知，但绝不可能长时间错。

- 配置：`world.fluidFlowCache`

### 6. 粒子 —— 数量上限 + 距离分档（参考 AsyncParticles / FPSFlow）

粒子不能套用实体那套剔除逻辑，原因有三：**单体极小**（遮挡判定每条射线 6 次
方块查询，比粒子本身还贵）、**生命周期极短**（迟滞类优化跑不满）、但**数量可以上万**
（1331 个营火 → 16000+ 粒子，这是 AsyncParticles 的实测场景）。

所以用两个手段：

- **每帧数量上限**（`culling.particleLimit`，默认 4000）——唯一能把最坏情况兜住的东西。
  密集粒子场景本来就淹没画面，少画一部分观感几乎无差别，但帧时间立刻可预测。
- **距离三档**（`particleLodMid` / `particleLodFar`）——近处全绘、中间档隔一个、
  最远档不画。对应 FPSFlow 的 "three-zone density LOD"。

> 中间档用的是**同一帧内按索引隔一个画**，不是「隔帧画」。粒子的位置每 tick 都在变，
> 隔 tick 更新会让它们肉眼可见地一顿一顿跳；而隔一个画只会让远处密度减半 ——
> 视觉上是"变稀"，不是"卡顿"。

判定顺序刻意为「先 LoD（纯算术）→ 后视锥遮挡（查方块）」。反过来的话，
密集场景下每帧要多做上千次方块查询。

- 配置：`culling.particleLod`、`culling.particleLodMid`、`culling.particleLodFar`、`culling.particleLimit`

### 7. 玩家皮肤 LoD

两级降级：

- **装饰层跳过**（`culling.skinLod`，默认开）——远处玩家跳过披风、自定义头饰。
  披风是玩家模型上单体开销最高的装饰层（额外一整面双面四边形 + 飘动物理插值），
  而 32 格外的玩家你分不出他有没有披风。
- **纹理降采样**（`culling.skinLodTexture`，**默认关**）——远处玩家用 32×32 皮肤。

> **刻意不跳过盔甲层。** 盔甲在 PvP 里是关键信息，远距离判断对手穿什么甲是战术需求。
> 跳过它会让 mod 在 PvP 服上不可接受。这是取舍，不是遗漏。
>
> 玩家自己**永远**完整渲染——第三人称看自己时披风缺失非常明显。

关于纹理降采样，说实话：**收益很小**。一张 64×64 RGBA 只占 16KB，40 个玩家全降采样
也省不到 1MB。真正的收益是采样局部性（远处玩家在屏幕上只占几十像素，却要对
64×64 纹理做 mipmap 采样）。而风险很实在——切换纹理要改 GL 绑定，远近玩家交替出现
会造成频繁状态变更。所以它默认关闭，且结果被缓存（每个皮肤只做一次）。

### 8. 可见面缓存 —— Ixeris 思路在 1.8.9 上的落点

先说清楚 **Ixeris 有两个手段在 1.8.9 上做不到**：

| Ixeris 手段 | 1.8.9 可行性 | 原因 |
|---|---|---|
| 线程化事件轮询（渲染踢到独立线程） | **不可行** | 依赖 1.13+ 渲染线程模型。1.8.9 的 `Minecraft#runTick` 把事件与渲染耦合在同一线程同一方法里，拆开等于重写主循环 |
| 缓冲原始输入 `GetRawInputBuffer` | **不可行** | 1.8.9 走 LWJGL 2 + DirectInput，没有 raw input 通道 |

但 Ixeris 文档指出的现象在 1.8.9 上**完全成立**：转视角会掉帧，因为"转视角时有额外工作，
比如计算区块可见性"。只是成因不同——

`RenderGlobal#setupTerrain` 在相机朝向变化时会重跑整个可见性计算，其中对**每个可见
渲染区块**都调用 `getVisibleFacings(pos)`：6 次邻接方块查询 + 新建一个 `EnumSet`。
视距 16 时数百个区块，即每帧上千次方块查询。

**而这个结果只依赖方块数据，与相机朝向无关**——转视角时它被白白重算了。
缓存到 tick 边界即可（`culling.visibleFacingCache`）。

> 为什么缓存只活一个 tick 而不是更久：1.8.9 没有可靠的"区块方块数据版本号"可用。
> 宁可每 tick 重算一次，也不能让玩家看到"方块拆了但那一面还是不画"。
> 收益依然存在——同一 tick 内 `setupTerrain` 可能被多次调用（多 pass 渲染）。

## 配置界面

Forge 1.8.9 **没有 ModMenu**（那是 Fabric 的 mod）。等价物是 Forge 内置的
`IModGuiFactory`：在 Mods 列表里点 Config 进入，见 `client/LumenGuiFactory`。

它用反射而非直接继承 `GuiConfig`。原因是个开发环境特有的坑：Forge universal jar 里
`IModGuiFactory` 的方法签名**仍是混淆名**（`mainConfigGuiClass()` 返回 `Class<? extends axu>`），
而开发用的 mapped jar 里 Minecraft 类已重映射成 MCP 名。两者混用编译直接失败，
但在**实际游戏**里它们是同一个命名空间、完全一致。

也就是说：直接写类型"开发环境编不过、运行时是对的"；反射两边都能跑。这是本项目唯一
一处刻意的反射用法——类型系统在两种命名空间下无法同时满足，没有更好的选择。

## 构建期校验

`check` 上挂了五个任务：

| 任务 | 作用 |
|---|---|
| `verifyMixinTargets` | 每个 mixin 的目标方法与注入点是否真的存在于 mapped jar 里 |
| `dispatcherSelfTest` | 异步调度器的并发行为（队列满不阻塞、代次丢弃、worker 存活、防饿死） |
| `chunkTuningSelfTest` | 区块线程自适应公式，重点验证 `builders >= workers` 不变量 |
| `lightEngineSelfTest` | 光照引擎的开关语义与 mod 冲突降级 |
| `mixinGatingSelfTest` | 每个纯客户端 mixin 是否都被正确门控（否则专用服务器启动失败） |

`verifyMixinTargets` 存在的理由：`require = 0` 会让"目标不存在"静默通过 —— 功能完全失效，
但游戏照常启动、日志干干净净。这类 bug 在运行时几乎无法定位，在构建期只需几毫秒。
开发期间它已经抓到三个真实问题：`RenderGlobal#renderTileEntities` 并不存在
（方块实体循环其实在 `renderEntities` 里）、`tryMoveToXYZ` 定义在 `PathNavigate`
而不是 `PathNavigateGround`、以及 `GuiButton` 的字段名是 `xPosition` 而不是 `x`。

> `lightEngineSelfTest` **不测光照传播**。那需要完整的 `World`/`Chunk`/`NibbleArray`，
> 得启动整个 Minecraft 运行时，与本项目「自测能在裸 JVM 上几秒跑完」的取向冲突。
> 传播的正确性必须进游戏确认 —— 这一点写在注释里，而不是用"测试通过"掩盖。

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
