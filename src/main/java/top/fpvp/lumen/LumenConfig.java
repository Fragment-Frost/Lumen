package top.fpvp.lumen;

import net.minecraftforge.common.config.Configuration;

/**
 * 所有优化点的开关与参数。
 *
 * <p>设计原则：<b>每一项都能单独关掉</b>。性能优化本身是有风险的改动，
 * 一旦出现视觉异常或崩溃，玩家需要能靠改配置把嫌疑范围缩小到单个功能，
 * 而不是被迫整个卸载 mod。因此这里刻意不做“一个大开关”，而是按子系统拆分。
 */
public final class LumenConfig {

    /**
     * 配置分类名。
     *
     * <p>公开是为了让 {@code LumenGuiFactory} 能按同样的分组构建界面 ——
     * 界面与配置文件的结构必须一致，否则玩家在界面里改了一项，
     * 回头翻 config 文件会对不上位置。
     */
    public static final String CAT_CORE = "core";
    public static final String CAT_THREADS = "threads";
    public static final String CAT_CULLING = "culling";
    public static final String CAT_RENDER = "render";
    public static final String CAT_WORLD = "world";
    public static final String CAT_DEBUG = "debug";

    /** 总开关：关掉后所有 mixin 逻辑立即退化为原版行为。 */
    public static boolean enabled = true;

    /** 光照校验批处理（既有功能，保留原字段名以免破坏既有配置）。 */
    public static boolean enableLightBatching = true;

    /**
     * 区段级光照折叠。
     *
     * <p>同一 16³ 区段内超过阈值个位置需要更新时，折叠成一次整段重算。
     * 爆炸、多方块结构生成时能省下大量重复的区段洪泛。
     *
     * <p>启用 {@link #starlightLightEngine} 后本项的收益大幅下降（引擎已经是增量的），
     * 因此两者同时开启时本项会被自动压制。
     */
    public static boolean enableSectionCollapse = true;

    /**
     * 启用 Starlight 风格的光照引擎重写。
     *
     * <p>用「增光 BFS + 减光 BFS」的增量模型替代原版「单点触发、整片重算」。
     * 每个方块在一次更新中最多被访问常数次，高密度改动场景下复杂度显著下降。
     *
     * <p>这是本项目侵入性最强的优化。检测到 OptiFine 等整体接管光照的 mod 时
     * 会自动关闭（见 {@code LightEngineCompat}）。
     */
    public static boolean starlightLightEngine = true;

    // ---- 界面 ----
    /** 从主菜单移除 Realms 按钮，并把 Mods 按钮补成整宽。 */
    public static boolean removeRealmsButton = true;

    // ---- GL / 渲染管线 ----
    /**
     * GL 状态缓存：跳过与上次相同的冗余状态设置。
     *
     * <p>参考 GPUBooster 的 "GL targets cache"。1.8.9 的渲染管线每帧会发出
     * 成千上万次 {@code glBindTexture} / {@code glEnable} 调用，其中大量是
     * 重复的（同一纹理连续绑定几十次）。这些调用都要过 JNI，单次开销不小。
     */
    public static boolean glStateCache = true;

    // ---- 方块实体 ----
    /**
     * 方块实体渲染优化：跳过完全离屏的 TESR，并缓存渲染包围盒。
     *
     * <p>参考 Optimized Block Entities：核心是减少每个方块实体每帧的
     * 重复计算（包围盒重建、旋转/平移矩阵推导）。
     */
    public static boolean blockEntityOptimization = true;

    // ---- MoreCulling 风格 ----
    /** 把树叶当作完整方块参与遮挡判定（树叶密集的森林里收益明显）。 */
    public static boolean cullLeavesOcclusion = true;
    /** 信标光柱的视锥剔除（原版完全不剔除，远处的光柱一直画）。 */
    public static boolean beaconBeamCulling = true;
    /** 地图与告示牌的贴墙剔除（贴墙时只画朝外那一面）。 */
    public static boolean mapSignCulling = true;

