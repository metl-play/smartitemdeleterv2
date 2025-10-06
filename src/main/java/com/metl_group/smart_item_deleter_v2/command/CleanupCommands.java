package com.metl_group.smart_item_deleter_v2.command;

import com.metl_group.smart_item_deleter_v2.core.ItemCleanupSystem;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class CleanupCommands {
    private CleanupCommands(){}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("cleanup")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("now")
                        .executes(ctx -> {
                            var srv = ctx.getSource().getServer();
                            long now = srv.getTickCount() * 50L;
                            for (var level : srv.getAllLevels()) {
                                // Optional: forced variant
                                ItemCleanupSystem.runCycle(level, now);
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("Forced cleanup executed."), true);
                            return 1;
                        })
                )
                .then(Commands.literal("stats")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("Cleanup v2: Stats coming soon."), false);
                            return 1;
                        })
                )
                .then(Commands.literal("dryrun")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("Dry-run: Vorschau coming soon."), false);
                            return 1;
                        })
                )
        );
    }
}
