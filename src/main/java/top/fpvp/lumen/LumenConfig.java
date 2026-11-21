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
    // 树叶遮挡的开关在下面的"激进剔除"分组里声明（默认关闭），
    // 不要在这里重复声明 —— Java 会直接编译失败。
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

    // ---- 激进剔除 ----
    /**
     * 激进剔除模式。
     *
     * <p>把方块实体的剔除条件从「六面全包围」放宽到「朝眼方向被一个整方块挡住」。
     *
     * <p><b>它确实会在某些角度下让物体提前消失</b> —— 这是设计取舍而非 bug。
     * 因此默认关闭。玩家自己与骑乘实体永远不参与。
     */
    public static boolean aggressiveCulling = false;
    /**
     * 树叶是否算作遮挡物。
     *
     * <p>能显著提升森林场景帧率，代价是被厚树叶挡住的实体提前消失。
     * 默认关闭 —— 这个副作用在多人游戏里容易被误认为"实体隐身"。
     */
    public static boolean cullLeavesOcclusion = false;

    /**
     * 激进剔除的<b>完整射线追踪</b>模式。
     *
     * <p>开启后，只要从物体中心到眼睛的视线上有<b>任何</b>不透明整方块，
     * 该物体就被剔除（方块实体与实体都适用）。这比默认的
     * "六面全包围 / 一格判定"激进得多。
     *
     * <p>参考 Liubai 的思路，但 1.8.9 缺少它最关键的那个前提 ——
     * Liubai 记录每个对象在屏幕上<b>的真实可见面积</b>，
     * 只有完全被挡且连续确认两次才隐藏。1.8.9 没有这套逐对象可见面积记录。
     *
     * <p>所以这里可能误剔：只露出边缘的箱子，其中心线被挡住就会消失。
     * 默认关闭。玩家自己、其它玩家、骑乘目标、有名字的实体永远不参与。
     */
    public static boolean aggressiveFullRay = false;
    /** 完整射线模式的最大距离（格）。超出不做判定。 */
    public static double aggressiveRayRange = 32.0D;

    // ==== 实体渲染范围 ====
    /** 可调的实体渲染范围总开关。 */
    public static boolean entityRenderRange = true;
    /** 生物渲染距离（格）。0 = 不限制。 */
    public static double entityRangeMob = 48.0D;
    /** 掉落物渲染距离（格）。0 = 不限制。 */
    public static double entityRangeItem = 64.0D;
    /** 玩家渲染距离（格）。0 = 不限制（默认）。PvP 里缩这个会失去对手信息。 */
    public static double entityRangePlayer = 0.0D;
    /** 其它实体（箭、船、矿车）渲染距离（格）。0 = 不限制。 */
    public static double entityRangeOther = 64.0D;

    // ==== 背面剔除 ====
    /**
     * 实体背面剔除。默认开。
     *
     * <p>实体模型是六面全生成的，内部面完全看不到但依然提交给 GPU。
     */
    public static boolean entityBackFaceCulling = true;
    /**
     * 方块背面剔除。默认<b>关</b>。
     *
     * <p>1.8.9 的方块网格在生成阶段就只产生可见面，本来就没有背面 ——
     * 再加一层没有收益。且检测到 OptiFine 时会自动跳过
     * （它的区域渲染可能依赖双面渲染）。详见 {@code BackFaceCulling}。
     */
    public static boolean blockBackFaceCulling = false;

    // ==== 网络 ====
    /**
     * 跳过内容完全相同的每 tick 位置包。
     *
     * <p>默认关：某些带反作弊的服务端依赖连续位置包做移动校验，
     * 跳过可能导致误判。开启时有最大连续跳过数限制。
     */
    public static boolean skipRedundantPackets = false;

    // ==== 像素合并 ====
    /**
     * 远处同色像素合并。
     *
     * <p><b>1.8.9 上无法实现</b>，开关保留但恒不生效。
     * 原因见 {@code PixelMergeAnalysis}：颜色合并需要后处理 FBO
     * （1.8.9 没有可用的离屏管线，而 glReadPixels 本身更贵），
     * 几何合并需要改写区块网格索引（显示列表不可读）。
     */
    public static boolean pixelMerge = false;

    // ==== ASM ====
    /**
     * ASM 字节码改写。
     *
     * <p><b>当前不启用任何变换</b>。调查确认原版"屎山"的开销接近零
     * （Profiler 未启用时是一次布尔检查），详见 {@code AsmOptimizations}。
     */
    public static boolean asmOptimizations = false;

    // ---- 异步剔除预算 ----
    /** 每帧最多提交多少条异步遮挡判定。0 = 用内置默认值（48）。 */
    public static int asyncOcclusionBudget = 48;

    // ---- 远处实体低分辨率化 ----
    /**
     * 远处实体使用低细节模型（LoD）。
     *
     * <p>参考 FPSFlow 的 Entity LOD：远处的生物跳过装饰性渲染层
     * （羊毛、雪、装备、鞍具），只画主体模型。
     */
    public static boolean entityLod = true;
    /** 超过这个距离（格）跳过实体装饰层。 */
    public static double entityLodLayerDistance = 24.0D;
    /** 超过这个距离（格）完全跳过实体渲染。0 = 不启用。 */
    public static double entityLodCullDistance = 64.0D;

    // ---- 贴图低分辨率化 ----
    /**
     * 远处方块/实体使用低分辨率纹理。
     *
     * <p>把远处的纹理降采样后上传，降低采样带宽与显存占用。
     * <b>收益有限且风险实在</b>（切换纹理会造成 GL 绑定变更），
     * 因此默认关闭，想试再开。
     */
    public static boolean textureLod = false;
    /** 超过这个距离（格）使用低分辨率纹理。 */
    public static double textureLodDistance = 48.0D;
    /** 纹理降采样比例（2 = 长宽各减半，总面积 1/4）。 */
    public static int textureLodDivisor = 2;

    // ==== 声音 ====
    /**
     * 提高 Paulscode 的普通声道数。
     *
     * <p>反汇编确认原版 {@code SoundSystemConfig.numberNormalChannels = 28}。
     * 而 {@code Library} 在构造时按这个值分配声道池，
     * 池满后 {@code newSource} <b>静默失败</b> —— 声音直接不播。
     * 这就是"人多/爆炸/下雨时部分声音消失"的根因。
     */
    public static boolean moreSoundChannels = true;
    /** 目标声道数。256 在常见 OpenAL 实现上都远够用。 */
    public static int soundChannels = 256;
    /**
     * 声音提交去重与限流。
     *
     * <p>同一 tick 内同位置同声音只播一次，并限制每 tick 总提交数。
     * 人类听觉分辨不出同时叠加的十几层音效，去掉多余的不会听出差别。
     */
    public static boolean soundThrottle = true;
    /** 每 tick 最多提交多少个声音。0 = 不限制。 */
    public static int soundSubmissionsPerTick = 24;

    // ==== 碰撞 ====
    /**
     * 碰撞检测优化（参考「加速碰撞」）。
     *
     * <p>原版每次 {@code getCollidingBoundingBoxes} 都 {@code new ArrayList}
     * + {@code new MutableBlockPos}。改为按嵌套深度复用的列表池。
     */
    public static boolean collisionOptimization = true;

    // ==== 物品渲染 ====
    /**
     * 缓存物品模型查找（Carbon 思路在 1.8.9 上可行的子集）。
     *
     * <p>真正的 GPU instancing 需要 GL 3.1+，1.8.9 用 LWJGL 2 没有。
     * 能省的是 {@code ItemModelMesher.getItemModel} 的每帧重复查找。
     */
    public static boolean itemRenderCache = true;

    // ==== 区块构建模式 ====
    /**
     * 区块构建等待策略：{@code threaded} / {@code semi_blocking} / {@code full_blocking}。
     *
     * <ul>
     *   <li>{@code threaded}（线程化）：主线程完全不等，帧率最平滑，
     *       但极远/新生成的区块可能短暂显示为白色空缺；</li>
     *   <li>{@code semi_blocking}（半堵塞，默认）：每帧只等 2ms，
     *       折中；</li>
     *   <li>{@code full_blocking}（全堵塞）：等到构建完成，绝无白色空缺，
     *       代价是跑图时明显顿挫。适合录屏/截图。</li>
     * </ul>
     */
    public static String chunkBuildMode = "semi_blocking";
    /** 半堵塞模式下每帧的等待预算（毫秒）。 */
    public static int chunkBuildWaitMsSemi = 2;
    /** 全堵塞模式下每帧的等待预算（毫秒）。 */
    public static int chunkBuildWaitMsFull = 50;
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

        // ---- 声音 ----
        moreSoundChannels = cfg.getBoolean("moreSoundChannels", CAT_CORE, moreSoundChannels,
                "提高 Paulscode 的普通声道数（默认 28 -> 256）。\n"
                        + "反汇编确认 SoundSystemConfig.numberNormalChannels 默认是 28，\n"
                        + "而 Library 在构造时按这个值分配声道池；池满后 newSource 静默失败，\n"
                        + "声音直接不播。这就是「人多/爆炸/下雨时部分声音消失」的根因。\n"
                        + "注意：该值必须在声音系统初始化前生效，改动后需要重启游戏。");
        soundChannels = cfg.getInt("soundChannels", CAT_CORE, soundChannels, 32, 1024,
                "目标普通声道数。过高会占用更多 OpenAL 资源，256 通常足够。");
        soundThrottle = cfg.getBoolean("soundThrottle", CAT_CORE, soundThrottle,
                "同一 tick 内同位置同声音只播一次，并限制每 tick 总提交数。\n"
                        + "避免一瞬间几十个声音请求把声道池打满。");
        soundSubmissionsPerTick = cfg.getInt("soundSubmissionsPerTick", CAT_CORE,
                soundSubmissionsPerTick, 0, 256,
                "每 tick 最多提交多少个声音。0 = 不限制。");

        // ---- 网络 ----
        skipRedundantPackets = cfg.getBoolean("skipRedundantPackets", CAT_CORE, skipRedundantPackets,
                "跳过内容完全相同的每 tick 位置包。默认关闭：\n"
                        + "某些带反作弊的服务端依赖连续位置包做移动校验，跳过可能被误判。\n"
                        + "注意：1.8.9 的网络 IO 与编码本来就已在 Netty 线程上，\n"
                        + "不需要也不应该再自己开线程池（会增加延迟并破坏包顺序）。");

        // ---- 像素合并（不可用）----
        pixelMerge = cfg.getBoolean("pixelMerge", CAT_RENDER, pixelMerge,
                top.fpvp.lumen.render.PixelMergeAnalysis.explain());

        // ---- ASM ----
        asmOptimizations = cfg.getBoolean("asmOptimizations", CAT_DEBUG, asmOptimizations,
                top.fpvp.lumen.asm.AsmOptimizations.explain());
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

        // ---- 区块构建模式（三档）----
        chunkBuildMode = cfg.getString("chunkBuildMode", CAT_THREADS, chunkBuildMode,
                "区块构建等待策略：\n"
                        + "  threaded       线程化，主线程完全不等。帧率最平滑，\n"
                        + "                 但极远/新生成的区块可能短暂显示为白色空缺；\n"
                        + "  semi_blocking  半堵塞，每帧只等一小段（默认，折中）；\n"
                        + "  full_blocking  全堵塞，等到构建完成。绝无白色空缺，\n"
                        + "                 但跑图时明显顿挫，适合录屏/截图。\n"
                        + "注意：这三个模式不改变构建的并行度，那由 chunkBuilderThreads 控制。\n"
                        + "它们改变的是「主线程愿意为等待构建结果付出多少时间」。");
        chunkBuildWaitMsSemi = cfg.getInt("chunkBuildWaitMsSemi", CAT_THREADS, chunkBuildWaitMsSemi,
                0, 16, "半堵塞模式每帧的等待预算（毫秒）。60fps 下 16.6ms 是整帧预算，\n"
                + "所以这个值必须远小于它，否则半堵塞就变成了全堵塞。");
        chunkBuildWaitMsFull = cfg.getInt("chunkBuildWaitMsFull", CAT_THREADS, chunkBuildWaitMsFull,
                1, 200, "全堵塞模式每帧的等待预算（毫秒）。它不追求帧率，而是保证没有白色空缺。");
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
                "把树叶算作遮挡物（参考 MoreCulling）。\n"
                        + "森林里收益明显，但被厚树叶挡住的实体会提前消失。\n"
                        + "默认关闭——这个副作用在多人游戏里容易被误认为「实体隐身」。");
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

        // ---- 激进剔除 ----
        aggressiveCulling = cfg.getBoolean("aggressiveCulling", CAT_CULLING, aggressiveCulling,
                "激进剔除：方块实体朝眼方向被遮挡即跳过（比默认的六面全包围更激进）。\n"
                        + "警告：某些角度下物体可能提前消失，这是设计取舍而非 bug。\n"
                        + "玩家自己与骑乘实体永远不参与。");
        cullLeavesOcclusion = cfg.getBoolean("cullLeavesOcclusion", CAT_CULLING, cullLeavesOcclusion,
                "把树叶算作遮挡物。显著提升森林帧率，\n"
                        + "代价是被厚树叶挡住的实体提前消失（默认关闭）。");
        asyncOcclusionBudget = cfg.getInt("asyncOcclusionBudget", CAT_CULLING, asyncOcclusionBudget,
                0, 512, "每帧最多提交多少条异步遮挡判定。0 = 用内置默认值 48。");

        // ---- 完整射线追踪（最激进）----
        aggressiveFullRay = cfg.getBoolean("aggressiveFullRay", CAT_CULLING, aggressiveFullRay,
                "完整射线追踪：只要视线上有任何不透明整方块挡住，该物体（方块实体与实体）就剔除。\n"
                        + "这是最激进的模式，默认关闭。\n"
                        + "注意：它只采样「中心到眼睛」一条线，所以只露出边缘的物体会被误剔。\n"
                        + "1.8.9 缺少 Liubai 那套「逐对象真实可见面积」的记录，做不到更精确。\n"
                        + "玩家自己、其它玩家、骑乘目标、有自定义名字的实体永远不参与。");
        aggressiveRayRange = cfg.get(CAT_CULLING, "aggressiveRayRange", aggressiveRayRange,
                "完整射线模式的最大判定距离（格）。超出不做判定。", 4.0D, 128.0D).getDouble();

        // ---- 实体渲染范围 ----
        entityRenderRange = cfg.getBoolean("entityRenderRange", CAT_CULLING, entityRenderRange,
                "可调的实体渲染范围。1.8.9 没有「生物渲染距离」这个设置，\n"
                        + "原版的距离控制是按实体类型写死的，导致 120 格外的僵尸\n"
                        + "依然要走完整的模型变换、骨骼动画、纹理绑定。");
        entityRangeMob = cfg.get(CAT_CULLING, "entityRangeMob", entityRangeMob,
                "生物渲染距离（格）。0 = 不限制。", 0.0D, 256.0D).getDouble();
        entityRangeItem = cfg.get(CAT_CULLING, "entityRangeItem", entityRangeItem,
                "掉落物渲染距离（格）。0 = 不限制。缩太狠会导致「东西掉了却看不见」。",
                0.0D, 256.0D).getDouble();
        entityRangePlayer = cfg.get(CAT_CULLING, "entityRangePlayer", entityRangePlayer,
                "玩家渲染距离（格）。默认 0 = 不限制。\n"
                        + "PvP 里缩这个会让你在远距离看不见对手——不建议改。",
                0.0D, 512.0D).getDouble();
        entityRangeOther = cfg.get(CAT_CULLING, "entityRangeOther", entityRangeOther,
                "其它实体（箭、船、矿车等）渲染距离（格）。0 = 不限制。", 0.0D, 256.0D).getDouble();

        // ---- 背面剔除 ----
        entityBackFaceCulling = cfg.getBoolean("entityBackFaceCulling", CAT_RENDER, entityBackFaceCulling,
                "实体背面剔除。实体模型是六面全生成的，内部面完全看不到但依然提交给 GPU。\n"
                        + "与 OptiFine 的区域渲染不冲突（实体渲染是独立阶段）。");
        blockBackFaceCulling = cfg.getBoolean("blockBackFaceCulling", CAT_RENDER, blockBackFaceCulling,
                "方块背面剔除。默认关闭：\n"
                        + "1.8.9 的方块网格在生成阶段就只产生可见面，本来没有背面，\n"
                        + "再加一层没有收益。检测到 OptiFine 时会自动跳过。");
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

        // ---- 远处实体低分辨率化 ----
        entityLod = cfg.getBoolean("entityLod", CAT_RENDER, entityLod,
                "远处实体使用低细节模型：跳过装饰性渲染层（参考 FPSFlow 的 Entity LOD）。\n"
                        + "玩家自己、其它玩家、以及玩家骑乘的实体永远保留完整渲染。");
        entityLodLayerDistance = cfg.get(CAT_RENDER, "entityLodLayerDistance", entityLodLayerDistance,
                "超过这个距离（格）跳过实体装饰层。", 4.0D, 256.0D).getDouble();
        entityLodCullDistance = cfg.get(CAT_RENDER, "entityLodCullDistance", entityLodCullDistance,
                "超过这个距离（格）完全跳过实体渲染。0 = 不启用。", 0.0D, 512.0D).getDouble();

        // ---- 贴图低分辨率化 ----
        textureLod = cfg.getBoolean("textureLod", CAT_RENDER, textureLod,
                "远处方块/实体使用低分辨率纹理。默认关闭：\n"
                        + "收益有限，而降采样要读回显存（同步等待）+ 切换 GL 绑定，\n"
                        + "在 1.8.9 的单个大方块图集上可能得不偿失。想试再开。");
        textureLodDistance = cfg.get(CAT_RENDER, "textureLodDistance", textureLodDistance,
                "超过这个距离（格）使用低分辨率纹理。", 8.0D, 512.0D).getDouble();
        textureLodDivisor = cfg.getInt("textureLodDivisor", CAT_RENDER, textureLodDivisor, 2, 8,
                "纹理降采样比例（2 = 长宽各减半，总面积 1/4）。");

        // ---- 物品渲染 ----
        itemRenderCache = cfg.getBoolean("itemRenderCache", CAT_RENDER, itemRenderCache,
                "缓存物品模型查找（参考 Carbon 的思路）。\n"
                        + "真正的 GPU instancing 需要 GL 3.1+，1.8.9 用 LWJGL 2 没有；\n"
                        + "这里做的是省掉 ItemModelMesher 的每帧重复查找。");

        // ---- 区块构建模式 ----
        // 这部分在 loadThreads 里，不在这里重复声明。
        // ---- 碰撞 ----
        collisionOptimization = cfg.getBoolean("collisionOptimization", CAT_WORLD, collisionOptimization,
                "碰撞检测优化（参考「加速碰撞」）。\n"
                        + "原版每次 getCollidingBoundingBoxes 都新建 ArrayList 与 MutableBlockPos，\n"
                        + "改为按嵌套深度复用的列表池。");
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
