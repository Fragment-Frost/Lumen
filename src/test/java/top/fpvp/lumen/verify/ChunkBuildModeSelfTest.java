package top.fpvp.lumen.verify;

import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.thread.ChunkThreadTuning;

/**
 * 区块构建三档模式的自测。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>三档模式的配置项彼此牵连，而且其中一处有真实的内存/线程陷阱：
 *
 * <ul>
 *   <li>{@code builders >= workers} 是<b>硬性不变量</b>。
 *       {@code allocateRenderBuilder()} 是无条件 {@code BlockingQueue#take()}，
 *       池空就永久阻塞 —— 不崩溃、不报错，只是区块再也不加载。
 *       三档模式会改变 worker 数（全堵塞要更多 worker），所以每档都要验；</li>
 *   <li>模式字符串填错时必须<b>安全退回</b>默认值，而不是崩或变成"线程化"
 *       （后者会让玩家看到白色空缺却不知道原因）；</li>
 *   <li>线程化的等待预算必须是 0，全堵塞必须显著大于半堵塞 ——
 *       如果两者相等，说明配置读错了，玩家会以为模式没生效。</li>
 * </ul>
 */
public final class ChunkBuildModeSelfTest {

    private static int failures;

    public static void main(String[] args) {
        testModeParsing();
        testWaitBudgets();
        testBuildersInvariant();
        testUnknownModeFallsBack();

        System.out.println();
        if (failures == 0) {
            System.out.println("All chunk build mode self-tests passed.");
        } else {
            System.out.println(failures + " chunk build mode self-test(s) FAILED.");
            System.exit(1);
        }
    }

    /** 三档模式都能被正确解析，且大小写/别名兼容。 */
    private static void testModeParsing() {
        String saved = LumenConfig.chunkBuildMode;
        boolean savedEnabled = LumenConfig.enabled;
        try {
            LumenConfig.enabled = true;

            LumenConfig.chunkBuildMode = "threaded";
            check("解析 threaded", ChunkThreadTuning.mode() == ChunkThreadTuning.Mode.THREADED, "");
            LumenConfig.chunkBuildMode = "THREADED";
            check("解析 THREADED（大小写无关）",
                    ChunkThreadTuning.mode() == ChunkThreadTuning.Mode.THREADED, "");
            LumenConfig.chunkBuildMode = "semi_blocking";
            check("解析 semi_blocking",
                    ChunkThreadTuning.mode() == ChunkThreadTuning.Mode.SEMI_BLOCKING, "");
            LumenConfig.chunkBuildMode = "full_blocking";
            check("解析 full_blocking",
                    ChunkThreadTuning.mode() == ChunkThreadTuning.Mode.FULL_BLOCKING, "");
            // 中文别名：配置文件里写中文在 Forge 里是允许的。
            LumenConfig.chunkBuildMode = "全堵塞";
            check("解析中文别名 全堵塞",
                    ChunkThreadTuning.mode() == ChunkThreadTuning.Mode.FULL_BLOCKING, "");
        } finally {
            LumenConfig.chunkBuildMode = saved;
            LumenConfig.enabled = savedEnabled;
        }
    }