    // ---- 异步遮挡剔除 ----
    /**
     * 用后台线程做遮挡射线追踪（参考 EntityCulling 的 async path-tracing）。
     *
     * <p>与当前同步采样方案的关系：同步方案每帧最多打
     * {@code occlusionRayBudget} 条射线；异步方案把射线丢给 worker，
     * 结果按帧回收，因此可以在同样的主线程预算下获得更多候选判定。
     */
    public static boolean asyncOcclusion = true;

    // ---- 线程 ----
    /**
     * 区块网格构建线程数。
     *
     * <p>{@code -1}（默认）= 自适应，按 CPU 核数与堆内存自动推算。
     * {@code 0} = 强制使用原版值（2 个）。正数 = 强制使用该值。
     *
     * <p>自适应公式源自对 Lunar Client 的实测反汇编（见
     * {@code MixinChunkRenderDispatcher} 的注释），在高核数机器上能把
     * 2 个构建线程提升到接近核心数的水平，这是跑图时区块加载卡顿的主要解法。
     */
    public static int chunkBuilderThreads = -1;
    /** 异步任务队列容量上限，满了直接丢弃（而不是阻塞主线程）。 */
    public static int asyncQueueCapacity = 512;
    /** 单个异步任务允许执行的最长时间，超时会被记录并中止。 */
    public static int asyncTaskBudgetMs = 8;
    /** 实体寻路异步化：把 A* 挪到 worker 线程。 */
    public static boolean asyncPathfinding = true;
    /** 同一个实体两次寻路请求之间的最小间隔（tick），用于合并高频请求。 */
    public static int pathfindRequestCooldown = 10;

    // ---- 剔除 ----
    /** 方块实体（TESR）的视锥 + 遮挡剔除。 */
    public static boolean tileEntityCulling = true;
    /** 普通实体的遮挡剔除（视锥剔除原版已有，这里只补遮挡）。 */
    public static boolean entityOcclusionCulling = true;
    /** 粒子系统的视锥剔除。 */
    public static boolean particleCulling = true;

    // ---- 粒子 LoD（参考 AsyncParticles / FPSFlow）----
    /**
     * 粒子距离分档 LoD：远处粒子减密或完全不画。
     *
     * <p>粒子的开销与实体不同：单体极小（遮挡判定不划算）、生命周期极短
     * （迟滞类优化跑不满）、但数量可以上万。因此用「数量上限 + 距离分档」
     * 而不是逐个射线判定。
     */
    public static boolean particleLod = true;
    /** 中间档距离（格）：超过后粒子隔一个画一个。0 = 关闭该档。 */
    public static double particleLodMid = 16.0D;
    /** 最远档距离（格）：超过后完全不画。0 = 关闭该档。 */
    public static double particleLodFar = 32.0D;
    /**
     * 每帧最多绘制多少个粒子。0 = 不限制。
     *
     * <p>这是唯一能把最坏情况兜住的手段：密集粒子场景本来就会淹没画面，
     * 少画一部分在观感上几乎无差别，但帧时间立刻可预测。
     */
    public static int particleLimit = 4000;

    // ---- 玩家皮肤 LoD ----
    /**
     * 玩家皮肤 LoD 总开关。
     *
     * <p>远处玩家跳过披风/帽子等装饰层。盔甲层<b>永远保留</b> ——
     * 那是 PvP 里的关键信息。
     */
    public static boolean skinLod = true;
    /** 超过这个距离（格）跳过装饰性渲染层。 */
    public static double skinLodLayerDistance = 32.0D;
    /**
     * 是否对远处玩家使用降采样皮肤纹理。
     *
     * <p><b>默认关闭</b>：收益很小（一张 64×64 皮肤只占 16KB），
     * 而切换纹理会造成 GL 绑定变更。想试的人可以单独打开。
     */
    public static boolean skinLodTexture = false;
    /** 超过这个距离（格）使用降采样皮肤。 */
    public static double skinLodTextureDistance = 48.0D;

