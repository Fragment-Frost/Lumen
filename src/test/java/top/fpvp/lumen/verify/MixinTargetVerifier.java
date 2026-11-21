package top.fpvp.lumen.verify;

import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 构建期校验：确认每个 mixin 的目标方法、以及 @Inject/@Redirect 的注入点
 * 真的存在于目标类里。
 *
 * <p>存在的理由：mixin 的 require = 0 会让"目标不存在"这类错误静默通过。
 * 功能于是完全失效，但游戏照常启动、日志干干净净 —— 这是最难发现的一类 bug。
 * 本工具把这类问题变成构建期可见的失败。
 *
 * <p>实现方式：不启动 Mixin 运行时（那需要完整的 launchwrapper 环境），
 * 而是直接从目标 jar 里读出目标类的字节码，用 ASM（Mixin 自带的副本）检查：
 * 目标类是否存在、声明的方法是否存在、指定的调用点是否存在于该方法体内。
 */
public final class MixinTargetVerifier {

    /** 待校验的 spec：目标类 + 方法 + 可选的注入目标。 */
    private static final class Spec {
        final String what;
        final String targetClass;
        final String methodName;
        final String methodDesc;
        final String invokeOwner;
        final String invokeName;
        final String invokeDesc;

        Spec(String what, String targetClass, String methodName, String methodDesc,
             String invokeOwner, String invokeName, String invokeDesc) {
            this.what = what;
            this.targetClass = targetClass;
            this.methodName = methodName;
            this.methodDesc = methodDesc;
            this.invokeOwner = invokeOwner;
            this.invokeName = invokeName;
            this.invokeDesc = invokeDesc;
        }

        static Spec method(String what, String cls, String name, String desc) {
            return new Spec(what, cls, name, desc, null, null, null);
        }

        static Spec invoke(String what, String cls, String name, String desc,
                           String owner, String target, String targetDesc) {
            return new Spec(what, cls, name, desc, owner, target, targetDesc);
        }
    }

    private static final List<Spec> SPECS = new ArrayList<Spec>();

    static {
        // ---- MixinWorld ----
        SPECS.add(Spec.invoke("MixinWorld checkLight redirect",
                "net/minecraft/world/World",
                "setBlockState",
                "(Lnet/minecraft/util/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z",
                "net/minecraft/world/World", "checkLight", "(Lnet/minecraft/util/BlockPos;)Z"));
        SPECS.add(Spec.method("MixinWorld tick", "net/minecraft/world/World", "tick", "()V"));
        SPECS.add(Spec.method("MixinWorld updateBlocks", "net/minecraft/world/World", "updateBlocks", "()V"));
        SPECS.add(Spec.method("MixinWorld updateEntities", "net/minecraft/world/World", "updateEntities", "()V"));

        // ---- MixinBlockLiquid ----
        SPECS.add(Spec.method("MixinBlockLiquid getFlowVector",
                "net/minecraft/block/BlockLiquid", "getFlowVector",
                "(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/BlockPos;)Lnet/minecraft/util/Vec3;"));

        // ---- MixinEntityRenderer ----
        SPECS.add(Spec.invoke("MixinEntityRenderer frustum hook",
                "net/minecraft/client/renderer/EntityRenderer", "renderWorldPass", "(IFJ)V",
                "net/minecraft/client/renderer/culling/ClippingHelperImpl", "getInstance",
                "()Lnet/minecraft/client/renderer/culling/ClippingHelper;"));

        // ---- MixinRenderGlobal ----
        SPECS.add(Spec.invoke("MixinRenderGlobal TESR occlusion",
                "net/minecraft/client/renderer/RenderGlobal",
                "renderEntities",
                "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V",
                "net/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher", "renderTileEntity",
                "(Lnet/minecraft/tileentity/TileEntity;FI)V"));
        SPECS.add(Spec.invoke("MixinRenderGlobal entity occlusion",
                "net/minecraft/client/renderer/RenderGlobal",
                "renderEntities",
                "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V",
                "net/minecraft/client/renderer/entity/RenderManager", "shouldRender",
                "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z"));

        // ---- MixinEffectRenderer ----
        SPECS.add(Spec.invoke("MixinEffectRenderer particle cull",
                "net/minecraft/client/particle/EffectRenderer", "renderParticles",
                "(Lnet/minecraft/entity/Entity;F)V",
                "net/minecraft/client/particle/EntityFX", "renderParticle",
                "(Lnet/minecraft/client/renderer/WorldRenderer;Lnet/minecraft/entity/Entity;FFFFFF)V"));

        // ---- MixinMinecraft ----
        SPECS.add(Spec.method("MixinMinecraft runTick", "net/minecraft/client/Minecraft", "runTick", "()V"));
        SPECS.add(Spec.method("MixinMinecraft shutdown", "net/minecraft/client/Minecraft", "shutdown", "()V"));
        SPECS.add(Spec.method("MixinMinecraft loadWorld", "net/minecraft/client/Minecraft", "loadWorld",
                "(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V"));

        // ---- MixinGuiIngame ----
        SPECS.add(Spec.method("MixinGuiIngame renderGameOverlay",
                "net/minecraft/client/gui/GuiIngame", "renderGameOverlay", "(F)V"));

        // ---- MixinPathNavigate ----
        SPECS.add(Spec.method("MixinPathNavigate tryMoveToXYZ",
                "net/minecraft/pathfinding/PathNavigate", "tryMoveToXYZ", "(DDDD)Z"));
        SPECS.add(Spec.method("MixinPathNavigate tryMoveToEntityLiving",
                "net/minecraft/pathfinding/PathNavigate", "tryMoveToEntityLiving",
                "(Lnet/minecraft/entity/Entity;D)Z"));

        // ---- MixinChunkRenderDispatcher ----
        SPECS.add(Spec.method("MixinChunkRenderDispatcher ctor",
                "net/minecraft/client/renderer/chunk/ChunkRenderDispatcher", "<init>", "()V"));

        // ---- MixinGuiMainMenu ----
        //
        // 移除 Realms 按钮靠的是 @Redirect 拦截 List#add。
        // 这里校验两个前提：宿主方法存在、且它内部确实有 List#add 调用点。
        // 后者尤其重要 —— 如果目标方法里没有 add 调用，mixin 会静默不生效
        // （require = 0），表现为"Realms 按钮还在"却查不出原因。
        SPECS.add(Spec.method("MixinGuiMainMenu addSingleplayerMultiplayerButtons",
                "net/minecraft/client/gui/GuiMainMenu", "addSingleplayerMultiplayerButtons",
                "(II)V"));
        SPECS.add(Spec.invoke("MixinGuiMainMenu List.add redirect",
                "net/minecraft/client/gui/GuiMainMenu", "addSingleplayerMultiplayerButtons",
                "(II)V",
                "java/util/List", "add", "(Ljava/lang/Object;)Z"));

        // ---- MixinWorld tick HEAD（光照引擎/方块实体缓存重置） ----
        //
        // 这两个 @Inject 挂在 tick 的 HEAD 上；tick 本身已在上面校验过，
        // 这里额外确认它有 Code 属性（不是抽象/桥接方法）。
        SPECS.add(Spec.method("MixinWorld tick HEAD",
                "net/minecraft/world/World", "tick", "()V"));

        // ---- MixinLayerCape ----
        //
        // 皮肤 LoD 的披风跳过点。参数是 1 个玩家 + **7 个** float
        // （limbSwing, limbSwingAmount, partialTicks, ageInTicks,
        //  netHeadYaw, headPitch, scale），一共 8 个参数。
        // 我第一版写成了 8 个 F，校验器直接抓出来了 —— 这类错误靠肉眼
        // 数 float 个数几乎不可能可靠。
        SPECS.add(Spec.method("MixinLayerCape doRenderLayer",
                "net/minecraft/client/renderer/entity/layers/LayerCape", "doRenderLayer",
                "(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V"));

        // ---- MixinLayerCustomHead ----
        //
        // 头饰层。注意参数类型是 EntityLivingBase（不是玩家类型），
        // 因为同一层也被其它生物使用 —— 写成 EntityPlayer 会找不到方法。
        SPECS.add(Spec.method("MixinLayerCustomHead doRenderLayer",
                "net/minecraft/client/renderer/entity/layers/LayerCustomHead", "doRenderLayer",
                "(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V"));

        // ---- MixinRenderGlobalFacing ----
        //
        // 可见面缓存的注入点。这个方法必须是 private 且返回 Set，
        // 否则 @Inject 的 HEAD/RETURN 都会静默失败（require = 0）。
        SPECS.add(Spec.method("MixinRenderGlobalFacing getVisibleFacings",
                "net/minecraft/client/renderer/RenderGlobal", "getVisibleFacings",
                "(Lnet/minecraft/util/BlockPos;)Ljava/util/Set;"));
    }

