package top.fpvp.lumen.verify;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 校验 {@code AutoDiscoveryMixinPlugin} 的客户端门控名单是否覆盖了所有 mixin。
 *
 * <h2>为什么需要这个检查</h2>
 *
 * <p>自动发现插件会注册 {@code mixin} 包下的<b>每一个</b>类。纯客户端 mixin
 * （渲染、GUI、声音、皮肤图层）若在专用服务器上被应用，会因目标类不存在
 * 而炸在启动阶段 —— 而且这个错误发生在<b>Mixin 应用期</b>，
 * 堆栈里全是 ASM 帧，几乎无法定位。
 *
 * <p>门控名单是手工维护的，而 mixin 会不断增加，两者迟早不一致。
 * 不一致时<b>没有任何提示</b>（{@code require = 0} 的静默效应）。
 *
 * <h2>事实来源：源码，不是编译产物</h2>
 *
 * <p>第一版用 {@code build/classes/java/main} 下的 {@code .class} 作事实来源，
 * 结果产生了一次<b>假失败</b>：Gradle 目录里是上一次构建的旧产物
 * （缺了新加的 4 个 mixin），测试就报告"门控名单遗漏 4 项" ——
 * 而实际上那 4 个里已有 3 个正确写进了名单，第 4 个本就该双端。
 *
 * <p>当时试过"在所有候选目录里挑 mixin 最多那个"，但那是治标：
 * 只要旧目录碰巧更多（比如删过 mixin），照样会误判。
 *
 * <p><b>源码目录是唯一不会陈旧的事实来源</b> —— 它就是要被检查的东西本身。
 * 现在直接扫描 {@code src/main/java/top/fpvp/lumen/mixin/*.java}。
 *
 * <p>编译产物仍然会被检查，但只作为<b>补充</b>：如果编译目录存在且比源码
 * 少了类，说明构建产物已过期，测试会明确报告这一点（这是有用的信号，
 * 说明你该重新编译了），而不是把它误当成门控缺陷。
 *
 * <h2>判定规则</h2>
 *
 * <p>不能简单要求"每个 mixin 都在名单里"：{@code MixinWorld}、
 * {@code MixinBlockLiquid}、{@code MixinPathNavigate}、{@code MixinWorldCollision}
 * 是<b>双端</b>逻辑（光照、流体、寻路、实体物理在服务端同样生效），
 * 它们必须在服务端也应用。因此维护一份"双端白名单"，检查：
 * <ol>
 *   <li>双端 mixin <b>不能</b>出现在客户端门控名单里（否则服务端会丢功能）；</li>
 *   <li>其余每个 mixin <b>必须</b>出现在门控名单里（否则服务端会崩）。</li>
 * </ol>
 *
 * <p>第 2 条是安全方向：就算某个新 mixin 其实是双端的，被误加进客户端名单
 * 只会让服务端少一项优化；反过来则会直接崩溃。两者代价不对称。
 */
public final class MixinGatingSelfTest {

    /** 这些 mixin 必须在双端都应用。 */
    private static final String[] MUST_APPLY_ON_SERVER = {
            "MixinWorld",         // 光照引擎、流体缓存、方块实体缓存
            "MixinBlockLiquid",   // 流体流向缓存
            "MixinPathNavigate",  // 异步寻路
            "MixinWorldCollision" // 碰撞的 MutableBlockPos 复用（服务端同样做实体物理）
    };

    /** 源码目录（事实来源）。 */
    private static final String SRC_DIR = "src/main/java/top/fpvp/lumen/mixin";

    private static int failures;

    public static void main(String[] args) throws Exception {
        File srcDir = new File(args.length > 0 ? args[0] : SRC_DIR);
        if (!srcDir.isDirectory()) {
            System.out.println("  FAIL 找不到 mixin 源码目录: " + srcDir.getAbsolutePath());
            System.exit(1);
        }
        System.out.println("  事实来源: " + srcDir.getPath());

        List<String> actual = collectFromSources(srcDir);
        List<String> gated = readGatedList();

        System.out.println("  发现 " + actual.size() + " 个 mixin 源文件，门控名单 " + gated.size() + " 项");

        // 规则 1：双端 mixin 不能被门控。
        for (String dualUse : MUST_APPLY_ON_SERVER) {
            if (!actual.contains(dualUse)) {
                // 白名单里的类已不存在：提示清理，但不当作门控缺陷。
                System.out.println("  WARN 双端白名单里的 " + dualUse + " 已不存在，可以移除该项");
                continue;
            }
            check("双端 mixin 未被门控: " + dualUse, !gated.contains(dualUse),
                    "它必须在服务端也应用，否则服务端会丢掉这项优化");
        }

        // 规则 2：其余 mixin 必须被门控。
        List<String> missing = new ArrayList<String>();
        for (String name : actual) {
            if (isDualUse(name)) {
                continue;
            }
            if (!gated.contains(name)) {
                missing.add(name);
            }
        }
        check("所有纯客户端 mixin 都已门控", missing.isEmpty(),
                "遗漏: " + missing + "（专用服务器会因目标类缺失而启动失败）");

        // 规则 3：门控名单里不应有已不存在的类（清理残留）。
        List<String> stale = new ArrayList<String>();
        for (String g : gated) {
            if (!actual.contains(g)) {
                stale.add(g);
            }
        }
        check("门控名单无残留项", stale.isEmpty(), "残留: " + stale);

        // 补充：检查编译产物是否过期。这不是门控缺陷，而是构建提示。
        reportStaleBuild(actual);

        System.out.println();
        if (failures == 0) {
            System.out.println("All mixin gating self-tests passed.");
        } else {
            System.out.println(failures + " mixin gating self-test(s) FAILED.");
            System.exit(1);
        }
    }

    private static boolean isDualUse(String name) {
        for (String s : MUST_APPLY_ON_SERVER) {
            if (s.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从源码目录收集 mixin 类名。
     *
     * <p>只收 {@code .java} 且不含 {@code $} 的（内部类不单独注册）。
     * 源码是事实来源，不会像 {@code .class} 那样陈旧。
     */
    private static List<String> collectFromSources(File dir) {
        List<String> names = new ArrayList<String>();
        File[] files = dir.listFiles();
        if (files == null) {
            return names;
        }
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".java")) {
                continue;
            }
            String base = n.substring(0, n.length() - ".java".length());
            // 跳过明显不是 mixin 的辅助类（本项目的 mixin 包约定全部以 Mixin 开头）。
            if (!base.startsWith("Mixin")) {
                continue;
            }
            names.add(base);
        }
        java.util.Collections.sort(names);
        return names;
    }

    /**
     * 编译产物过期时给出提示。
     *
     * <p>只提示、不失败：过期产物说明"该重新构建"，不是代码缺陷。
     * 但如果把它当成事实来源（第一版的做法），就会产生假失败。
     */
    private static void reportStaleBuild(List<String> fromSource) {
        File[] dirs = {
                new File("build/classes/java/main/top/fpvp/lumen/mixin"),
                new File("build/cpcheck/top/fpvp/lumen/mixin")
        };
        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            List<String> compiled = new ArrayList<String>();
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    if (n.endsWith(".class") && n.indexOf('$') < 0 && n.startsWith("Mixin")) {
                        compiled.add(n.substring(0, n.length() - ".class".length()));
                    }
                }
            }
            List<String> missingInBuild = new ArrayList<String>();
            for (String s : fromSource) {
                if (!compiled.contains(s)) {
                    missingInBuild.add(s);
                }
            }
            if (!missingInBuild.isEmpty()) {
                System.out.println("  NOTE " + dir.getPath() + " 已过期，缺少 "
                        + missingInBuild.size() + " 个类: " + missingInBuild
                        + "（请重新编译；这不影响门控判定）");
            }
        }
    }

    /** 反射读取门控名单，避免把测试和实现绑死在源码文本上。 */
    private static List<String> readGatedList() throws Exception {
        List<String> list = new ArrayList<String>();
        Class<?> plugin = Class.forName("top.fpvp.lumen.init.AutoDiscoveryMixinPlugin");
        for (java.lang.reflect.Field f : plugin.getDeclaredFields()) {
            if (!f.getName().equals("CLIENT_ONLY_SUFFIXES")) {
                continue;
            }
            f.setAccessible(true);
            Object value = f.get(null);
            if (value instanceof String[]) {
                for (String s : (String[]) value) {
                    list.add(s);
                }
            }
        }
        return list;
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