    /** 等待预算：线程化=0，半堵塞小，全堵塞大。 */
    private static void testWaitBudgets() {
        String savedMode = LumenConfig.chunkBuildMode;
        int savedSemi = LumenConfig.chunkBuildWaitMsSemi;
        int savedFull = LumenConfig.chunkBuildWaitMsFull;
        boolean savedEnabled = LumenConfig.enabled;
        try {
            LumenConfig.enabled = true;
            LumenConfig.chunkBuildWaitMsSemi = 2;
            LumenConfig.chunkBuildWaitMsFull = 50;

            LumenConfig.chunkBuildMode = "threaded";
            check("线程化等待预算为 0", ChunkThreadTuning.waitBudgetMs() == 0L,
                    "非 0 就意味着主线程仍在等，与「线程化」语义矛盾");

            LumenConfig.chunkBuildMode = "semi_blocking";
            long semi = ChunkThreadTuning.waitBudgetMs();
            check("半堵塞等待预算 = 配置值", semi == 2L, "实际 " + semi);
            check("半堵塞预算远小于一帧（16.6ms）", semi < 16L,
                    "否则半堵塞就变成了全堵塞");

            LumenConfig.chunkBuildMode = "full_blocking";
            long full = ChunkThreadTuning.waitBudgetMs();
            check("全堵塞等待预算 = 配置值", full == 50L, "实际 " + full);
            check("全堵塞预算显著大于半堵塞", full > semi * 5,
                    "两者接近说明配置没生效，玩家会以为模式切换无效");
        } finally {
            LumenConfig.chunkBuildMode = savedMode;
            LumenConfig.chunkBuildWaitMsSemi = savedSemi;
            LumenConfig.chunkBuildWaitMsFull = savedFull;
            LumenConfig.enabled = savedEnabled;
        }
    }

    /**
     * 最关键的检查：每一档模式下都必须满足 {@code builders >= workers}。
     *
     * <p>违反它 = 有 worker 永久卡在 {@code take()} 上，区块再也不加载，
     * 且没有任何报错。
     */
    private static void testBuildersInvariant() {
        String savedMode = LumenConfig.chunkBuildMode;
        int savedThreads = LumenConfig.chunkBuilderThreads;
        boolean savedEnabled = LumenConfig.enabled;
        try {
            LumenConfig.enabled = true;
            for (String mode : new String[]{"threaded", "semi_blocking", "full_blocking"}) {
                LumenConfig.chunkBuildMode = mode;
                // 覆盖自适应与几个强制值，含极端小值。
                for (int threads : new int[]{-1, 0, 1, 2, 4, 8, 16, 32}) {
                    LumenConfig.chunkBuilderThreads = threads;
                    int workers = ChunkThreadTuning.workerCount();
                    int builders = ChunkThreadTuning.builderCount();
                    String tag = "[" + mode + " threads=" + threads + "]";
                    check("builders >= workers " + tag
                                    + " (" + builders + " >= " + workers + ")",
                            builders >= workers,
                            "违反会导致 worker 永久阻塞在 allocateRenderBuilder()，"
                                    + "区块不再加载且无任何报错");
                    check("workers >= 1 " + tag, workers >= 1, "");
                    check("builders >= 1 " + tag, builders >= 1, "");
                }
            }
        } finally {
            LumenConfig.chunkBuildMode = savedMode;
            LumenConfig.chunkBuilderThreads = savedThreads;
            LumenConfig.enabled = savedEnabled;
        }
    }

    /** 未知模式必须安全退回默认（而非崩或误判成线程化）。 */
    private static void testUnknownModeFallsBack() {
        String saved = LumenConfig.chunkBuildMode;
        boolean savedEnabled = LumenConfig.enabled;
        try {
            LumenConfig.enabled = true;
            LumenConfig.chunkBuildMode = "这是拼错的值";
            check("未知模式退回 semi_blocking（不是 threaded）",
                    ChunkThreadTuning.mode() == ChunkThreadTuning.Mode.SEMI_BLOCKING,
                    "退回 threaded 会让玩家看到白色空缺却找不到原因");

            LumenConfig.chunkBuildMode = "";
            check("空字符串退回 semi_blocking",
                    ChunkThreadTuning.mode() == ChunkThreadTuning.Mode.SEMI_BLOCKING, "");

            LumenConfig.chunkBuildMode = null;
            check("null 退回 semi_blocking",
                    ChunkThreadTuning.mode() == ChunkThreadTuning.Mode.SEMI_BLOCKING, "");
        } finally {
            LumenConfig.chunkBuildMode = saved;
            LumenConfig.enabled = savedEnabled;
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("  OK   " + name);
        } else {
            failures++;
            System.out.println("  FAIL " + name + (detail.isEmpty() ? "" : " -> " + detail));
        }
    }
}
