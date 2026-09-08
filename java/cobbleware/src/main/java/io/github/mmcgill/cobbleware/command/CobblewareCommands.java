package io.github.mmcgill.cobbleware.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.mmcgill.cobbleware.api.MainWorkerClient;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Registers commands supplied by Cobbleware. */
public final class CobblewareCommands {
    private CobblewareCommands() {
    }

    public static void register(MainWorkerClient mainWorkerClient) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("cobbleware")
                        .then(Commands.literal("auth")
                                .then(Commands.argument("code", StringArgumentType.word())
                                        .executes(context -> authenticate(context, mainWorkerClient))))
                        .then(Commands.literal("startstates")
                                .executes(CobblewareCommands::startStates))
        ));
    }

    private static int authenticate(CommandContext<CommandSourceStack> context, MainWorkerClient mainWorkerClient) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.literal("Only a player can authenticate Cobbleware."));
            return 0;
        }

        String code = StringArgumentType.getString(context, "code");
        MainWorkerClient.AuthenticationRequest request = new MainWorkerClient.AuthenticationRequest(
                code,
                player.getUUID(),
                player.getGameProfile().name());

        player.sendSystemMessage(Component.literal("Checking Cobbleware authentication code..."));
        mainWorkerClient.authenticate(request).thenAccept(result -> source.getServer().execute(
                () -> sendAuthenticationResult(player, result)));
        return 1;
    }

    private static void sendAuthenticationResult(
            ServerPlayer player,
            MainWorkerClient.AuthenticationResult result) {
        Component message = switch (result.status()) {
            case SUCCESS -> Component.literal("Cobbleware authentication accepted. Your browser should finish connecting shortly.");
            case CONFIGURATION_ERROR -> Component.literal("Cobbleware is not configured correctly on this server.");
            case UNAUTHORIZED -> Component.literal("Cobbleware could not authenticate with its Main Worker. Contact a server administrator.");
            case REJECTED -> Component.literal("That Cobbleware authentication code is invalid, expired, or already in use. Get a new code and try again.");
            case UNAVAILABLE -> Component.literal("Cobbleware could not reach its Main Worker. Try again shortly.");
        };
        player.sendSystemMessage(message);
    }

    private static int startStates(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("hello world"), false);
        return 1;
    }
}
