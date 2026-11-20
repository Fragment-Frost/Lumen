package top.fpvp.lumen.init;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 自动发现 mixin 包下所有 mixin 类并注册。
 *
 * <p>注意 {@link ClassNode} 必须从 {@code org.spongepowered.asm.lib.tree} 导入 ——
 * 这是 Mixin 0.7.x 自带的 ASM 副本。1.8.9 只能用 0.7.11，用 0.8.x 的
 * {@code org.objectweb.asm.tree} 会编译失败。
 */
public class AutoDiscoveryMixinPlugin implements IMixinConfigPlugin {

    private String mixinPackage;

    private List<String> mixins;

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage;
    }

    private String getMixinBaseDir() {
        return mixinPackage.replace(".", "/");
    }

    /**
     * 把 classUrl 归一化为「类根目录」的 URL，便于后面相对化。
     */
    private URL getBaseUrlForClassUrl(URL classUrl) {
        String string = classUrl.toString();
        if ("jar".equals(classUrl.getProtocol())) {
            try {
                return new URL(string.substring(4).split("!")[0]);
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }
        if (string.endsWith(".class")) {
            try {
                return new URL(string.replace("\\", "/")
                        .replace(getClass().getCanonicalName()
                                .replace(".", "/") + ".class", ""));
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }
        return classUrl;
    }

    private void tryAddMixinClass(String className) {
        String norm = (className.endsWith(".class")
                ? className.substring(0, className.length() - ".class".length())
                : className)
                .replace("\\", "/")
                .replace("/", ".");

        // 只接受 mixin 包下、且不是内部类的类
        if (!norm.startsWith(mixinPackage + ".") || norm.endsWith(".")) {
            return;
        }
        String relative = norm.substring(mixinPackage.length() + 1);
        if (relative.isEmpty() || relative.indexOf('$') >= 0) {
            return;
        }
        mixins.add(relative);
    }

    @Override
    public List<String> getMixins() {
        if (mixins != null) {
            return mixins;
        }
        mixins = new ArrayList<String>();

        URL classUrl = getClass().getProtectionDomain().getCodeSource().getLocation();
        Path file;
        try {
            file = Paths.get(getBaseUrlForClassUrl(classUrl).toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        if (Files.isDirectory(file)) {
            walkDir(file);
        } else {
            walkJar(file);
        }

        Collections.sort(mixins);
        return mixins;
    }

    private void walkDir(Path classRoot) {
        Path base = classRoot.resolve(getMixinBaseDir());
        if (!Files.isDirectory(base)) {
            return;
        }
        try (Stream<Path> classes = Files.walk(base)) {
            classes.filter(Files::isRegularFile)
                    .map(it -> classRoot.relativize(it).toString())
                    .forEach(this::tryAddMixinClass);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void walkJar(Path file) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry next;
            while ((next = zis.getNextEntry()) != null) {
                tryAddMixinClass(next.getName());
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * 客户端专用 mixin 只在客户端应用。
     *
     * <p>本 mod 的目标几乎全是渲染/客户端 tick，但 jar 会在专用服务器上也被加载
     * （{@code acceptableRemoteVersions = "*"} 让它能装在服务端）。若不加判断，
     * 服务端会去 mixin {@code Minecraft}/{@code GuiIngame} 这类<b>根本不存在</b>的
     * 客户端类，直接抛 {@code ClassNotFoundException} 起不来。
     *
     * <p>判断方式用类名前缀而不是 {@code MixinEnvironment.getSide()}：
     * 后者在 mixin 配置加载早期可能还是 {@code UNKNOWN}，而包名是确定的。
     *
     * <p>注意 {@code MixinWorld} / {@code MixinBlockLiquid} / {@code MixinPathNavigate}
     * 是<b>双端</b>逻辑（光照、流体、寻路在服务端同样生效），必须保留。
     */
    private static final String[] CLIENT_ONLY_SUFFIXES = {
            "MixinEntityRenderer",
            "MixinRenderGlobal",
            "MixinEffectRenderer",
            "MixinMinecraft",
            "MixinGuiIngame",
            "MixinChunkRenderDispatcher"
    };

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean client;
        try {
            client = org.spongepowered.asm.mixin.MixinEnvironment.getDefaultEnvironment().getSide()
                    == org.spongepowered.asm.mixin.MixinEnvironment.Side.CLIENT;
        } catch (Throwable t) {
            // 环境尚未就绪：按客户端处理（本 mod 的绝大多数用户是客户端）。
            client = true;
        }
        if (client) {
            return true;
        }
        String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        for (String suffix : CLIENT_ONLY_SUFFIXES) {
            if (suffix.equals(simple)) {
                // 服务端：跳过纯客户端 mixin，避免目标类缺失导致的启动失败。
                return false;
            }
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }
}
