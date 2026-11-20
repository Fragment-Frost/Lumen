package top.fpvp.lumen;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import top.fpvp.lumen.thread.AsyncTaskDispatcher;

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
        acceptableRemoteVersions = "*"
)
public final class Lumen {

    public static final String MODID = "lumen";
    public static final String NAME = "Lumen";
    public static final String VERSION = "1.0.0";

    private static Configuration config;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        config = new Configuration(event.getSuggestedConfigurationFile());
        LumenConfig.load(config);
    }

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void init(FMLInitializationEvent event) {
        if (!LumenConfig.enabled) {
            LumenProfiler.log().info("[Lumen] 总开关已关闭，所有优化退化为原版行为。");
            return;
        }

        LumenProfiler.log().info("[Lumen] 光照批处理: {}", LumenConfig.enableLightBatching);
        LumenProfiler.log().info("[Lumen] 区块构建线程: {}",
                LumenConfig.chunkBuilderThreads == 0 ? "原版(2)" : LumenConfig.chunkBuilderThreads);
        LumenProfiler.log().info("[Lumen] 异步寻路: {}", LumenConfig.asyncPathfinding);
        LumenProfiler.log().info("[Lumen] 剔除: TESR={} Entity={} Particle={}",
                LumenConfig.tileEntityCulling, LumenConfig.entityOcclusionCulling, LumenConfig.particleCulling);

        // 客户端专用的异步执行器在这里显式初始化，避免在类加载期做任何
        // 与线程/GL 相关的动作 —— 1.8.9 的类加载顺序并不保证渲染上下文已就绪。
        AsyncTaskDispatcher.initialize();
    }
}