    /**
     * 可见面集合缓存（Ixeris 指出的「转视角掉帧」在 1.8.9 上的落点）。
     *
     * <p>{@code RenderGlobal#getVisibleFacings} 的结果只依赖方块数据、
     * 与相机朝向无关，但原版在朝向变化时会为每个可见区块重算一遍。
     * 缓存到 tick 边界，避免转视角时的重复计算。
     */
    public static boolean visibleFacingCache = true;
    /** 每帧允许发射的遮挡检测射线数量上限（防止剔除本身变成瓶颈）。 */
    public static int occlusionRayBudget = 256;
    /** 遮挡检测射线的最大长度（格）。 */
    public static double occlusionRayLength = 48.0D;
    /** 命中后判定为“被遮挡”的连续帧数（迟滞，避免边缘闪烁）。 */
    public static int occlusionHysteresis = 3;

    // ---- 渲染 ----
    /** 把 HUD 文本缓存成纹理。 */
    public static boolean hudTextureCache = true;
    /** HUD 纹理缓存的最大条目数。 */
    public static int hudCacheEntries = 192;

    // ---- 世界 ----
    /** 流体流向向量按 tick 缓存，减少重复的邻居扫描。 */
    public static boolean fluidFlowCache = true;

    // ---- 调试 ----
    /** 在 F3 调试界面右侧追加 Lumen 的统计行。 */
    public static boolean debugOverlay = true;
    /** 每个统计项单独打印（更啰嗦，排查用）。 */
    public static boolean verboseDebug = false;

    private LumenConfig() {
    }

    public static void load(Configuration cfg) {
        try {
            cfg.load();
            loadCore(cfg);
            loadThreads(cfg);
            loadCulling(cfg);
            loadRender(cfg);
            loadWorld(cfg);
            loadDebug(cfg);
        } catch (Exception e) {
            // 配置读失败不应该让游戏起不来：回落到默认值继续，但必须留下痕迹。
            LumenProfiler.log().error("[Lumen] 读取配置文件失败，本次使用默认值", e);
        } finally {
            if (cfg.hasChanged()) {
                cfg.save();
            }
        }
    }

    private static void loadCore(Configuration cfg) {
        enabled = cfg.getBoolean("enabled", CAT_CORE, enabled,
                "Lumen 总开关。关闭后所有优化逻辑立即退化为原版行为（无需重启）。");
        enableLightBatching = cfg.getBoolean("enableLightBatching", CAT_CORE, enableLightBatching,
                "光照校验批处理（Starlight 风格）。把同一 tick 内的 checkLight 去重后统一冲刷。");
    }

    private static void loadThreads(Configuration cfg) {
        chunkBuilderThreads = cfg.getInt("chunkBuilderThreads", CAT_THREADS, chunkBuilderThreads, -1, 32,
                "区块网格构建线程数。\n"
                        + "  -1 = 自适应（默认）：按 CPU 核数与堆内存自动推算，"
                        + "公式为 max(1, min(核心数-2, 内存预算/5))，"
                        + "其中内存预算 = max(1, 堆上限*0.3/10MB)。"
                        + "8 核 8G 大致得到 6 个线程，12 核 8G 大约 10 个。\n"
                        + "   0 = 强制使用原版的 2 个线程。\n"
                        + "  正数 = 强制使用该值（会被 CPU 核数封顶）。\n"
                        + "注意：线程数只会影响“区块网格构建”（把方块转成顶点），"
                        + "不影响区块数据加载——后者在原版里是单线程的。");
        asyncQueueCapacity = cfg.getInt("asyncQueueCapacity", CAT_THREADS, asyncQueueCapacity, 16, 8192,
                "异步任务队列容量上限。队列满时任务被直接丢弃而不是阻塞主线程——"
                        + "阻塞主线程才是真正的死锁来源。");
        asyncTaskBudgetMs = cfg.getInt("asyncTaskBudgetMs", CAT_THREADS, asyncTaskBudgetMs, 1, 100,
                "单个异步任务的软时间预算（毫秒）。超时会被记录并放弃结果。");
        asyncPathfinding = cfg.getBoolean("asyncPathfinding", CAT_THREADS, asyncPathfinding,
                "把生物寻路（A*）移到后台线程。关闭后一切照旧走主线程。");
        pathfindRequestCooldown = cfg.getInt("pathfindRequestCooldown", CAT_THREADS, pathfindRequestCooldown, 0, 200,
                "同一实体两次寻路请求的最小间隔（tick）。用于合并高频重复请求。");
    }

