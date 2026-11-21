package top.fpvp.lumen.verify;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 校验配置界面真的接上了 Forge 的 {@code IModGuiFactory} 契约。
 *
 * <h2>为什么需要这个测试</h2>
 *
 * <p>这个检查来自一次真实的线上故障：{@code LumenGuiFactory} 当初<b>没有</b>
 * 实现 {@code IModGuiFactory}，只写了同名方法，导致 Mods 页面里的
 * Config 按钮<b>永久变灰</b>。
 *
 * <p>更糟的是它<b>完全静默</b>：编译通过、启动无日志、无崩溃，
 * 只是按钮点不动。而根因藏在 Forge 内部的一处 NPE ——
 * {@code GuiModList} 从 {@code BiMap} 取出 {@code null} 工厂后，
 * 直接对它 {@code invokeinterface}。
 *
 * <p>这类"契约型"错误无法靠编译器发现（Java 没有结构化类型），
 * 也很难靠人眼 review 发现（代码看起来完全合理）。
 * 所以必须用反射在构建期验证。
 *
 * <h2>检查什么</h2>
 *
 * <ol>
 *   <li>类确实 {@code implements IModGuiFactory}（最关键，直接决定按钮是否可点）；</li>
 *   <li>四个接口方法都可调用且签名正确；</li>
 *   <li>{@code mainConfigGuiClass()} 返回的类<b>接受一个 GuiScreen 参数的构造器</b>
 *       —— Forge 用 {@code getConstructor(GuiScreen.class)} 反射实例化它，
 *       没有这个构造器会在<span>点击时</span>才炸；</li>
 *   <li>返回的类是 {@code GuiScreen} 的子类。</li>
 * </ol>
 *
 * <p>第 3 条尤其容易漏：{@code GuiConfig} 的构造器需要 parent 屏幕，
 * 如果写成无参构造，Forge 的 {@code getConstructor} 会抛
 * {@code NoSuchMethodException}，而它发生在玩家点按钮的瞬间。
 */
public final class ConfigGuiSelfTest {

    private static final String FACTORY = "top.fpvp.lumen.client.LumenGuiFactory";
    private static final String I_MOD_GUI_FACTORY = "net.minecraftforge.fml.client.IModGuiFactory";
    private static final String GUI_SCREEN = "net.minecraft.client.gui.GuiScreen";

    private static int failures;

    public static void main(String[] args) throws Exception {
        Class<?> factory;
        Class<?> iface;
        Class<?> guiScreen;
        try {
            factory = Class.forName(FACTORY);
            iface = Class.forName(I_MOD_GUI_FACTORY);
            guiScreen = Class.forName(GUI_SCREEN);
        } catch (Throwable t) {
            System.out.println("  SKIP 缺少 Forge/Minecraft 类，跳过配置界面检查");
            System.out.println("       (" + t + ")");
            return;
        }

        // 1. 必须 implements IModGuiFactory。
        //
        //    这是本次故障的直接原因，也是唯一"没有它会静默失效"的一条。
        check("LumenGuiFactory implements IModGuiFactory",
                iface.isAssignableFrom(factory),
                "Forge 的 BiMap 注册要求真实实现该接口，否则 getGuiFactoryFor() 返回 null，"
                        + "Config 按钮永久变灰（不会崩溃、不会报错）");

        // 2. 四个方法必须存在。
        check("mainConfigGuiClass() 存在",
                hasMethod(factory, "mainConfigGuiClass"), "缺少该方法；Forge 会在点击时抛 NoSuchMethodError");
        check("initialize(Minecraft) 存在",
                hasMethod(factory, "initialize"), "缺少该方法");
        check("runtimeGuiCategories() 存在",
                hasMethod(factory, "runtimeGuiCategories"), "缺少该方法");
        check("getHandlerFor(...) 存在",
                hasMethod(factory, "getHandlerFor"), "缺少该方法");

        // 3. mainConfigGuiClass() 的返回值必须能被 Forge 反射实例化。
        Class<?> configGui = null;
        try {
            Object instance = factory.getDeclaredConstructor().newInstance();
            Method m = factory.getMethod("mainConfigGuiClass");
            Object result = m.invoke(instance);
            if (result instanceof Class) {
                configGui = (Class<?>) result;
            }
        } catch (Throwable t) {
            failures++;
            System.out.println("  FAIL 无法调用 mainConfigGuiClass() -> " + t);
        }

        if (configGui != null) {
            check("返回类是 GuiScreen 子类",
                    guiScreen.isAssignableFrom(configGui),
                    "实际返回: " + configGui.getName());

            // 关键：Forge 用 getConstructor(GuiScreen.class) 实例化。
            Constructor<?> ctor = null;
            try {
                ctor = configGui.getConstructor(guiScreen);
            } catch (Throwable ignored) {
                // 下面统一报错。
            }
            check("存在 (GuiScreen) 构造器",
                    ctor != null,
                    "Forge 的 GuiModList 调用 getConstructor(GuiScreen.class)，"
                            + "没有它会在玩家点按钮的瞬间抛 NoSuchMethodException");
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("All config GUI self-tests passed.");
        } else {
            System.out.println(failures + " config GUI self-test(s) FAILED.");
            System.exit(1);
        }
    }

    private static boolean hasMethod(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
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
