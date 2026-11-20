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

    private static final String CAT_CORE = "core";
    private static final String CAT_THREADS = "threads";
    private static final String CAT_CULLING = "culling";
    private static final String CAT_RENDER = "render";
    private static final String CAT_WORLD = "world";
    private static final String CAT_DEBUG = "debug";

    /** 总开关：关掉后所有 mixin 逻辑立即退化为原版行为。 */
    public static boolean enabled = true;

    /** 光照校验批处理（既有功能，保留原字段名以免破坏既有配置）。 */
    public static boolean enableLightBatching = true;

    // ---- 线程 ----
    /** 区块网格构建线程数；0 = 保持原版（2 个 builder 线程）。 */
    public static int chunkBuilderThreads = 0;
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
        chunkBuilderThreads = cfg.getInt("chunkBuilderThreads", CAT_THREADS, chunkBuilderThreads, 0, 16,
                "区块网格构建线程数。0 = 使用原版的 2 个线程。\n"
                        + "经验值：与物理核心数相当（例如 8 核填 6）。填得太高会让线程互相抢 L3 缓存与 GC 线程，"
                        + "反而掉帧——这就是“线程开太多”的典型反效果。");
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
    }

    private static void loadRender(Configuration cfg) {
        hudTextureCache = cfg.getBoolean("hudTextureCache", CAT_RENDER, hudTextureCache,
                "把 HUD/角标文本烘焙进纹理，避免每帧重新排版并重复提交字形四边形。");
        hudCacheEntries = cfg.getInt("hudCacheEntries", CAT_RENDER, hudCacheEntries, 16, 2048,
                "HUD 纹理缓存条目上限。");
    }

    private static void loadWorld(Configuration cfg) {
        fluidFlowCache = cfg.getBoolean("fluidFlowCache", CAT_WORLD, fluidFlowCache,
                "缓存流体流向向量。水/岩浆每 tick 会被大量实体反复查询，缓存能在温水区显著降低 CPU 占用。");
    }

    private static void loadDebug(Configuration cfg) {
        debugOverlay = cfg.getBoolean("debugOverlay", CAT_DEBUG, debugOverlay,
                "在 F3 界面追加 Lumen 统计行。");
        verboseDebug = cfg.getBoolean("verboseDebug", CAT_DEBUG, verboseDebug,
                "打印更详细的统计（逐项计数）。");
    }
}
