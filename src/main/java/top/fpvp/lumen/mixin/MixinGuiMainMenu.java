package top.fpvp.lumen.mixin;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * 从主菜单移除 Realms 按钮，并把被它占掉的布局位置补回来。
 *
 * <h3>为什么用 @Redirect 拦截 List#add 而不是改 ID</h3>
 *
 * <p>1.8.9 的 {@code addSingleplayerMultiplayerButtons} 会往 {@code buttonList}
 * 里依次塞四个按钮：单人（id 1）、多人（id 2）、<b>Realms（id 14）</b>、Mods（id 6）。
 * Realms 与 Mods 是<b>左右并排</b>的半宽按钮（各 98px），前两个是整宽（200px）。
 *
 * <p>三种可行做法里：
 * <ul>
 *   <li>改 ID —— 按钮还在，只是点了没反应，视觉上仍然碍眼；</li>
 *   <li>在 {@code initGui} 的 RETURN 里 {@code buttonList.remove(...)} —— 可行，
 *       但要在每一处调用 {@code initGui} 的路径上都跑一遍，而且此时 Mods 按钮
 *       的位置已经按“有 Realms”算好了，移除后会留下半宽的洞；</li>
 *   <li><b>在 {@code List#add} 这一层拦住</b> —— 按钮根本没进列表，
 *       而且可以把 Mods 按钮顺带改成整宽居中，布局是干净的。</li>
 * </ul>
 *
 * <p>这里选第三种。判断依据用 {@code id} 与 {@code width} 双重确认：
 * 单看 id（14）已经足够，但加上 {@code width == 98} 能避免在别的版本/别的 mod
 * 恰好复用了 id 14 时误删一个无辜的按钮 —— 误删按钮比留着一个 Realms 按钮糟糕得多。
 */
@Mixin(GuiMainMenu.class)
public abstract class MixinGuiMainMenu {

    /** Realms 按钮的 id（1.8.9 硬编码）。 */
    private static final int REALMS_BUTTON_ID = 14;

    /** Realms/Mods 这两个半宽按钮的宽度。 */
    private static final int HALF_BUTTON_WIDTH = 98;

    /** 整宽按钮的宽度。 */
    private static final int FULL_BUTTON_WIDTH = 200;

    /**
     * 拦截 {@code initGui} 期间往按钮列表里添加 Realms 按钮。
     *
     * <p>返回 {@code false} 表示“假装加过了”，调用方 {@code pop} 掉返回值后继续，
     * 不会有任何察觉。
     *
     * <p>参数类型必须是 {@code List}（擦除后的类型），不能写 {@code List<GuiButton>}：
     * {@code @Redirect} 的方法签名按字节码描述符匹配，泛型参数在描述符里不存在。
     * 写成泛型会在运行期报“无法找到注入点”。
     */
    @Redirect(
            method = "addSingleplayerMultiplayerButtons",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;add(Ljava/lang/Object;)Z"
            ),
            require = 0
    )
    @SuppressWarnings("unchecked")
    private boolean lumen$skipRealmsButton(List list, Object candidate) {
        if (candidate instanceof GuiButton) {
            GuiButton button = (GuiButton) candidate;
            if (button.id == REALMS_BUTTON_ID && button.width == HALF_BUTTON_WIDTH) {
                // 丢弃 Realms 按钮。返回 false 会被调用方 pop 掉，无副作用。
                return false;
            }
            // Mods 按钮原本是右侧半宽（x = width/2 + 2）。
            // Realms 没了之后让它撑成整宽并居中，否则左侧会空出 100px 的洞。
            // 注意 1.8.9 的字段名是 xPosition（不是 x）。
            if (button.id == 6 && button.width == HALF_BUTTON_WIDTH) {
                button.xPosition = (screenWidth() - FULL_BUTTON_WIDTH) / 2;
                button.width = FULL_BUTTON_WIDTH;
            }
        }
        return list.add(candidate);
    }

    /**
     * 当前 GUI 宽度。
     *
     * <p>用 {@code width} 字段而不是缓存的屏幕尺寸：GUI 缩放改变后
     * {@code initGui} 会被重新调用，此时 {@code width} 才是最新值。
     * 通过 mixin 的 {@code @Shadow} 拿更规范，但这里是 private 方法，
     * 用强制转换读 public 字段更省事且不依赖字段名。
     */
    private int screenWidth() {
        return ((net.minecraft.client.gui.GuiScreen) (Object) this).width;
    }
}
