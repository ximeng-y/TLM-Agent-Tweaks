package com.xm2nd.tlmagenttweaks.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.HistoryAIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.xm2nd.tlmagenttweaks.network.RequestCompressHistoryPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在历史聊天记录界面右列「清空聊天记录」按钮正上方追加「压缩聊天记录」按钮。
 * <p>
 * 点击后向服务端发起手动压缩请求；按钮置为禁用态防止连点，
 * 收到结果包后（ClientPacketHandlers）整个界面会重新 init 恢复按钮。
 */
@Mixin(HistoryAIChatScreen.class)
public abstract class HistoryAIChatScreenMixin {

    @Shadow
    @Final
    private EntityMaid maid;

    @Inject(method = "addButtons", at = @At("TAIL"), remap = false)
    private void tlmAgentTweaks$addCompressButton(CallbackInfo ci) {
        HistoryAIChatScreen screen = (HistoryAIChatScreen) (Object) this;
        // 与 HistoryAIChatScreen 相同的布局参数：右列左缘 posX+150（posX = width/2-75），
        // 清空按钮 y = height-70，此处取其正上方一格（按钮高 20、间距 6）
        int x = screen.width / 2 + 75;
        int y = screen.height - 96;

        FlatColorButton button = new FlatColorButton(x, y, 120, 20,
                net.minecraft.network.chat.Component.translatable("gui.tlm_agent_tweaks.button.compress_history"),
                b -> {
                    b.active = false;
                    b.setMessage(net.minecraft.network.chat.Component.translatable("gui.tlm_agent_tweaks.button.compress_history.pending"));
                    PacketDistributor.sendToServer(new RequestCompressHistoryPayload(this.maid.getId()));
                });
        // 反射加入（同 AIChatScreenMixin 的原因）
        try {
            java.lang.reflect.Method method = net.minecraft.client.gui.screens.Screen.class
                    .getDeclaredMethod("addRenderableWidget", net.minecraft.client.gui.components.events.GuiEventListener.class);
            method.setAccessible(true);
            method.invoke(screen, button);
        } catch (ReflectiveOperationException e) {
            com.xm2nd.tlmagenttweaks.TlmAgentTweaks.LOGGER.warn("Failed to add compress button to HistoryAIChatScreen", e);
        }
    }
}
