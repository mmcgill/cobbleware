package io.github.mmcgill.cobbleware.command;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Registers commands supplied by Cobbleware. */
public final class CobblewareCommands {
    private CobblewareCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleware")
                        .then(Commands.literal("startstates")
                                .executes(CobblewareCommands::startStates))
        ));
    }

    private static int startStates(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("hello world"), false);
        return 1;
    }
}
