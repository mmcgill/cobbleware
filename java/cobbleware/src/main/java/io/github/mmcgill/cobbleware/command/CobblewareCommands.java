package io.github.mmcgill.cobbleware.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.mmcgill.cobbleware.api.MainWorkerClient;
import io.github.mmcgill.cobbleware.block.CobbleputerBlock;
import io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity;
import io.github.mmcgill.cobbleware.block.CobbleputerState;
import io.github.mmcgill.cobbleware.execution.ExecutionDriver;
import io.github.mmcgill.cobbleware.instrumentation.Instrumentation;
import io.github.mmcgill.cobbleware.instrumentation.StatsFormatter;
import io.github.mmcgill.cobbleware.registry.CobblewareBlocks;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Registers commands supplied by Cobbleware. */
public final class CobblewareCommands {
    private static final double EXECUTION_RANGE = 3.0;
    private static final FontDescription UNIFORM_FONT =
            new FontDescription.Resource(Identifier.withDefaultNamespace("uniform"));

    private CobblewareCommands() {}

    public static void register(
            MainWorkerClient mainWorkerClient, Instrumentation instrumentation, ExecutionDriver executionDriver) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("cobbleware")
                        .then(Commands.literal("auth")
                                .then(Commands.argument("code", StringArgumentType.word())
                                        .executes(context -> authenticate(context, mainWorkerClient))))
                        .then(Commands.literal("program").executes(context -> program(context, mainWorkerClient)))
                        .then(Commands.literal("exec")
                                .then(Commands.argument("start-state", StringArgumentType.word())
                                        .suggests((context, builder) ->
                                                suggestStartStates(context, builder, mainWorkerClient))
                                        .executes(context -> execute(context, mainWorkerClient, executionDriver))))
                        .then(Commands.literal("pause").executes(context -> pause(context, executionDriver)))
                        .then(Commands.literal("resume").executes(context -> resume(context, executionDriver)))
                        .then(Commands.literal("stats")
                                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                                .executes(context -> stats(context, instrumentation))
                                .then(Commands.argument("qualifier", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                StatsFormatter.qualifiers(instrumentation.snapshot()), builder))
                                        .executes(context -> stats(context, instrumentation))))));
    }

    private static int stats(CommandContext<CommandSourceStack> context, Instrumentation instrumentation) {
        String qualifier;
        try {
            qualifier = StringArgumentType.getString(context, "qualifier");
        } catch (IllegalArgumentException ignored) {
            sendStats(context.getSource(), StatsFormatter.all(instrumentation.snapshot()));
            return 1;
        }

        io.github.mmcgill.cobbleware.instrumentation.Instrumentation.Snapshot snapshot = instrumentation.snapshot();
        try {
            UUID cobbleputerId = UUID.fromString(qualifier);
            Optional<java.util.List<StatsFormatter.Line>> stats = StatsFormatter.cobbleputer(snapshot, cobbleputerId);
            if (stats.isEmpty()) {
                context.getSource()
                        .sendFailure(Component.literal(
                                "No instrumentation data exists for Cobbleputer " + cobbleputerId + "."));
                return 0;
            }
            sendStats(context.getSource(), stats.orElseThrow());
            return 1;
        } catch (IllegalArgumentException ignored) {
            // The qualifier may be an event or player name.
        }

        Optional<java.util.List<StatsFormatter.Line>> eventStats = StatsFormatter.event(snapshot, qualifier);
        if (eventStats.isPresent()) {
            sendStats(context.getSource(), eventStats.orElseThrow());
            return 1;
        }
        Optional<java.util.List<StatsFormatter.Line>> playerStats = StatsFormatter.player(snapshot, qualifier);
        if (playerStats.isPresent()) {
            sendStats(context.getSource(), playerStats.orElseThrow());
            return 1;
        }
        context.getSource().sendFailure(Component.literal("Unknown Cobbleware stats qualifier '" + qualifier + "'."));
        return 0;
    }

    private static void sendStats(CommandSourceStack source, java.util.List<StatsFormatter.Line> lines) {
        for (StatsFormatter.Line line : lines) {
            MutableComponent message = Component.literal(line.text());
            if (line.usesUniformFont()) {
                message = message.withStyle(style -> style.withFont(UNIFORM_FONT));
            }
            Component renderedMessage = message;
            source.sendSuccess(() -> renderedMessage, false);
        }
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
                code, player.getUUID(), player.getGameProfile().name());

        player.sendSystemMessage(Component.literal("Checking Cobbleware authentication code..."));
        mainWorkerClient
                .authenticate(request)
                .thenAccept(result -> source.getServer().execute(() -> sendAuthenticationResult(player, result)));
        return 1;
    }

    private static void sendAuthenticationResult(ServerPlayer player, MainWorkerClient.AuthenticationResult result) {
        Component message =
                switch (result.status()) {
                    case SUCCESS ->
                        Component.literal(
                                "Cobbleware authentication accepted. Your browser should finish connecting shortly.");
                    case CONFIGURATION_ERROR ->
                        Component.literal("Cobbleware is not configured correctly on this server.");
                    case UNAUTHORIZED ->
                        Component.literal("Cobbleware could not authenticate with its Main Worker. Contact a server"
                                + " administrator.");
                    case REJECTED ->
                        Component.literal(
                                "That Cobbleware authentication code is invalid, expired, or already in use. Get a new"
                                        + " code and try again.");
                    case UNAVAILABLE ->
                        Component.literal("Cobbleware could not reach its Main Worker. Try again shortly.");
                };
        player.sendSystemMessage(message);
    }

    private static int program(CommandContext<CommandSourceStack> context, MainWorkerClient mainWorkerClient) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.literal("Only a player can view a Cobbleware program."));
            return 0;
        }

        player.sendSystemMessage(Component.literal("Looking up your Cobbleware program..."));
        mainWorkerClient
                .getProgram(player.getUUID())
                .thenAccept(result -> source.getServer().execute(() -> sendProgramResult(player, result)));
        return 1;
    }

    private static void sendProgramResult(ServerPlayer player, MainWorkerClient.ProgramResult result) {
        Component message =
                switch (result.status()) {
                    case SUCCESS -> Component.literal(formatProgram(result.program()));
                    case NO_PROGRAM -> Component.literal("You do not have an active Cobbleware program.");
                    case CONFIGURATION_ERROR ->
                        Component.literal("Cobbleware is not configured correctly on this server.");
                    case UNAUTHORIZED ->
                        Component.literal("Cobbleware could not authenticate with its Main Worker. Contact a server"
                                + " administrator.");
                    case PROGRAM_ERROR ->
                        Component.literal("Your Cobbleware program could not provide start states: " + result.detail());
                    case UNAVAILABLE ->
                        Component.literal("Cobbleware could not reach its Main Worker. Try again shortly.");
                };
        player.sendSystemMessage(message);
    }

    private static CompletableFuture<Suggestions> suggestStartStates(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder, MainWorkerClient mainWorkerClient) {
        ServerPlayer player;
        try {
            player = context.getSource().getPlayerOrException();
        } catch (CommandSyntaxException exception) {
            return builder.buildFuture();
        }

        return mainWorkerClient
                .getProgram(player.getUUID())
                .thenCompose(result -> result.status() == MainWorkerClient.ProgramStatus.SUCCESS
                        ? SharedSuggestionProvider.suggest(result.program().startStateNames(), builder)
                        : builder.buildFuture());
    }

    private static int execute(
            CommandContext<CommandSourceStack> context,
            MainWorkerClient mainWorkerClient,
            ExecutionDriver executionDriver) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.literal("Only a player can start a Cobbleware execution."));
            return 0;
        }

        Optional<CobbleputerTarget> target = findNearestCobbleputer(player);
        if (target.isEmpty()) {
            player.sendSystemMessage(Component.literal("No Cobbleputer is within 3m."));
            return 0;
        }
        if (target.orElseThrow().state().getValue(CobbleputerBlock.STATE) == CobbleputerState.RUNNING) {
            player.sendSystemMessage(Component.literal("The nearest Cobbleputer is already running."));
            return 0;
        }

        String startStateName = StringArgumentType.getString(context, "start-state");
        player.sendSystemMessage(Component.literal("Looking up your Cobbleware program..."));
        mainWorkerClient
                .getProgram(player.getUUID())
                .thenAccept(result -> source.getServer()
                        .execute(() -> completeExecutionStart(player, startStateName, result, executionDriver)));
        return 1;
    }

    private static void completeExecutionStart(
            ServerPlayer player,
            String startStateName,
            MainWorkerClient.ProgramResult result,
            ExecutionDriver executionDriver) {
        Optional<CobbleputerTarget> target = findNearestCobbleputer(player);
        if (target.isEmpty()) {
            player.sendSystemMessage(Component.literal("No Cobbleputer is within 3m."));
            return;
        }
        if (target.orElseThrow().state().getValue(CobbleputerBlock.STATE) == CobbleputerState.RUNNING) {
            player.sendSystemMessage(Component.literal("The nearest Cobbleputer is already running."));
            return;
        }

        if (result.status() != MainWorkerClient.ProgramStatus.SUCCESS) {
            sendExecutionProgramFailure(player, result);
            return;
        }

        Optional<byte[]> startState = result.program().startState(startStateName);
        if (startState.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "Your active Cobbleware program has no start state named '" + startStateName + "'."));
            return;
        }

        CobbleputerTarget resolvedTarget = target.orElseThrow();
        if (!(resolvedTarget.level() instanceof ServerLevel serverLevel)) {
            player.sendSystemMessage(Component.literal("Cobbleware execution can only run on the server."));
            return;
        }
        ExecutionDriver.ExecutionInitResult activation = executionDriver.startExecution(
                ExecutionDriver.minecraft(serverLevel.getServer()),
                resolvedTarget.blockEntity(),
                player.getUUID(),
                player.getGameProfile().name(),
                startState.orElseThrow());
        if (activation == ExecutionDriver.ExecutionInitResult.LIMIT_REACHED) {
            sendRunningExecutionLimitReached(player, executionDriver.maxRunningExecutionsPerPlayer());
            return;
        }
        if (activation != ExecutionDriver.ExecutionInitResult.SUCCESS) {
            player.sendSystemMessage(Component.literal("The nearest Cobbleputer could not be started."));
            return;
        }
        player.sendSystemMessage(
                Component.literal("Started your Cobbleware program at start state '" + startStateName + "'."));
    }

    private static void sendExecutionProgramFailure(ServerPlayer player, MainWorkerClient.ProgramResult result) {
        Component message =
                switch (result.status()) {
                    case NO_PROGRAM -> Component.literal("You do not have an active Cobbleware program.");
                    case CONFIGURATION_ERROR ->
                        Component.literal("Cobbleware is not configured correctly on this server.");
                    case UNAUTHORIZED ->
                        Component.literal("Cobbleware could not authenticate with its Main Worker. Contact a server"
                                + " administrator.");
                    case PROGRAM_ERROR ->
                        Component.literal("Your Cobbleware program could not provide start states: " + result.detail());
                    case UNAVAILABLE ->
                        Component.literal("Cobbleware could not reach its Main Worker. Try again shortly.");
                    case SUCCESS -> throw new IllegalArgumentException("successful program result is not a failure");
                };
        player.sendSystemMessage(message);
    }

    private static int pause(CommandContext<CommandSourceStack> context, ExecutionDriver executionDriver) {
        return changeExecutionState(context, executionDriver, CobbleputerState.RUNNING);
    }

    private static int resume(CommandContext<CommandSourceStack> context, ExecutionDriver executionDriver) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.literal("Only a player can change a Cobbleware execution's state."));
            return 0;
        }

        Optional<CobbleputerTarget> target = findNearestCobbleputer(player);
        if (target.isEmpty()) {
            player.sendSystemMessage(Component.literal("No Cobbleputer is within 3m."));
            return 0;
        }

        CobbleputerTarget resolvedTarget = target.orElseThrow();
        CobbleputerState lifecycleState = resolvedTarget.state().getValue(CobbleputerBlock.STATE);
        if (lifecycleState != CobbleputerState.STOPPED && lifecycleState != CobbleputerState.CRASHED) {
            player.sendSystemMessage(Component.literal("The nearest Cobbleputer is not stopped or crashed."));
            return 0;
        }
        if (!player.getUUID()
                .equals(resolvedTarget.blockEntity().ownerPlayerId().orElse(null))) {
            player.sendSystemMessage(
                    Component.literal("You can only change the state of your own Cobbleputer execution."));
            return 0;
        }
        if (!(resolvedTarget.level() instanceof ServerLevel serverLevel)) {
            player.sendSystemMessage(Component.literal("Cobbleware executions can only change state on the server."));
            return 0;
        }

        ExecutionDriver.ExecutionInitResult activation;
        if (lifecycleState == CobbleputerState.STOPPED) {
            activation = executionDriver.resume(
                    ExecutionDriver.minecraft(serverLevel.getServer()), resolvedTarget.blockEntity());
        } else {
            activation = executionDriver.restartCrashedExecution(
                    ExecutionDriver.minecraft(serverLevel.getServer()), resolvedTarget.blockEntity());
        }
        if (activation == ExecutionDriver.ExecutionInitResult.LIMIT_REACHED) {
            sendRunningExecutionLimitReached(player, executionDriver.maxRunningExecutionsPerPlayer());
            return 0;
        }
        if (activation != ExecutionDriver.ExecutionInitResult.SUCCESS) {
            player.sendSystemMessage(Component.literal("The nearest Cobbleputer could not be resumed."));
            return 0;
        }
        if (lifecycleState == CobbleputerState.STOPPED) {
            player.sendSystemMessage(Component.literal("Resumed your Cobbleputer execution."));
        } else {
            player.sendSystemMessage(Component.literal("Restarted your crashed Cobbleputer with its saved state."));
        }
        return 1;
    }

    private static int changeExecutionState(
            CommandContext<CommandSourceStack> context,
            ExecutionDriver executionDriver,
            CobbleputerState requiredState) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (CommandSyntaxException exception) {
            source.sendFailure(Component.literal("Only a player can change a Cobbleware execution's state."));
            return 0;
        }

        Optional<CobbleputerTarget> target = findNearestCobbleputer(player);
        if (target.isEmpty()) {
            player.sendSystemMessage(Component.literal("No Cobbleputer is within 3m."));
            return 0;
        }

        CobbleputerTarget resolvedTarget = target.orElseThrow();
        if (resolvedTarget.state().getValue(CobbleputerBlock.STATE) != requiredState) {
            String requiredStateName = requiredState == CobbleputerState.RUNNING ? "running" : "stopped";
            player.sendSystemMessage(Component.literal("The nearest Cobbleputer is not " + requiredStateName + "."));
            return 0;
        }
        if (!player.getUUID()
                .equals(resolvedTarget.blockEntity().ownerPlayerId().orElse(null))) {
            player.sendSystemMessage(
                    Component.literal("You can only change the state of your own Cobbleputer execution."));
            return 0;
        }
        if (!(resolvedTarget.level() instanceof ServerLevel serverLevel)) {
            player.sendSystemMessage(Component.literal("Cobbleware executions can only change state on the server."));
            return 0;
        }

        if (requiredState == CobbleputerState.RUNNING) {
            executionDriver.stop(ExecutionDriver.minecraft(serverLevel.getServer()), resolvedTarget.blockEntity());
            player.sendSystemMessage(Component.literal("Paused your Cobbleputer execution."));
        } else {
            executionDriver.resume(ExecutionDriver.minecraft(serverLevel.getServer()), resolvedTarget.blockEntity());
            player.sendSystemMessage(Component.literal("Resumed your Cobbleputer execution."));
        }
        return 1;
    }

    private static Optional<CobbleputerTarget> findNearestCobbleputer(ServerPlayer player) {
        Level level = player.level();
        Vec3 playerPosition = player.position();
        BlockPos min = BlockPos.containing(
                playerPosition.x - EXECUTION_RANGE,
                playerPosition.y - EXECUTION_RANGE,
                playerPosition.z - EXECUTION_RANGE);
        BlockPos max = BlockPos.containing(
                playerPosition.x + EXECUTION_RANGE,
                playerPosition.y + EXECUTION_RANGE,
                playerPosition.z + EXECUTION_RANGE);
        CobbleputerTarget closest = null;
        double closestDistanceSquared = EXECUTION_RANGE * EXECUTION_RANGE;

        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!state.is(CobblewareBlocks.COBBLEPUTER)) {
                continue;
            }
            double distanceSquared = playerPosition.distanceToSqr(Vec3.atCenterOf(pos));
            if (distanceSquared > closestDistanceSquared) {
                continue;
            }
            if (!(level.getBlockEntity(pos) instanceof CobbleputerBlockEntity blockEntity)) {
                continue;
            }
            closest = new CobbleputerTarget(level, pos.immutable(), state, blockEntity);
            closestDistanceSquared = distanceSquared;
        }
        return Optional.ofNullable(closest);
    }

    private static void sendRunningExecutionLimitReached(ServerPlayer player, int limit) {
        player.sendSystemMessage(
                Component.literal("You have reached the running-executions-per-player limit of " + limit + "."));
    }

    private static String formatProgram(MainWorkerClient.ProgramInfo program) {
        String startStates = program.startStateNames().isEmpty()
                ? "(none)"
                : program.startStateNames().stream().collect(Collectors.joining(", "));
        return "Cobbleware program: " + program.name()
                + "\nID: " + program.id()
                + "\nSize: " + program.sizeBytes() + " bytes"
                + "\nUploaded: " + program.uploadedAt()
                + "\nStart states: " + startStates;
    }

    private record CobbleputerTarget(Level level, BlockPos pos, BlockState state, CobbleputerBlockEntity blockEntity) {}
}
