package top.fpvp.lumen.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;
import top.fpvp.lumen.Lumen;
import top.fpvp.lumen.LumenConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Forge 配置界面入口。
 *
 * <h2>Forge 1.8.9 没有 ModMenu</h2>
 *
 * <p>ModMenu 是 Fabric 的 mod。Forge 的等价物就是 {@link IModGuiFactory}：
 * 在 {@code @Mod} 注解里声明 {@code guiFactory = "..."}，
 * 然后在主菜单 → Mods 列表里点 Config 进入。
 *
 * <h2>为什么必须 implements IModGuiFactory（一次惨痛教训）</h2>
 *
 * <p>第一版实现<b>没有</b>实现这个接口，而是用同名方法 + 反射，
 * 理由是"开发环境编译时看到的是混淆签名，实现不了"。结果是：
 * Mods 页面里的 Config 按钮<b>永久变灰不可点</b>。
 *
 * <p>原因在 Forge 的调用链（反编译 {@code GuiModList} 与
 * {@code FMLClientHandler} 确认）：
 * <ol>
 *   <li>{@code FMLClientHandler#getGuiFactoryFor(mod)} 从一个
 *       {@code BiMap<ModContainer, IModGuiFactory>} 里取工厂；</li>
 *   <li>这个 Map 的填充<b>要求类真的实现 {@code IModGuiFactory}</b>，
 *       否则注册阶段就被跳过，取出来是 {@code null}；</li>
 *   <li>紧接着 {@code GuiModList} 对返回值直接
 *       {@code invokeinterface IModGuiFactory.mainConfigGuiClass()} ——
 *       在 {@code null} 上调用会抛 NPE；</li>
 *   <li>NPE 被上层吞掉，按钮的 {@code enabled} 标记保持 false。</li>
 * </ol>
 *
 * <p>也就是说：<b>"用同名方法骗过鸭子类型"在 Java 的接口机制下不成立</b>。
 * Forge 判的是 {@code isAssignableFrom}，不是方法名。
 *
 * <p>而"编译时看到混淆签名"本身是个误会：那是被错误的 classpath 误导了。
 * 正确的编译依赖是 essential-loom 缓存里的
 * {@code forge-mapped.jar}（MCP 名），它的签名是
 * {@code initialize(Minecraft)} / {@code Class<? extends GuiScreen>}，
 * 完全可以正常实现。踩坑记录见 README「配置界面」一节。
 *
 * <h2>为什么配置项要分组</h2>
 *
 * <p>{@link GuiConfig} 会按 category 分页显示。本 mod 的开关有三十多个，
 * 平铺在一页里根本找不到想改的那项。分组沿用 {@link LumenConfig} 里
 * 公开的分类常量，保证配置文件与界面结构一致 ——
 * 否则玩家在界面里改了某项，回头翻 config 文件时对不上位置。
 */
public final class LumenGuiFactory implements IModGuiFactory {

    @Override
    public void initialize(Minecraft minecraftInstance) {
        // 无需初始化：配置在 preInit 已经读过。
    }

    @Override
    public Class<? extends GuiScreen> mainConfigGuiClass() {
        return LumenConfigGui.class;
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        // 本 mod 没有「运行时选项」（那类 mod 需要提供 RuntimeOptionGuiHandler）。
        return null;
    }

    @Override
    public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) {
        return null;
    }

    /**
     * 实际的配置界面。
     *
     * <p>每次打开时重新读取配置：玩家可能手改过 config 文件，
     * 或者上一次修改后没重启。重读保证界面显示的就是当前生效值。
     */
    public static final class LumenConfigGui extends GuiConfig {

        private static final String MOD_ID = "lumen";

        public LumenConfigGui(GuiScreen parent) {
            super(parent, buildElements(), MOD_ID, false, false, "Lumen");
        }

        private static List<IConfigElement> buildElements() {
            List<IConfigElement> list = new ArrayList<IConfigElement>();
            Configuration cfg = Lumen.getConfig();
            if (cfg == null) {
                // 配置尚未加载：返回空列表，界面显示为空但不会崩。
                return list;
            }
            // ConfigElement 包装一个 category，GuiConfig 会把它渲染成一页。
            list.add(new ConfigElement(cfg.getCategory(LumenConfig.CAT_CORE)));
            list.add(new ConfigElement(cfg.getCategory(LumenConfig.CAT_THREADS)));
            list.add(new ConfigElement(cfg.getCategory(LumenConfig.CAT_CULLING)));
            list.add(new ConfigElement(cfg.getCategory(LumenConfig.CAT_RENDER)));
            list.add(new ConfigElement(cfg.getCategory(LumenConfig.CAT_WORLD)));
            list.add(new ConfigElement(cfg.getCategory(LumenConfig.CAT_DEBUG)));
            return list;
        }

        @Override
        public void onGuiClosed() {
            // 关闭界面时写回文件，避免"改了但没生效"。
            //
            // 不重启游戏、也不重建各优化器的内部状态 ——
            // 所有优化器都在每个调用点重新读 LumenConfig 的静态字段，
            // 所以赋值立即生效。这是刻意的设计：如果优化器缓存了配置，
            // 就会出现"改了开关但必须重启"的体验。
            super.onGuiClosed();
            Lumen.saveConfig();
        }
    }
}