    private static void loadCulling(Configuration cfg) {
        tileEntityCulling = cfg.getBoolean("tileEntityCulling", CAT_CULLING, tileEntityCulling,
                "方块实体（箱子、告示牌、熔炉等 TESR）的视锥 + 遮挡剔除。");
        entityOcclusionCulling = cfg.getBoolean("entityOcclusionCulling", CAT_CULLING, entityOcclusionCulling,
                "实体遮挡剔除：被固体方块挡住的实体不再提交渲染。");
        particleCulling = cfg.getBoolean("particleCulling", CAT_CULLING, particleCulling,
                "粒子视锥剔除。");
        occlusionRayBudget = cfg.getInt("occlusionRayBudget", CAT_CULLING, occlusionRayBudget, 0, 4096,
                "每帧最多发射多少条遮挡检测射线。设为 0 等价于只做视锥剔除。");
        occlusionRayLength = cfg.get(CAT_CULLING, "occlusionRayLength", occlusionRayLength,
                "遮挡检测射线的最大长度（格）。", 4.0D, 256.0D).getDouble();
        occlusionHysteresis = cfg.getInt("occlusionHysteresis", CAT_CULLING, occlusionHysteresis, 1, 60,
                "连续多少帧判定被遮挡后才真正隐藏。用于消除画面边缘的闪烁。");
        asyncOcclusion = cfg.getBoolean("asyncOcclusion", CAT_CULLING, asyncOcclusion,
                "把遮挡射线追踪挪到后台线程（参考 EntityCulling）。\n"
                        + "主线程只负责采集射线沿途的方块快照，判定在 worker 上完成，\n"
                        + "因此射线数量不再受主线程预算限制。结果有 1~2 帧延迟。");
        cullLeavesOcclusion = cfg.getBoolean("cullLeavesOcclusion", CAT_CULLING, cullLeavesOcclusion,
                "把树叶也算作遮挡物（参考 MoreCulling）。\n"
                        + "森林里收益明显，但被厚树叶挡住的实体会提前消失。");
        beaconBeamCulling = cfg.getBoolean("beaconBeamCulling", CAT_CULLING, beaconBeamCulling,
                "对信标光柱做视锥剔除（参考 MoreCulling）。原版几乎从不剔除光柱。");
        mapSignCulling = cfg.getBoolean("mapSignCulling", CAT_CULLING, mapSignCulling,
                "六面都被实心方块完全包围的地图/告示牌整块跳过（参考 MoreCulling）。");
        particleLod = cfg.getBoolean("particleLod", CAT_CULLING, particleLod,
                "粒子距离分档 LoD（参考 FPSFlow 的 three-zone density LOD）。\n"
                        + "近处全绘、中间档隔一个、最远档不画。");
        particleLodMid = cfg.get(CAT_CULLING, "particleLodMid", particleLodMid,
                "中间档距离（格）：超过后粒子隔一个画一个。0 = 关闭该档。", 0.0D, 256.0D).getDouble();
        particleLodFar = cfg.get(CAT_CULLING, "particleLodFar", particleLodFar,
                "最远档距离（格）：超过后完全不画。0 = 关闭该档。", 0.0D, 512.0D).getDouble();
        particleLimit = cfg.getInt("particleLimit", CAT_CULLING, particleLimit, 0, 100000,
                "每帧最多绘制多少个粒子。0 = 不限制。\n"
                        + "这是唯一能把密集粒子场景的最坏情况兜住的手段。");
        skinLod = cfg.getBoolean("skinLod", CAT_CULLING, skinLod,
                "玩家皮肤 LoD：远处玩家跳过披风/帽子等装饰层。\n"
                        + "盔甲层永远保留——那是 PvP 里的关键信息。玩家自己永远完整渲染。");
        skinLodLayerDistance = cfg.get(CAT_CULLING, "skinLodLayerDistance", skinLodLayerDistance,
                "超过这个距离（格）跳过装饰性渲染层。", 4.0D, 256.0D).getDouble();
        skinLodTexture = cfg.getBoolean("skinLodTexture", CAT_CULLING, skinLodTexture,
                "对远处玩家使用降采样（32×32）皮肤纹理。默认关闭。\n"
                        + "收益很小（一张皮肤只占 16KB），而切换纹理会造成 GL 绑定变更。");
        skinLodTextureDistance = cfg.get(CAT_CULLING, "skinLodTextureDistance", skinLodTextureDistance,
                "超过这个距离（格）使用降采样皮肤。", 4.0D, 512.0D).getDouble();
        visibleFacingCache = cfg.getBoolean("visibleFacingCache", CAT_CULLING, visibleFacingCache,
                "缓存 getVisibleFacings 的结果到 tick 边界（参考 Ixeris 对转视角掉帧的分析）。\n"
                        + "该结果只依赖方块数据、与相机朝向无关，而原版在朝向变化时会为每个\n"
                        + "可见区块重算一遍。视距 16 时是数百次重复计算。");
    }

