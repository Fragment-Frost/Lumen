package top.fpvp.lumen;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import top.fpvp.lumen.lighting.LightEngine;
import top.fpvp.lumen.lighting.LightEngineCompat;
import top.fpvp.lumen.thread.AsyncTaskDispatcher;
import top.fpvp.lumen.thread.ChunkThreadTuning;

/**
 * Lumen —— 面向 Minecraft 1.8.9 的客户端性能优化集合。
 *
 * <p>涵盖五个方向：
 * <ol>
 *   <li><b>光照</b>：把同一 tick 内的 {@code checkLight} 去重后统一冲刷；</li>
 *   <li><b>线程</b>：区块网格构建线程数可调、寻路 A* 与方块更新异步化；</li>
 *   <li><b>剔除</b>：方块实体与粒子的视锥剔除、实体的遮挡剔除；</li>
 *   <li><b>内存</b>：{@code long} 键集合替代 {@code HashSet<Long>}、对象池、流体流向缓存；</li>
 *   <li><b>渲染</b>：HUD 文本烘焙成纹理，减少每帧字形四边形提交。</li>
 * </ol>
 *
 * <p>所有优化默认开启、且都可单独关闭（见 {@link LumenConfig}）。
 * 关掉总开关后行为与未安装本 mod 完全一致。
 */
@Mod(
        modid = Lumen.MODID,
        name = Lumen.NAME,
        version = Lumen.VERSION,
        acceptableRemoteVersions = "*",
        // 客户端专用：注册 Forge 内置配置界面。
        //
        // 注意 Forge 1.8.9 没有 ModMenu（那是 Fabric 的 mod），
        // 等价物就是这个 IModGuiFactory —— 在 Mods 列表里点 Config 进入。
        // 用字符串而不是 class 字面量，是因为这个类引用客户端专属的 GUI 类，
        // 在专用服务器上加载会 NoClassDefFoundError。
        guiFactory = "top.fpvp.lumen.client.LumenGuiFactory"
)
public final class Lumen {

    public static final String MODID = "lumen";
    public static final String NAME = "Lumen";
    public static final String VERSION = "1.0.0";

    private static Configuration config;

    /** 供配置界面读取配置对象。 */
    public static Configuration getConfig() {
        return config;
    }

    /** 供配置界面在关闭时立即写回文件。 */
    public static void saveConfig() {
        if (config != null && config.hasChanged()) {
            config.save();
        }
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        config = new Configuration(event.getSuggestedConfigurationFile());
        LumenConfig.load(config);
        // 首次启动时把默认值写进文件，否则玩家看到的是一个空 config，
        // 得先猜有哪些项才能改。
        if (config.hasChanged()) {
            config.save();
        }
    }

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void init(FMLInitializationEvent event) {
        if (!LumenConfig.enabled) {
            // 总开关关闭：不做任何初始化。
            //
            // 原本这里会打一行日志告知玩家。日志已全部移除，
            // 所以这个状态只在配置界面里可见 —— 玩家自己关的开关，
            // 回到配置界面就能看到，不需要额外提示。
            return;
        }

        // 客户端专用的异步执行器在这里显式初始化，避免在类加载期做任何
        // 与线程/GL 相关的动作 —— 1.8.9 的类加载顺序并不保证渲染上下文已就绪。
        AsyncTaskDispatcher.initialize();
    }

    /**
     * 把线程配置翻译成人能看懂的一行。
     *
     * <p>原本只用于启动日志。日志移除后改由配置界面调用 ——
     * 配置项里写的是 {@code -1}，玩家需要知道那实际会开出几个线程，
     * 否则"自适应"等于一个不可验证的黑箱。
     */
    public static String describeBuilderThreads() {
        int configured = LumenConfig.chunkBuilderThreads;
        if (configured == 0) {
            return "原版(" + ChunkThreadTuning.VANILLA_WORKERS + ")，已强制";
        }
        if (configured > 0) {
            return configured + "（手动指定，核数上限 " + ChunkThreadTuning.cores() + "）";
        }
        // 自适应：直接算出公式结果，方便玩家确认是否按预期生效。
        long maxMemory = Runtime.getRuntime().maxMemory();
        return "自适应 -> " + ChunkThreadTuning.workerCount() + " 个 worker / "
                + ChunkThreadTuning.builderCount() + " 个构建器"
                + "（核心数 " + ChunkThreadTuning.cores()
                + "，堆 " + (maxMemory / (1024 * 1024)) + "MB"
                + "，内存预算 " + ChunkThreadTuning.memoryBudget() + "）";
    }
}
