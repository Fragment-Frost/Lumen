package top.fpvp.lumen.verify;

import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.thread.ChunkThreadTuning;

/**
 * 验证 {@link ChunkThreadTuning} 的真实行为（不是公式的副本）。
 *
 * <p>重点验证那个唯一会导致静默死锁的不变量：
 * <b>builderCount() 必须 >= workerCount()</b>。
 * 只要这一条被破坏，多出来的 worker 就会永久阻塞在
 * {@code BlockingQueue#take()} 上，表现为区块不再加载但游戏不崩溃。
 */
public final class ChunkTuningSelfTest {

    private static int failures;

    public static void main(String[] args) {
        boolean savedEnabled = LumenConfig.enabled;
        int savedThreads = LumenConfig.chunkBuilderThreads;

        try {
            // 1. 自适应模式下的实际输出
            LumenConfig.enabled = true;
            LumenConfig.chunkBuilderThreads = -1;
            System.out.println("=== 自适应（本机实测） ===");
            System.out.printf("  cores=%d  heap=%dMB  budget=%d  ->  %d workers / %d builders%n",
                    ChunkThreadTuning.cores(),
                    Runtime.getRuntime().maxMemory() / (1024 * 1024),
                    ChunkThreadTuning.memoryBudget(),
                    ChunkThreadTuning.workerCount(),
                    ChunkThreadTuning.builderCount());
            check("自适应: builders >= workers",
                    ChunkThreadTuning.builderCount() >= ChunkThreadTuning.workerCount(),
                    ChunkThreadTuning.builderCount() + " builders vs "
                            + ChunkThreadTuning.workerCount() + " workers");
            check("自适应: workers >= 1", ChunkThreadTuning.workerCount() >= 1,
                    "workers=" + ChunkThreadTuning.workerCount());

            // 2. 强制原版
            LumenConfig.chunkBuilderThreads = 0;
            check("强制原版: workers == 2",
                    ChunkThreadTuning.workerCount() == ChunkThreadTuning.VANILLA_WORKERS,
                    "workers=" + ChunkThreadTuning.workerCount());
            check("强制原版: builders == 5",
                    ChunkThreadTuning.builderCount() == ChunkThreadTuning.VANILLA_BUILDERS,
                    "builders=" + ChunkThreadTuning.builderCount());

            // 3. 手动指定：必须被核数封顶
            LumenConfig.chunkBuilderThreads = 999;
            int capped = ChunkThreadTuning.workerCount();
            check("手动超核数被封顶",
                    capped <= ChunkThreadTuning.cores(),
                    "workers=" + capped + " cores=" + ChunkThreadTuning.cores());
            check("手动超核数: builders >= workers",
                    ChunkThreadTuning.builderCount() >= capped,
                    ChunkThreadTuning.builderCount() + " builders vs " + capped + " workers");

            // 4. 遍历所有合法配置，确认不变量恒成立
            int bad = 0;
            StringBuilder detail = new StringBuilder();
            for (int cfg = -1; cfg <= 32; cfg++) {
                LumenConfig.chunkBuilderThreads = cfg;
                int w = ChunkThreadTuning.workerCount();
                int b = ChunkThreadTuning.builderCount();
                if (b < w) {
                    bad++;
                    detail.append(String.format("cfg=%d -> %d/%d; ", cfg, w, b));
                }
            }
            check("所有配置 (-1..32) 满足 builders >= workers", bad == 0,
                    bad + " 个违规: " + detail);

            // 5. 总开关关闭时必须完全退化为原版
            LumenConfig.enabled = false;
            LumenConfig.chunkBuilderThreads = -1;
            check("总开关关闭 -> 原版 worker",
                    ChunkThreadTuning.workerCount() == ChunkThreadTuning.VANILLA_WORKERS,
                    "workers=" + ChunkThreadTuning.workerCount());
            check("总开关关闭 -> 原版 builders",
                    ChunkThreadTuning.builderCount() == ChunkThreadTuning.VANILLA_BUILDERS,
                    "builders=" + ChunkThreadTuning.builderCount());

        } finally {
            LumenConfig.enabled = savedEnabled;
            LumenConfig.chunkBuilderThreads = savedThreads;
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("All chunk tuning self-tests passed.");
        } else {
            System.out.println(failures + " chunk tuning self-test(s) FAILED.");
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("  OK   " + name);
        } else {
            failures++;
            System.out.println("  FAIL " + name + " -> " + detail);
        }
    }
}
