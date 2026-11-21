package top.fpvp.lumen.verify;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 校验 {@code AutoDiscoveryMixinPlugin} 的客户端门控名单是否覆盖了所有 mixin。
 *
 * <h2>为什么需要这个检查</h2>
 *
 * <p>自动发现插件会注册 {@code mixin} 包下的<b>每一个</b>类。而其中的
 * 纯客户端 mixin（渲渲染、GUI、粒子、皮肤图层）如果在专用服务器上被应用，
 * 会因为目标类不存在而炸在启动阶段 —— 而且这个错误发生在
 * <b>Mixin 应用期</b>，堆栈里全是 ASM 帧，几乎无法定位。
 *
 * <p>门控名单是手工维护的，而 mixin 会不断增加。这两者迟早不一致，
 * 而且不一致时<b>没有任何提示</b>（{@code require = 0} 的静默效应）。
 * 所以这里用反射读名单，和目录里的实际文件逐一比对。
 *
 * <h2>判定规则</h2>
 *
 * <p>不能简单要求"每个 mixin 都在名单里"：{@code MixinWorld}、
 * {@code MixinBlockLiquid}、{@code MixinPathNavigate}、
 * {@code MixinBlockLiquid} 是<b>双端</b>逻辑（光照、流体、寻路在服务端同样生效），
 * 它们必须在服务端也应用。因此维护一份"双端白名单"，检查：
 * <ol>
 *   <li>双端 mixin <b>不能</b>出现在客户端门控名单里（否则服务端会丢功能）；</li>
 *   <li>其余每个 mixin <b>必须</b>出现在门控名单里（否则服务端会崩）。</li>
 * </ol>
 *
 * <p>第 2 条是安全方向：就算某个新 mixin 其实是双端的，被误加进客户端名单
 * 只会让服务端少一项优化；反过来则会直接崩溃。两者的代价不对称。
 */
public final class MixinGatingSelfTest {

    /** 这些 mixin 必须在双端都应用。 */
    private static final String[] MUST_APPLY_ON_SERVER = {
            "MixinWorld",       // 光照引擎、流体缓存、方块实体缓存
            "MixinBlockLiquid", // 流体流向缓存
            "MixinPathNavigate" // 异步寻路
    };

    private static int failures;

    public static void main(String[] args) throws Exception {
        // 依次尝试几个可能的输出目录。
        //
        // 之前用「第一个存在的目录」导致了一个假失败：Gradle 的
        // build/classes/java/main 里是旧产物（10 个 mixin），
        // 而实际源码已经 13 个 —— 测试读到旧目录就报"名单有残留"。
        // 现在改为：把所有候选目录都试一遍，用<b>mixin 数量最多</b>的那个，
        // 这样一个过时的目录不会掩盖真实问题。
        List<File> candidates = new ArrayList<File>();
        if (args.length > 0) {
            candidates.add(new File(args[0]));
        }
        candidates.add(new File("build/classes/java/main/top/fpvp/lumen/mixin"));
        candidates.add(new File("build/cpcheck/top/fpvp/lumen/mixin"));

        File mixinDir = null;
        int best = -1;
        for (File dir : candidates) {
            if (!dir.isDirectory()) {
                continue;
            }
            int count = countMixinClasses(dir);
            if (count > best) {
                best = count;
                mixinDir = dir;
            }
        }

        if (mixinDir == null) {
            System.out.println("  SKIP 找不到 mixin 输出目录，跳过门控检查");
            System.out.println("（先编译主源码再运行本测试）");
            return;
        }
        System.out.println("  使用目录: " + mixinDir.getPath());

        List<String> actual = collectMixinNames(mixinDir);
        List<String> gated = readGatedList();

        System.out.println("  发现 " + actual.size() + " 个 mixin，门控名单 " + gated.size() + " 项");

        // 规则 1：双端 mixin 不能被门控。
        for (String dualUse : MUST_APPLY_ON_SERVER) {
            check("双端 mixin 未被门控: " + dualUse, !gated.contains(dualUse), "");
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

    private static int countMixinClasses(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int n = 0;
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".class") && name.indexOf('$') < 0) {
                n++;
            }
        }
        return n;
    }

    private static List<String> collectMixinNames(File dir) {
        List<String> names = new ArrayList<String>();
        File[] files = dir.listFiles();
        if (files == null) {
            return names;
        }
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".class")) {
                continue;
            }
            // 跳过内部类：插件本身也会跳过它们。
            if (n.indexOf('$') >= 0) {
                continue;
            }
            names.add(n.substring(0, n.length() - ".class".length()));
        }
        java.util.Collections.sort(names);
        return names;
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
