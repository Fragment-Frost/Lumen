package top.fpvp.lumen.client;

import net.minecraft.client.gui.GuiScreen;
import top.fpvp.lumen.Lumen;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.LumenProfiler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Forge 配置界面入口。
 *
 * <h2>为什么这里用反射而不是直接继承 GuiConfig</h2>
 *
 * <p>这是一个开发环境特有的坑，值得记下来：Forge 的 universal jar 里，
 * {@code IModGuiFactory} 与 {@code GuiConfig} 的方法签名<b>仍然是混淆名</b>
 * （{@code initialize(ave)}、{@code mainConfigGuiClass()} 返回
 * {@code Class<? extends axu>}），而开发用的 mapped jar 里 Minecraft 类
 * 已经重映射成 MCP 名（{@code GuiScreen}）。
 *
 * <p>两者混用时 {@code Class<? extends GuiScreen>} 与
 * {@code Class<? extends axu>} 对不上，编译直接失败。而在<b>实际游戏</b>里，
 * Forge 与 Minecraft 都在同一个运行时命名空间下，两者是一致的。
 *
 * <p>也就是说：<b>直接写类型在开发环境编不过，但运行时是对的</b>；
 * 而反射在两边都能工作。因此这里全部走反射，代价是十几行样板代码，
 * 换来的是"开发环境能编译、游戏里能跑"。
 *
 * <p>这是本项目里唯一一处刻意的反射用法，别处都避免反射（因为它会
 * 把编译期错误推迟到运行期）。这里没有选择：类型系统在两种命名空间下
 * 无法同时满足。
 *
 * <h2>Forge 1.8.9 没有 ModMenu</h2>
 *
 * <p>ModMenu 是 Fabric 的 mod。Forge 的等价物就是 {@code IModGuiFactory}：
 * 在 {@code @Mod} 注解里声明 {@code guiFactory = "..."}，
 * 然后在 Mods 列表里点 Config 进入。本类就承担这个角色。
 *
 * <p>由于入口本身也要通过反射被 Forge 调用（Forge 会去
 * {@code newInstance()} 我们的类并调用 {@code mainConfigGuiClass()}），
 * 这个类<b>不能实现</b> {@code IModGuiFactory} 接口 —— 一旦实现就必须
 * 写出与混淆签名完全匹配的方法，而那正是编译不过的原因。
 * 用同名方法 + 反射构造即可满足 Forge 的调用约定。
 */
public final class LumenGuiFactory {

    /** Forge 配置界面的全限定名，避免直接引用。 */
    private static final String GUI_CONFIG = "net.minecraftforge.fml.client.config.GuiConfig";

    /** 配置元素构造器。 */
    private static final String CONFIG_ELEMENT = "net.minecraftforge.common.config.ConfigElement";

    private LumenGuiFactory() {
    }

    /**
     * Forge 会调用这个方法。参数类型用 {@link Object} 以匹配任意命名空间。
     */
    public void initialize(Object minecraftInstance) {
        // 无需初始化：配置在 preInit 已读取。
    }

    /**
     * Forge 会调用这个方法拿到配置界面的 Class。
     *
     * <p>返回的是<b>运行时动态生成</b>的一个 GuiConfig 子类实例的 Class ——
     * 见 {@link #buildConfigScreenClass()}。
     */
    public Class<?> mainConfigGuiClass() {
        return buildConfigScreenClass();
    }

    public Set<?> runtimeGuiCategories() {
        return null;
    }

    public Object getHandlerFor(Object element) {
        return null;
    }

    /**
     * 构造配置界面的 Class。
     *
     * <p>做法：用一个匿名 {@code GuiConfig} 子类只覆写构造参数来源，
     * 全部通过反射调用父类构造器。这样既能拿到正确的
     * {@code IConfigElement} 列表，又不需要在编译期引用具体类型。
     *
     * <p>失败时返回 {@code null}：Forge 会退化成"没有配置界面"，
     * 而不是崩溃。对一个纯客户端优化 mod，"没有配置界面"是可接受的降级。
     */
    private static Class<?> buildConfigScreenClass() {
        try {
            Class<?> base = Class.forName(GUI_CONFIG);
            // 确认父类存在且是 GuiScreen 的子类，否则早点放弃。
            if (!Class.forName("net.minecraft.client.gui.GuiScreen").isAssignableFrom(base)) {
                LumenProfiler.log().warn("[Lumen] GuiConfig 不是 GuiScreen 子类，配置界面不可用。");
                return null;
            }
            return RunnableConfigScreenHolder.CLASS;
        } catch (Throwable t) {
            LumenProfiler.log().warn("[Lumen] 无法加载 Forge 配置界面（{}），可在 config/lumen.cfg 里手动修改。",
                    t.toString());
            return null;
        }
    }

    /**
     * 延迟持有配置界面 Class。
     *
     * <p>单独一个静态内部类是为了让"加载失败"只发生一次，
     * 且不会因为异常导致外层类初始化失败。
     */
    private static final class RunnableConfigScreenHolder {
        static final Class<?> CLASS = create();

