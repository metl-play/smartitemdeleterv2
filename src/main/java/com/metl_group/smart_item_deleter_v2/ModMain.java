package com.metl_group.smart_item_deleter_v2;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import com.metl_group.smart_item_deleter_v2.command.CleanupCommands;
import com.metl_group.smart_item_deleter_v2.config.CleanupConfig;

@Mod(ModMain.MOD_ID)
public final class ModMain {
    public static final String MOD_ID = "smart_item_deleter_v2";

    public ModMain(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, CleanupConfig.SERVER_SPEC);
    }

    @EventBusSubscriber(modid = MOD_ID)
    public static final class ModBus {
        @SubscribeEvent
        public static void onCommonSetup(final FMLCommonSetupEvent e) {
        }

        @SubscribeEvent
        public static void onConfigReload(final ModConfigEvent.Reloading e) {
            if (e.getConfig().getSpec() == CleanupConfig.SERVER_SPEC) {
                CleanupConfig.trackConfig(e.getConfig());
                CleanupConfig.bake();
            }
        }

        @SubscribeEvent
        public static void onConfigLoad(final ModConfigEvent.Loading e) {
            if (e.getConfig().getSpec() == CleanupConfig.SERVER_SPEC) {
                CleanupConfig.trackConfig(e.getConfig());
                CleanupConfig.bake();
            }
        }
    }

    @EventBusSubscriber(modid = MOD_ID)
    public static final class GameBus {
        @SubscribeEvent
        public static void onRegisterCommands(final RegisterCommandsEvent e) {
            CleanupCommands.register(e.getDispatcher());
        }
    }
}