    private static void loadRender(Configuration cfg) {
        hudTextureCache = cfg.getBoolean("hudTextureCache", CAT_RENDER, hudTextureCache,
                "把 HUD/角标文本烘焙进纹理，避免每帧重新排版并重复提交字形四边形。");
        hudCacheEntries = cfg.getInt("hudCacheEntries", CAT_RENDER, hudCacheEntries, 16, 2048,
                "HUD 纹理缓存条目上限。");
        blockEntityOptimization = cfg.getBoolean("blockEntityOptimization", CAT_RENDER, blockEntityOptimization,
                "缓存方块实体的渲染包围盒（参考 Optimized Block Entities）。\n"
                        + "原版每帧每个方块实体要新建 3 个 AxisAlignedBB，大型仓库里会显著推高 GC 频率。\n"
                        + "只缓存「恰好等于位置处单位立方体」的包围盒，随状态变化的（信标光柱等）不缓存。");
        removeRealmsButton = cfg.getBoolean("removeRealmsButton", CAT_RENDER, removeRealmsButton,
                "从主菜单移除 Realms 按钮，并把 Mods 按钮补成整宽。");
    }

    private static void loadWorld(Configuration cfg) {
        fluidFlowCache = cfg.getBoolean("fluidFlowCache", CAT_WORLD, fluidFlowCache,
                "缓存流体流向向量。水/岩浆每 tick 会被大量实体反复查询，缓存能在温水区显著降低 CPU 占用。");
        starlightLightEngine = cfg.getBoolean("starlightLightEngine", CAT_WORLD, starlightLightEngine,
                "启用 Starlight 风格的光照引擎重写。\n"
                        + "用「增光 BFS + 减光 BFS」的增量模型替代原版「单点触发、整片重算」，\n"
                        + "每个方块在一次更新中最多被访问常数次。爆炸、多方块结构场景收益最大。\n"
                        + "这是本项目侵入性最强的优化：检测到 OptiFine 等整体接管光照的 mod 时\n"
                        + "会自动关闭（见日志）。");
        enableSectionCollapse = cfg.getBoolean("enableSectionCollapse", CAT_WORLD, enableSectionCollapse,
                "同一 16³ 区段内改动过多时折叠成一次整段重算（仅在未启用光照引擎时生效）。");
    }

    private static void loadDebug(Configuration cfg) {
        debugOverlay = cfg.getBoolean("debugOverlay", CAT_DEBUG, debugOverlay,
                "在 F3 界面追加 Lumen 统计行。");
        verboseDebug = cfg.getBoolean("verboseDebug", CAT_DEBUG, verboseDebug,
                "打印更详细的统计（逐项计数）。");
    }
}