        static Class<?> create() {
            try {
                return LumenConfigScreen.class;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /**
     * 真正的配置界面。
     *
     * <p>它<b>不继承</b> GuiConfig（编译期拿不到那个类型），而是持有一个
     * 通过反射创建的 GuiConfig 委托对象。Forge 需要的是一个
     * {@code Class<? extends GuiScreen>}，委托对象本身是 GuiScreen 子类，
     * 所以类型上成立。
     *
     * <p>但委托模式在这里行不通：Forge 会用<b>无参构造</b>实例化这个类
     * （再调 {@code initGui}），而 GuiConfig 需要一个 parent 屏幕。
     * 因此最终方案是：这个类<b>实现</b> {@code GuiScreen} 的关键方法，
     * 内部在 {@code initGui} 时才反射创建真正的 GuiConfig 并转发。
     *
     * <p>由于这条路径的复杂度与脆弱性都偏高，本类同时提供一个明确的
     * 回退：如果反射失败，界面只显示一行提示，告诉玩家去哪里手改配置。
     */
    public static final class LumenConfigScreen extends GuiScreen {
        private GuiScreen delegate;

        @Override
        public void initGui() {
            super.initGui();
            delegate = createDelegate(this);
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            if (delegate != null) {
                delegate.drawScreen(mouseX, mouseY, partialTicks);
                return;
            }
            // 回退：给玩家一条明确的手改路径，而不是一个空白界面。
            drawDefaultBackground();
            drawCenteredString(fontRendererObj, "Lumen 配置界面不可用",
                    width / 2, height / 2 - 20, 0xFFFFFF);
            drawCenteredString(fontRendererObj, "请手动编辑 config/lumen.cfg",
                    width / 2, height / 2, 0xAAAAAA);
        }

        @Override
        public void updateScreen() {
            if (delegate != null) {
                delegate.updateScreen();
            }
        }

        /**
         * 转发输入事件。
         *
         * <p>不能覆写 {@code mouseClicked}/{@code keyTyped} 来转发：它们是
         * {@code protected}，而委托对象在<b>另一个包</b>里（Forge 的 config 包），
         * 跨包访问受保护方法在编译期就被拒绝。
         *
         * <p>改用公开的 {@code handleMouseInput}/{@code handleKeyboardInput}：
         * 这两个方法是 {@code public}，且它们内部会去消费 LWJGL 的输入队列
         * 并分派给 {@code mouseClicked} 等受保护方法。
         *
         * <p>副作用：本类自己的 {@code mouseClicked} 不再被调用，
         * 但这正是我们要的 —— 输入全部交给真实的 GuiConfig。
         */
        @Override
        public void handleMouseInput() throws java.io.IOException {
            if (delegate != null) {
                delegate.handleMouseInput();
                return;
            }
            super.handleMouseInput();
        }

        @Override
        public void handleKeyboardInput() throws java.io.IOException {
            if (delegate != null) {
                delegate.handleKeyboardInput();
                return;
            }
            super.handleKeyboardInput();
        }

        @Override
        public void onGuiClosed() {
            if (delegate != null) {
                delegate.onGuiClosed();
            }
            Lumen.saveConfig();
        }

        @Override
        public boolean doesGuiPauseGame() {
            return delegate != null && delegate.doesGuiPauseGame();
        }

        /**
         * 反射创建真正的 GuiConfig。
         *
         * <p>构造器签名（MCP 名）：
         * {@code GuiConfig(GuiScreen parent, List<IConfigElement> elements,
         * String modID, boolean allRequireWorldRestart, boolean allRequireMcRestart,
         * String title)}
         */
        private static GuiScreen createDelegate(GuiScreen parent) {
            try {
                Class<?> guiConfig = Class.forName(GUI_CONFIG);
                Class<?> elementIf = Class.forName(
                        "net.minecraftforge.fml.client.config.IConfigElement");
                List<Object> elements = buildElements();

                for (Constructor<?> ctor : guiConfig.getConstructors()) {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length != 6) {
                        continue;
                    }
                    if (!p[0].isInstance(parent) || !p[1].isInstance(elements)) {
                        continue;
                    }
                    Object instance = ctor.newInstance(parent, elements, Lumen.MODID,
                            Boolean.FALSE, Boolean.FALSE, Lumen.NAME);
                    if (instance instanceof GuiScreen) {
                        return (GuiScreen) instance;
                    }
                }
                LumenProfiler.log().warn("[Lumen] 未找到匹配的 GuiConfig 构造器。");
                return null;
            } catch (Throwable t) {
                LumenProfiler.log().warn("[Lumen] 创建配置界面失败: {}", t.toString());
                return null;
            }
        }

        /** 用 ConfigElement 包装每个分类。 */
        private static List<Object> buildElements() throws Exception {
            List<Object> list = new ArrayList<Object>();
            net.minecraftforge.common.config.Configuration cfg = Lumen.getConfig();
            if (cfg == null) {
                return list;
            }
            Class<?> elementCls = Class.forName(CONFIG_ELEMENT);
            Constructor<?> ctor = elementCls.getConstructor(
                    Class.forName("net.minecraftforge.common.config.ConfigCategory"));
            for (String category : new String[]{
                    LumenConfig.CAT_CORE, LumenConfig.CAT_THREADS, LumenConfig.CAT_CULLING,
                    LumenConfig.CAT_RENDER, LumenConfig.CAT_WORLD, LumenConfig.CAT_DEBUG}) {
                list.add(ctor.newInstance(cfg.getCategory(category)));
            }
            return list;
        }
    }
}
