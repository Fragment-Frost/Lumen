package top.fpvp.lumen.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.GameSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fpvp.lumen.LumenConfig;
import top.fpvp.lumen.LumenProfiler;
import top.fpvp.lumen.thread.AsyncPathfinding;
import top.fpvp.lumen.thread.AsyncTaskDispatcher;

/**
 * 在 F3 调试界面里追加 Lumen 的统计行。
 *
 * <p>只做“追加一行”，不替换原版任何输出：调试点被插在
 * {@code renderGameOverlay} 的末尾，此时原版 HUD 已经画完，
 * 我们只是接着写一行字，不存在与原版抢状态的问题。
 *
 * <p>这行统计本身就是这个 mod 的价值证明：它能直接告诉你
 * “这一帧到底剔掉了多少东西、后台队列有没有积压”。没有它，
 * 这些优化是否生效就只能靠猜。
 */
@Mixin(GuiIngame.class)
public abstract class MixinGuiIngame {

    @Inject(method = "renderGameOverlay(F)V", at = @At("RETURN"))
    private void lumen$debugOverlay(float partialTicks, CallbackInfo ci) {
        if (!LumenConfig.enabled || !LumenConfig.debugOverlay) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings == null || !mc.gameSettings.showDebugInfo || mc.fontRendererObj == null) {
            return;
        }

        ScaledResolution resolution = new ScaledResolution(mc);
        FontRenderer font = mc.fontRendererObj;

        int x = 2;
        // 放在左下角，避开原版 F3 的两列文本。
        int y = resolution.getScaledHeight() - font.FONT_HEIGHT * 3;

        font.drawStringWithShadow(LumenProfiler.debugLine(), x, y, 0x55FFFF);
        font.drawStringWithShadow(AsyncTaskDispatcher.get().describe(), x, y + font.FONT_HEIGHT, 0xAAAAAA);
        font.drawStringWithShadow(AsyncPathfinding.describe(), x, y + font.FONT_HEIGHT * 2, 0xAAAAAA);
    }
}
