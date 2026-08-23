package com.xm2nd.tlmagenttweaks.client;

import com.github.tartaricacid.touhoulittlemaid.api.event.client.AddClothConfigEvent;
import com.xm2nd.tlmagenttweaks.Config;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Cloth Config 集成：在 TLM 设置页的「AI 功能」大类下追加「AI 功能增强」子栏目
 * （与 LLM/TTS/STT 子栏目并列，挂载方式与 Self-Talk 一致）。
 * <p>
 * 由入口类经反射注册，未安装 cloth-config 时本类不会被加载。
 */
@OnlyIn(Dist.CLIENT)
public final class TweaksClothConfig {

    private TweaksClothConfig() {
    }

    @SubscribeEvent
    public static void onAddClothConfig(AddClothConfigEvent event) {
        ConfigBuilder root = event.getRoot();
        ConfigEntryBuilder entryBuilder = event.getEntryBuilder();
        ConfigCategory globalAi = root.getOrCreateCategory(
                Component.translatable("config.touhou_little_maid.global_ai"));

        SubCategoryBuilder tweaks = entryBuilder.startSubCategory(
                        Component.translatable("config.tlm_agent_tweaks.title"))
                .setExpanded(true);

        tweaks.add(entryBuilder.startIntSlider(
                        Component.translatable("config.tlm_agent_tweaks.threshold"),
                        Config.MANUAL_COMPRESS_THRESHOLD_K.get(), 1, 1024)
                .setDefaultValue(8)
                .setTooltip(Component.translatable("config.tlm_agent_tweaks.threshold.tooltip"))
                .setSaveConsumer(v -> saveInt(Config.MANUAL_COMPRESS_THRESHOLD_K, v))
                .build());

        tweaks.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_agent_tweaks.stats_enabled"),
                        Config.STATS_ENABLED.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.tlm_agent_tweaks.stats_enabled.tooltip"))
                .setSaveConsumer(v -> saveBool(Config.STATS_ENABLED, v))
                .build());

        tweaks.add(entryBuilder.startIntSlider(
                        Component.translatable("config.tlm_agent_tweaks.admin_level"),
                        Config.STATS_ADMIN_PERMISSION_LEVEL.get(), 0, 4)
                .setDefaultValue(2)
                .setTooltip(Component.translatable("config.tlm_agent_tweaks.admin_level.tooltip"))
                .setSaveConsumer(v -> saveInt(Config.STATS_ADMIN_PERMISSION_LEVEL, v))
                .build());

        tweaks.add(entryBuilder.startBooleanToggle(
                        Component.translatable("config.tlm_agent_tweaks.record_summary"),
                        Config.STATS_RECORD_SUMMARY_CALLS.get())
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.tlm_agent_tweaks.record_summary.tooltip"))
                .setSaveConsumer(v -> saveBool(Config.STATS_RECORD_SUMMARY_CALLS, v))
                .build());

        globalAi.addEntry(tweaks.build());
    }

    private static void saveInt(ModConfigSpec.IntValue value, int newValue) {
        value.set(newValue);
        value.save();
    }

    private static void saveBool(ModConfigSpec.BooleanValue value, boolean newValue) {
        value.set(newValue);
        value.save();
    }
}