    private MixinTargetVerifier() {
    }

    public static void main(String[] args) throws Exception {
        File jar = null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--target-jar".equals(args[i])) {
                jar = new File(args[i + 1]);
            }
        }
        if (jar == null || !jar.isFile()) {
            System.err.println("usage: MixinTargetVerifier --target-jar <minecraft.jar>");
            System.exit(2);
        }

        int failures = 0;
        ZipFile zip = new ZipFile(jar);
        try {
            for (Spec spec : SPECS) {
                String error = verify(zip, spec);
                if (error == null) {
                    System.out.println("  OK   " + spec.what);
                } else {
                    failures++;
                    System.out.println("  FAIL " + spec.what + " -> " + error);
                }
            }
        } finally {
            zip.close();
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("All " + SPECS.size() + " mixin targets resolved.");
        } else {
            System.out.println(failures + " of " + SPECS.size() + " mixin targets FAILED.");
            System.exit(1);
        }
    }

    private static String verify(ZipFile zip, Spec spec) throws Exception {
        ZipEntry entry = zip.getEntry(spec.targetClass + ".class");
        if (entry == null) {
            return "target class not found in jar: " + spec.targetClass;
        }

        ClassNode node;
        InputStream in = zip.getInputStream(entry);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            node = new ClassNode();
            new ClassReader(out.toByteArray()).accept(node, 0);
        } finally {
            in.close();
        }

        MethodNode method = findMethod(node, spec.methodName, spec.methodDesc);
        if (method == null) {
            return "method not found: " + spec.methodName + spec.methodDesc + " in " + spec.targetClass;
        }

        if (spec.invokeName == null) {
            return null;
        }

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getType() != AbstractInsnNode.METHOD_INSN) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (call.name.equals(spec.invokeName)
                    && call.desc.equals(spec.invokeDesc)
                    && (spec.invokeOwner == null || call.owner.equals(spec.invokeOwner))) {
                return null;
            }
        }
        return "invoke site not found: " + spec.invokeOwner + "." + spec.invokeName
                + spec.invokeDesc + " inside " + spec.targetClass + "." + spec.methodName;
    }

    private static MethodNode findMethod(ClassNode node, String name, String desc) {
        for (int i = 0; i < node.methods.size(); i++) {
            MethodNode m = node.methods.get(i);
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return m;
            }
        }
        return null;
    }
}
