package io.github.mmcgill.cobbleware.execution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cobbleware.effects.Effect;
import cobbleware.effects.SendChat;
import cobbleware.effects.SetRedstonePower;
import cobbleware.events.ChatMessage;
import cobbleware.events.Event;
import cobbleware.internal.ExecutionFailureReason;
import cobbleware.internal.ExecutionResult;
import cobbleware.internal.ExecutionState;
import cobbleware.internal.ExecutionTransition;
import cobbleware.internal.ExecutionsRequest;
import cobbleware.internal.ExecutionsResponse;
import com.google.protobuf.ByteString;
import io.github.mmcgill.cobbleware.api.MainWorker;
import io.github.mmcgill.cobbleware.block.CobbleputerBlock;
import io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity;
import io.github.mmcgill.cobbleware.block.CobbleputerState;
import io.github.mmcgill.cobbleware.block.WirelessObserverBlockEntity;
import io.github.mmcgill.cobbleware.instrumentation.Instrumentation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ExecutionDriverTest {
    private static BlockEntityType<?> testCobbleputerBlockEntity;
    private static BlockEntityType<?> testObserverBlockEntity;
    private static BlockState uninitializedState;
    private static BlockState runningState;
    private static BlockState stoppedState;
    private static BlockState crashedState;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        testCobbleputerBlockEntity =
                BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("chest"));
        testObserverBlockEntity =
                BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("dispenser"));
        uninitializedState = lifecycleState(CobbleputerState.UNINITIALIZED);
        runningState = lifecycleState(CobbleputerState.RUNNING);
        stoppedState = lifecycleState(CobbleputerState.STOPPED);
        crashedState = lifecycleState(CobbleputerState.CRASHED);
    }

    private static BlockState lifecycleState(CobbleputerState lifecycleState) {
        return new BlockState(
                Blocks.CHEST, new Property<?>[] {CobbleputerBlock.STATE}, new Comparable<?>[] {lifecycleState});
    }

    private static BlockState lifecycleStateFor(CobbleputerState lifecycleState) {
        return switch (lifecycleState) {
            case UNINITIALIZED -> uninitializedState;
            case RUNNING -> runningState;
            case STOPPED -> stoppedState;
            case CRASHED -> crashedState;
        };
    }

    @Test
    void blockInfoContainsTheRegistryIdentifierAndAllStateProperties() {
        BlockState state = Blocks.OAK_DOOR.defaultBlockState();

        cobbleware.events.BlockInfo blockInfo = ExecutionDriver.protoBlockInfo(state);

        assertEquals("minecraft", blockInfo.getBlockType().getNs());
        assertEquals("oak_door", blockInfo.getBlockType().getPath());
        assertEquals("north", blockInfo.getPropertiesOrThrow("facing"));
        assertEquals("lower", blockInfo.getPropertiesOrThrow("half"));
        assertEquals("false", blockInfo.getPropertiesOrThrow("open"));
        assertEquals(state.getProperties().size(), blockInfo.getPropertiesCount());
    }

    @Test
    void mapsEverySpecifiedProtoDyeColorAndRejectsInvalidSelectors() {
        Map<cobbleware.common.DyeColor, DyeColor> expected = Map.ofEntries(
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_WHITE, DyeColor.WHITE),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_ORANGE, DyeColor.ORANGE),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_MAGENTA, DyeColor.MAGENTA),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_LIGHT_BLUE, DyeColor.LIGHT_BLUE),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_YELLOW, DyeColor.YELLOW),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_LIME, DyeColor.LIME),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_PINK, DyeColor.PINK),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_GRAY, DyeColor.GRAY),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_LIGHT_GRAY, DyeColor.LIGHT_GRAY),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_CYAN, DyeColor.CYAN),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_PURPLE, DyeColor.PURPLE),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_BLUE, DyeColor.BLUE),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_BROWN, DyeColor.BROWN),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_GREEN, DyeColor.GREEN),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_RED, DyeColor.RED),
                Map.entry(cobbleware.common.DyeColor.DYE_COLOR_BLACK, DyeColor.BLACK));

        for (Map.Entry<cobbleware.common.DyeColor, DyeColor> entry : expected.entrySet()) {
            assertEquals(Optional.of(entry.getValue()), ExecutionDriver.minecraftDyeColor(entry.getKey()));
        }
        assertEquals(
                Optional.empty(), ExecutionDriver.minecraftDyeColor(cobbleware.common.DyeColor.DYE_COLOR_UNSPECIFIED));
        assertEquals(Optional.empty(), ExecutionDriver.minecraftDyeColor(cobbleware.common.DyeColor.UNRECOGNIZED));
    }

    @Test
    void rejectedEventsForStoppedCobbleputersAreNotCountedAsDropped() {
        assertEquals(false, ExecutionDriver.countsRejectedEventAsDropped(CobbleputerState.STOPPED));
        assertEquals(true, ExecutionDriver.countsRejectedEventAsDropped(CobbleputerState.UNINITIALIZED));
        assertEquals(true, ExecutionDriver.countsRejectedEventAsDropped(CobbleputerState.RUNNING));
        assertEquals(true, ExecutionDriver.countsRejectedEventAsDropped(CobbleputerState.CRASHED));
    }

    @Test
    void limitsQueuedEventsAcrossOnePlayersCobbleputers() {
        Fixture fixture = new Fixture(32, 2);
        UUID owner = UUID.randomUUID();
        CobbleputerBlockEntity first = fixture.startNewExecution(owner, new byte[] {1});
        CobbleputerBlockEntity second = fixture.startNewExecution(owner, new byte[] {2});

        fixture.driver.enqueueEvent(
                first,
                Event.newBuilder()
                        .setChatMessage(ChatMessage.newBuilder().setMessage("dropped"))
                        .build());

        assertEquals(2, first.nextEventSequenceNum());
        assertEquals(2, second.nextEventSequenceNum());
    }

    @Test
    void givesEachPlayerAnIndependentQueuedEventLimit() {
        Fixture fixture = new Fixture(32, 1);
        CobbleputerBlockEntity first = fixture.startNewExecution(UUID.randomUUID(), new byte[] {1});
        CobbleputerBlockEntity second = fixture.startNewExecution(UUID.randomUUID(), new byte[] {2});

        assertEquals(2, first.nextEventSequenceNum());
        assertEquals(2, second.nextEventSequenceNum());
    }

    @Test
    void retainedStoppedEventsContinueToUseTheOwnersQueuedEventLimit() {
        Fixture fixture = new Fixture(32, 1);
        UUID owner = UUID.randomUUID();
        CobbleputerBlockEntity stopped = fixture.startNewExecution(owner, new byte[] {1});
        fixture.driver.stop(fixture.minecraft, stopped);
        CobbleputerBlockEntity running = fixture.startNewExecution(owner, new byte[] {2});

        fixture.driver.enqueueEvent(
                stopped,
                Event.newBuilder()
                        .setChatMessage(ChatMessage.newBuilder().setMessage("ignored"))
                        .build());

        assertEquals(2, stopped.nextEventSequenceNum());
        assertEquals(1, running.nextEventSequenceNum());
    }

    @Test
    void deactivationFreesTheOwnersQueuedEventCapacity() {
        Fixture fixture = new Fixture(32, 1);
        UUID owner = UUID.randomUUID();
        CobbleputerBlockEntity first = fixture.startNewExecution(owner, new byte[] {1});
        fixture.driver.deactivate(first);
        CobbleputerBlockEntity second = fixture.startNewExecution(owner, new byte[] {2});

        assertEquals(2, second.nextEventSequenceNum());
    }

    @Test
    void consumingEventsFreesTheOwnersQueuedEventCapacity() {
        Fixture fixture = new Fixture(32, 1);
        UUID owner = UUID.randomUUID();
        CobbleputerBlockEntity cobbleputer = fixture.startNewExecution(owner, new byte[] {1});
        CompletableFuture<MainWorker.ExecutionSyncResult> first = fixture.worker.enqueueSyncResult();
        CompletableFuture<MainWorker.ExecutionSyncResult> second = fixture.worker.enqueueSyncResult();
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();
        ExecutionsRequest firstRequest = fixture.worker.syncRequests.getFirst();
        first.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.newBuilder()
                .addResults(resultFor(execution(firstRequest, 0), 1, new byte[] {2}))
                .build()));
        fixture.serverTick();

        fixture.driver.enqueueEvent(
                cobbleputer,
                Event.newBuilder()
                        .setChatMessage(ChatMessage.newBuilder().setMessage("accepted"))
                        .build());
        second.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.newBuilder()
                .addResults(resultFor(execution(fixture.worker.syncRequests.get(1), 0), 1, new byte[] {2}))
                .build()));
        fixture.serverTick();

        assertEquals(
                Event.EventCase.CHAT_MESSAGE,
                execution(fixture.worker.syncRequests.get(2), 0).getEvents(0).getEventCase());
    }

    @Test
    void offersEveryPendingEventForOneExecutionSync() {
        Fixture fixture = new Fixture(32, 200);
        CobbleputerBlockEntity cobbleputer = fixture.startNewExecution(new byte[] {1});
        for (int index = 1; index < 200; index += 1) {
            fixture.driver.enqueueEvent(
                    cobbleputer,
                    Event.newBuilder()
                            .setChatMessage(ChatMessage.newBuilder().setMessage("event " + index))
                            .build());
        }
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();

        assertEquals(200, execution(fixture.worker.syncRequests.getFirst(), 0).getEventsCount());
    }

    @Test
    void groupsGuaranteedExecutionsByPlayerAndSuppliesSyncLimits() {
        Fixture fixture = new Fixture(32);
        UUID owner = UUID.randomUUID();
        fixture.startNewExecution(owner, new byte[] {1});
        fixture.startNewExecution(owner, new byte[] {2});
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();

        ExecutionsRequest request = fixture.worker.syncRequests.getFirst();
        assertEquals(10, request.getWorkerLimit());
        assertEquals(1, request.getPlayersCount());
        assertEquals(
                owner.getMostSignificantBits(),
                request.getPlayers(0).getPlayerId().getHigh());
        assertEquals(
                owner.getLeastSignificantBits(),
                request.getPlayers(0).getPlayerId().getLow());
        assertEquals(200, request.getPlayers(0).getOutstandingEffectLimit());
        assertEquals(2, request.getPlayers(0).getExecutionsCount());
        assertEquals(20, request.getPlayers(0).getExecutions(0).getOutboxLimit());
    }

    @Test
    void appliesResultsInGroupedRequestOrderForInterleavedOwners() {
        Fixture fixture = new Fixture(32);
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        List<CobbleputerBlockEntity> cobbleputers = new ArrayList<>();
        for (int index = 0; index < 10; index += 1) {
            cobbleputers.add(fixture.startNewExecution(firstOwner, new byte[] {(byte) index}));
            cobbleputers.add(fixture.startNewExecution(secondOwner, new byte[] {(byte) index}));
        }
        CompletableFuture<MainWorker.ExecutionSyncResult> first = fixture.worker.enqueueSyncResult();
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();

        ExecutionsRequest request = fixture.worker.syncRequests.getFirst();
        assertEquals(2, request.getPlayersCount());
        assertEquals(20, executionCount(request));
        Map<UUID, byte[]> expectedStates = new HashMap<>();
        ExecutionsResponse.Builder response = ExecutionsResponse.newBuilder();
        for (int index = 0; index < executionCount(request); index += 1) {
            ExecutionState execution = execution(request, index);
            byte[] finalState = new byte[] {(byte) (index + 20)};
            expectedStates.put(
                    new UUID(
                            execution.getExecutionId().getHigh(),
                            execution.getExecutionId().getLow()),
                    finalState);
            response.addResults(resultFor(execution, 1, finalState));
        }
        first.complete(MainWorker.ExecutionSyncResult.success(response.build()));

        fixture.serverTick();

        for (CobbleputerBlockEntity cobbleputer : cobbleputers) {
            assertArrayEquals(
                    expectedStates.get(cobbleputer.executionId().orElseThrow()),
                    cobbleputer.executionState().orElseThrow());
        }
    }

    @Test
    void syncsOnlyGuaranteedPoolMembersWhenBestEffortIsDisabled() {
        Fixture fixture = new Fixture(32, 200, 1);
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        fixture.startNewExecution(firstOwner, new byte[] {1});
        fixture.startNewExecution(secondOwner, new byte[] {2});
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();

        ExecutionsRequest request = fixture.worker.syncRequests.getFirst();
        UUID guaranteedOwner =
                fixture.executionPoolManager.guaranteedMembers().getFirst().playerId();
        assertEquals(1, request.getPlayersCount());
        assertEquals(
                guaranteedOwner.getMostSignificantBits(),
                request.getPlayers(0).getPlayerId().getHigh());
        assertEquals(
                guaranteedOwner.getLeastSignificantBits(),
                request.getPlayers(0).getPlayerId().getLow());
        assertEquals(1, executionCount(request));
    }

    @Test
    void syncsGuaranteedAndActiveBestEffortPoolMembers() {
        Fixture fixture = new Fixture(32, 200, 1, 1);
        UUID guaranteedOwner = UUID.randomUUID();
        UUID bestEffortOwner = UUID.randomUUID();
        UUID thirdOwner = UUID.randomUUID();
        fixture.startNewExecution(guaranteedOwner, new byte[] {1});
        fixture.startNewExecution(bestEffortOwner, new byte[] {2});
        fixture.startNewExecution(thirdOwner, new byte[] {3});
        fixture.eligiblePlayers.put(
                guaranteedOwner, new ExecutionPoolManager.EligiblePlayer(guaranteedOwner, "guaranteed", 1, 0));
        fixture.eligiblePlayers.put(
                bestEffortOwner, new ExecutionPoolManager.EligiblePlayer(bestEffortOwner, "best-effort", 2, 0));
        fixture.eligiblePlayers.put(thirdOwner, new ExecutionPoolManager.EligiblePlayer(thirdOwner, "queued", 3, 0));
        fixture.refreshAndAdvancePools();
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();

        ExecutionsRequest request = fixture.worker.syncRequests.getFirst();
        UUID activeBestEffortOwner =
                fixture.executionPoolManager.bestEffortMembers().getFirst().playerId();
        UUID queuedAfterAdmission = activeBestEffortOwner.equals(bestEffortOwner) ? thirdOwner : bestEffortOwner;
        assertEquals(2, request.getWorkerLimit());
        assertEquals(2, request.getPlayersCount());
        assertEquals(2, executionCount(request));
        assertEquals(
                ExecutionPoolManager.Pool.GUARANTEED,
                fixture.executionPoolManager.poolFor(guaranteedOwner).orElseThrow());
        assertEquals(
                ExecutionPoolManager.Pool.BEST_EFFORT,
                fixture.executionPoolManager.poolFor(activeBestEffortOwner).orElseThrow());
        assertTrue(fixture.executionPoolManager.poolFor(queuedAfterAdmission).isEmpty());
        executionForOwner(request, activeBestEffortOwner);
        assertFalse(request.getPlayersList().stream()
                .anyMatch(player -> player.getPlayerId().getHigh() == queuedAfterAdmission.getMostSignificantBits()
                        && player.getPlayerId().getLow() == queuedAfterAdmission.getLeastSignificantBits()));
    }

    @Test
    void publishesPoolTransitionsForAssignmentChangesAndIneligibility() {
        Fixture fixture = new Fixture(32, 200, 1, 1);
        UUID guaranteedOwner = UUID.randomUUID();
        UUID bestEffortOwner = UUID.randomUUID();
        UUID waitingOwner = UUID.randomUUID();
        fixture.startNewExecution(guaranteedOwner, new byte[] {1});
        fixture.startNewExecution(bestEffortOwner, new byte[] {2});
        fixture.startNewExecution(waitingOwner, new byte[] {3});
        fixture.eligiblePlayers.put(
                guaranteedOwner, new ExecutionPoolManager.EligiblePlayer(guaranteedOwner, "guaranteed", 1, 0));
        fixture.eligiblePlayers.put(
                bestEffortOwner, new ExecutionPoolManager.EligiblePlayer(bestEffortOwner, "best-effort", 2, 0));
        fixture.eligiblePlayers.put(
                waitingOwner, new ExecutionPoolManager.EligiblePlayer(waitingOwner, "waiting", 3, 0));

        fixture.refreshAndAdvancePools();

        UUID activeBestEffortOwner =
                fixture.executionPoolManager.bestEffortMembers().getFirst().playerId();
        UUID waitingAfterAssignment = activeBestEffortOwner.equals(bestEffortOwner) ? waitingOwner : bestEffortOwner;
        assertEquals(3, fixture.worker.poolTransitions.size());
        assertTrue(fixture.worker.poolTransitions.contains(new CapturingMainWorker.PoolTransitionReport(
                guaranteedOwner, cobbleware.internal.ExecutionPoolTransition.EXECUTION_POOL_TRANSITION_GUARANTEED)));
        assertTrue(fixture.worker.poolTransitions.contains(new CapturingMainWorker.PoolTransitionReport(
                activeBestEffortOwner,
                cobbleware.internal.ExecutionPoolTransition.EXECUTION_POOL_TRANSITION_BESTEFFORT)));
        assertTrue(fixture.worker.poolTransitions.contains(new CapturingMainWorker.PoolTransitionReport(
                waitingAfterAssignment,
                cobbleware.internal.ExecutionPoolTransition.EXECUTION_POOL_TRANSITION_WAITING)));

        fixture.refreshAndAdvancePools();
        assertEquals(3, fixture.worker.poolTransitions.size());

        fixture.eligiblePlayers.remove(waitingAfterAssignment);
        fixture.refreshAndAdvancePools();
        assertEquals(
                new CapturingMainWorker.PoolTransitionReport(
                        waitingAfterAssignment,
                        cobbleware.internal.ExecutionPoolTransition.EXECUTION_POOL_TRANSITION_NONE),
                fixture.worker.poolTransitions.getLast());

        fixture.refreshAndAdvancePools();
        assertEquals(4, fixture.worker.poolTransitions.size());

        fixture.eligiblePlayers.put(
                waitingAfterAssignment,
                new ExecutionPoolManager.EligiblePlayer(waitingAfterAssignment, "returning", 3, 0));
        fixture.refreshAndAdvancePools();
        assertEquals(
                new CapturingMainWorker.PoolTransitionReport(
                        waitingAfterAssignment,
                        cobbleware.internal.ExecutionPoolTransition.EXECUTION_POOL_TRANSITION_WAITING),
                fixture.worker.poolTransitions.getLast());
    }

    @Test
    void appliesAQueuedBestEffortResponseAfterItsOwnerRotatesOut() {
        Fixture fixture = new Fixture(32, 200, 1, 1);
        UUID guaranteedOwner = UUID.randomUUID();
        UUID firstBestEffortCandidate = UUID.randomUUID();
        UUID secondBestEffortCandidate = UUID.randomUUID();
        fixture.startNewExecution(guaranteedOwner, new byte[] {1});
        CobbleputerBlockEntity firstCandidate = fixture.startNewExecution(firstBestEffortCandidate, new byte[] {2});
        CobbleputerBlockEntity secondCandidate = fixture.startNewExecution(secondBestEffortCandidate, new byte[] {3});
        fixture.eligiblePlayers.put(
                guaranteedOwner, new ExecutionPoolManager.EligiblePlayer(guaranteedOwner, "guaranteed", 1, 0));
        fixture.eligiblePlayers.put(
                firstBestEffortCandidate,
                new ExecutionPoolManager.EligiblePlayer(firstBestEffortCandidate, "first", 2, 0));
        fixture.eligiblePlayers.put(
                secondBestEffortCandidate,
                new ExecutionPoolManager.EligiblePlayer(secondBestEffortCandidate, "second", 3, 0));
        fixture.refreshAndAdvancePools();
        CompletableFuture<MainWorker.ExecutionSyncResult> first = fixture.worker.enqueueSyncResult();
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();
        ExecutionsRequest request = fixture.worker.syncRequests.getFirst();
        UUID rotatedOwner =
                fixture.executionPoolManager.bestEffortMembers().getFirst().playerId();
        CobbleputerBlockEntity rotated =
                rotatedOwner.equals(firstBestEffortCandidate) ? firstCandidate : secondCandidate;
        ExecutionState rotatedExecution = executionForOwner(request, rotatedOwner);
        for (int tick = 0; tick < ExecutionPoolManager.BEST_EFFORT_POOL_DURATION_TICKS; tick += 1) {
            fixture.executionPoolManager.advanceBestEffortPool();
        }
        assertTrue(fixture.executionPoolManager.poolFor(rotatedOwner).isEmpty());
        assertFalse(fixture.executionPoolManager
                .bestEffortMembers()
                .getFirst()
                .playerId()
                .equals(rotatedOwner));

        ExecutionsResponse.Builder response = ExecutionsResponse.newBuilder();
        for (int index = 0; index < executionCount(request); index += 1) {
            ExecutionState offered = execution(request, index);
            ExecutionResult result = resultFor(offered, 1, new byte[] {9});
            if (offered.equals(rotatedExecution)) {
                result = result.toBuilder()
                        .addEffects(Effect.newBuilder()
                                .setSendChat(SendChat.newBuilder().setMsg("rotated"))
                                .build())
                        .build();
            }
            response.addResults(result);
        }
        first.complete(MainWorker.ExecutionSyncResult.success(response.build()));

        fixture.serverTick();

        assertArrayEquals(new byte[] {9}, rotated.executionState().orElseThrow());
        assertEquals(List.of("rotated"), fixture.minecraft.nearbyMessages);
    }

    @Test
    void omitsDemotedPlayersFromTheNextAuthoritativeSnapshot() {
        Fixture fixture = new Fixture(32, 200, 1);
        UUID firstOwner = UUID.randomUUID();
        CobbleputerBlockEntity first = fixture.startNewExecution(firstOwner, new byte[] {1});
        CompletableFuture<MainWorker.ExecutionSyncResult> firstResult = fixture.worker.enqueueSyncResult();
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();
        ExecutionsRequest firstRequest = fixture.worker.syncRequests.getFirst();

        UUID secondOwner = UUID.randomUUID();
        fixture.startNewExecution(secondOwner, new byte[] {2});
        fixture.eligiblePlayers.put(firstOwner, new ExecutionPoolManager.EligiblePlayer(firstOwner, "first", 3, 0));
        fixture.eligiblePlayers.put(secondOwner, new ExecutionPoolManager.EligiblePlayer(secondOwner, "second", 1, 0));
        fixture.executionPoolManager.refresh(fixture.eligiblePlayers.values());
        firstResult.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.newBuilder()
                .addResults(resultFor(execution(firstRequest, 0), 1, new byte[] {3}))
                .build()));

        fixture.serverTick();

        ExecutionsRequest secondRequest = fixture.worker.syncRequests.get(1);
        assertEquals(
                secondOwner.getMostSignificantBits(),
                secondRequest.getPlayers(0).getPlayerId().getHigh());
        assertEquals(
                secondOwner.getLeastSignificantBits(),
                secondRequest.getPlayers(0).getPlayerId().getLow());
        assertArrayEquals(new byte[] {3}, first.executionState().orElseThrow());
    }

    @Test
    void serializesOneFlightAndRetainsEventsQueuedAfterDispatch() {
        Fixture fixture = new Fixture(32);
        CobbleputerBlockEntity cobbleputer = fixture.startNewExecution(new byte[] {1});
        CompletableFuture<MainWorker.ExecutionSyncResult> first = fixture.worker.enqueueSyncResult();
        CompletableFuture<MainWorker.ExecutionSyncResult> second = fixture.worker.enqueueSyncResult();

        fixture.serverTick();

        ExecutionsRequest firstRequest = fixture.worker.syncRequests.getFirst();
        assertEquals(1, executionCount(firstRequest));
        assertEquals(1, execution(firstRequest, 0).getEventSeqNum());
        assertEquals(
                Event.EventCase.STARTED, execution(firstRequest, 0).getEvents(0).getEventCase());

        fixture.driver.enqueueEvent(
                cobbleputer,
                Event.newBuilder()
                        .setChatMessage(ChatMessage.newBuilder().setMessage("after dispatch"))
                        .build());
        fixture.serverTick();

        assertEquals(1, fixture.worker.syncRequests.size());

        first.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.newBuilder()
                .addResults(resultFor(execution(firstRequest, 0), 1, new byte[] {9}))
                .build()));
        fixture.serverTick();

        assertEquals(2, fixture.worker.syncRequests.size());
        assertEquals(2, execution(fixture.worker.syncRequests.get(1), 0).getEventSeqNum());
        assertEquals(
                Event.EventCase.CHAT_MESSAGE,
                execution(fixture.worker.syncRequests.get(1), 0).getEvents(0).getEventCase());
        assertEquals(
                "after dispatch",
                fixture.worker
                        .syncRequests
                        .get(1)
                        .getPlayers(0)
                        .getExecutions(0)
                        .getEvents(0)
                        .getChatMessage()
                        .getMessage());
        assertEquals(
                ByteString.copyFrom(new byte[] {9}),
                execution(fixture.worker.syncRequests.get(1), 0).getInitialState());
        assertEquals(3, cobbleputer.nextEventSequenceNum());
        assertFalse(second.isDone());
    }

    @Test
    void discardsTheEntireResponseWhenItDoesNotCoverEveryOfferedExecution() {
        Fixture fixture = new Fixture(32);
        CobbleputerBlockEntity firstCobbleputer = fixture.startNewExecution(new byte[] {1});
        CobbleputerBlockEntity secondCobbleputer = fixture.startNewExecution(new byte[] {2});
        CompletableFuture<MainWorker.ExecutionSyncResult> first = fixture.worker.enqueueSyncResult();
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();

        ExecutionsRequest request = fixture.worker.syncRequests.getFirst();
        assertEquals(2, executionCount(request));
        first.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.newBuilder()
                .addResults(resultFor(execution(request, 0), 1, new byte[] {8}))
                .build()));
        fixture.serverTick();

        assertArrayEquals(new byte[] {1}, firstCobbleputer.executionState().orElseThrow());
        assertArrayEquals(new byte[] {2}, secondCobbleputer.executionState().orElseThrow());
        assertEquals(2, fixture.worker.syncRequests.size());
        assertEquals(2, executionCount(fixture.worker.syncRequests.get(1)));
    }

    @Test
    void discardsTheEntireResponseWhenResultsAreOutOfRequestOrder() {
        Fixture fixture = new Fixture(32);
        CobbleputerBlockEntity firstCobbleputer = fixture.startNewExecution(new byte[] {1});
        CobbleputerBlockEntity secondCobbleputer = fixture.startNewExecution(new byte[] {2});
        CompletableFuture<MainWorker.ExecutionSyncResult> first = fixture.worker.enqueueSyncResult();
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();

        ExecutionsRequest request = fixture.worker.syncRequests.getFirst();
        first.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.newBuilder()
                .addResults(resultFor(execution(request, 1), 1, new byte[] {8}))
                .addResults(resultFor(execution(request, 0), 1, new byte[] {9}))
                .build()));
        fixture.serverTick();

        assertArrayEquals(new byte[] {1}, firstCobbleputer.executionState().orElseThrow());
        assertArrayEquals(new byte[] {2}, secondCobbleputer.executionState().orElseThrow());
        assertEquals(2, fixture.worker.syncRequests.size());
    }

    @Test
    void sendsOneEmptySnapshotAfterTheLastExecutionIsDeactivated() {
        Fixture fixture = new Fixture(32);
        CobbleputerBlockEntity cobbleputer = fixture.startNewExecution(new byte[] {1});
        CompletableFuture<MainWorker.ExecutionSyncResult> first = fixture.worker.enqueueSyncResult();
        CompletableFuture<MainWorker.ExecutionSyncResult> empty = fixture.worker.enqueueSyncResult();

        fixture.serverTick();
        ExecutionsRequest request = fixture.worker.syncRequests.getFirst();
        fixture.driver.deactivate(cobbleputer);
        first.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.newBuilder()
                .addResults(resultFor(execution(request, 0), 1, new byte[] {2}))
                .build()));

        fixture.serverTick();
        assertEquals(2, fixture.worker.syncRequests.size());
        assertEquals(0, executionCount(fixture.worker.syncRequests.get(1)));

        empty.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.getDefaultInstance()));
        fixture.serverTick();

        assertEquals(2, fixture.worker.syncRequests.size());
    }

    @Test
    void crashesRatherThanPersistingAStateThatExceedsTheLocalLimit() {
        Fixture fixture = new Fixture(1);
        CobbleputerBlockEntity cobbleputer = fixture.startNewExecution(new byte[] {1});
        CompletableFuture<MainWorker.ExecutionSyncResult> first = fixture.worker.enqueueSyncResult();
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();
        ExecutionsRequest request = fixture.worker.syncRequests.getFirst();
        first.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.newBuilder()
                .addResults(resultFor(execution(request, 0), 1, new byte[] {2, 3}))
                .build()));

        fixture.serverTick();

        assertEquals(CobbleputerState.CRASHED, cobbleputer.getBlockState().getValue(CobbleputerBlock.STATE));
        assertTrue(cobbleputer.executionId().isEmpty());
        assertArrayEquals(new byte[] {1}, cobbleputer.executionState().orElseThrow());
        assertEquals(List.of("TotalExecutionStateSize"), fixture.worker.limitReasons);
        assertEquals(
                List.of(
                        ExecutionTransition.EXECUTION_TRANSITION_STARTED,
                        ExecutionTransition.EXECUTION_TRANSITION_CRASHED),
                fixture.worker.transitions);
    }

    @Test
    void crashesWhenTheRuntimeReportsAnExecutionFailure() {
        Fixture fixture = new Fixture(32);
        CobbleputerBlockEntity cobbleputer = fixture.startNewExecution(new byte[] {1});
        CompletableFuture<MainWorker.ExecutionSyncResult> first = fixture.worker.enqueueSyncResult();
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();
        ExecutionState offered = execution(fixture.worker.syncRequests.getFirst(), 0);
        first.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.newBuilder()
                .addResults(resultFor(offered, 1, new byte[] {1}).toBuilder()
                        .setExecutionFailed(true)
                        .setFailureReason(ExecutionFailureReason.EXECUTION_FAILURE_REASON_STATE_SIZE_LIMIT)
                        .build())
                .build()));
        fixture.serverTick();

        assertEquals(CobbleputerState.CRASHED, cobbleputer.getBlockState().getValue(CobbleputerBlock.STATE));
        assertEquals(List.of("TotalExecutionStateSize"), fixture.worker.limitReasons);
    }

    @Test
    void crashesWhenTheRuntimeReportsAnOversizedStepResponse() {
        Fixture fixture = new Fixture(32);
        CobbleputerBlockEntity cobbleputer = fixture.startNewExecution(new byte[] {1});
        CompletableFuture<MainWorker.ExecutionSyncResult> first = fixture.worker.enqueueSyncResult();
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();
        ExecutionState offered = execution(fixture.worker.syncRequests.getFirst(), 0);
        assertEquals(32, fixture.worker.syncRequests.getFirst().getPlayers(0).getTotalExecutionStateLimit());
        first.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.newBuilder()
                .addResults(resultFor(offered, 1, new byte[] {1}).toBuilder()
                        .setExecutionFailed(true)
                        .setFailureReason(ExecutionFailureReason.EXECUTION_FAILURE_REASON_STEP_RESPONSE_SIZE_LIMIT)
                        .build())
                .build()));
        fixture.serverTick();

        assertEquals(CobbleputerState.CRASHED, cobbleputer.getBlockState().getValue(CobbleputerBlock.STATE));
        assertArrayEquals(new byte[] {1}, cobbleputer.executionState().orElseThrow());
        assertEquals(List.of("ProgramStepResponseSize"), fixture.worker.limitReasons);
    }

    @Test
    void appliesAndAcknowledgesEveryEffectInOrder() {
        Fixture fixture = new Fixture(32);
        CobbleputerBlockEntity cobbleputer = fixture.startNewExecution(new byte[] {1});
        CompletableFuture<MainWorker.ExecutionSyncResult> first = fixture.worker.enqueueSyncResult();
        fixture.worker.enqueueSyncResult();

        fixture.serverTick();
        ExecutionsRequest request = fixture.worker.syncRequests.getFirst();
        Effect firstEffect = Effect.newBuilder()
                .setSendChat(SendChat.newBuilder().setMsg("one"))
                .build();
        Effect secondEffect = Effect.newBuilder()
                .setSendChat(SendChat.newBuilder().setMsg("two"))
                .build();
        first.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.newBuilder()
                .addResults(resultFor(execution(request, 0), 1, new byte[] {2}).toBuilder()
                        .addEffects(firstEffect)
                        .addEffects(secondEffect)
                        .build())
                .build()));

        fixture.serverTick();

        assertEquals(List.of("one", "two"), fixture.minecraft.nearbyMessages);
        assertEquals(0, cobbleputer.effectSequenceNum());
    }

    @Test
    void dispatchesAnObserverEventProducedByAnEffectInTheSubsequentSync() {
        Fixture fixture = new Fixture(32);
        CobbleputerBlockEntity cobbleputer = fixture.startNewExecution(new byte[] {1});
        CompletableFuture<MainWorker.ExecutionSyncResult> first = fixture.worker.enqueueSyncResult();
        fixture.worker.enqueueSyncResult();
        fixture.minecraft.observedCobbleputer = Optional.of(cobbleputer);
        WirelessObserverBlockEntity observer = new WirelessObserverBlockEntity(
                testObserverBlockEntity, new BlockPos(10, 64, 0), Blocks.DISPENSER.defaultBlockState());
        observer.setPairedCobbleputer(GlobalPos.of(Level.OVERWORLD, cobbleputer.getBlockPos()));
        fixture.minecraft.observedStateChanges.addLast(observedStateChange(observer));
        fixture.minecraft.onSetRedstonePower = () -> fixture.driver.registerObserverChange(observer);

        fixture.serverTick();
        ExecutionsRequest firstRequest = fixture.worker.syncRequests.getFirst();
        Effect effect = Effect.newBuilder()
                .setSetRedstonePower(SetRedstonePower.newBuilder().setPowerLevel(1))
                .build();
        first.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.newBuilder()
                .addResults(resultFor(execution(firstRequest, 0), 1, new byte[] {2}).toBuilder()
                        .addEffects(effect)
                        .build())
                .build()));

        fixture.serverTick();

        ExecutionsRequest secondRequest = fixture.worker.syncRequests.get(1);
        assertEquals(1, execution(secondRequest, 0).getEventsCount());
        assertEquals(
                Event.EventCase.BLOCK_STATE_CHANGE,
                execution(secondRequest, 0).getEvents(0).getEventCase());
    }

    @Test
    void drainsObserverWorkOnEveryTickWhileExecutionSyncIsInFlight() {
        Fixture fixture = new Fixture(32);
        CobbleputerBlockEntity cobbleputer = fixture.startNewExecution(new byte[] {1});
        CompletableFuture<MainWorker.ExecutionSyncResult> first = fixture.worker.enqueueSyncResult();
        fixture.worker.enqueueSyncResult();
        fixture.minecraft.observedCobbleputer = Optional.of(cobbleputer);
        fixture.serverTick();
        ExecutionsRequest firstRequest = fixture.worker.syncRequests.getFirst();

        WirelessObserverBlockEntity initializedObserver = new WirelessObserverBlockEntity(
                testObserverBlockEntity, new BlockPos(10, 64, 0), Blocks.DISPENSER.defaultBlockState());
        WirelessObserverBlockEntity changedObserver = new WirelessObserverBlockEntity(
                testObserverBlockEntity, new BlockPos(11, 64, 0), Blocks.DISPENSER.defaultBlockState());
        changedObserver.setPairedCobbleputer(GlobalPos.of(Level.OVERWORLD, cobbleputer.getBlockPos()));

        fixture.driver.registerObserverInitialization(initializedObserver);
        fixture.minecraft.observedStateChanges.addLast(observedStateChange(changedObserver));
        fixture.driver.registerObserverChange(changedObserver);
        fixture.serverTick();

        fixture.minecraft.observedStateChanges.addLast(observedStateChange(changedObserver));
        fixture.driver.registerObserverChange(changedObserver);
        fixture.serverTick();

        assertEquals(List.of(initializedObserver), fixture.minecraft.initializedObservers);
        assertEquals(List.of(changedObserver, changedObserver), fixture.minecraft.changedObservers);
        assertEquals(1, fixture.worker.syncRequests.size());

        first.complete(MainWorker.ExecutionSyncResult.success(ExecutionsResponse.newBuilder()
                .addResults(resultFor(execution(firstRequest, 0), 1, new byte[] {2}))
                .build()));
        fixture.serverTick();

        ExecutionsRequest secondRequest = fixture.worker.syncRequests.get(1);
        assertEquals(2, execution(secondRequest, 0).getEventsCount());
        assertEquals(
                Event.EventCase.BLOCK_STATE_CHANGE,
                execution(secondRequest, 0).getEvents(0).getEventCase());
        assertEquals(
                Event.EventCase.BLOCK_STATE_CHANGE,
                execution(secondRequest, 0).getEvents(1).getEventCase());
    }

    private static WirelessObserverBlockEntity.ObservedBlockStateChange observedStateChange(
            WirelessObserverBlockEntity observer) {
        return new WirelessObserverBlockEntity.ObservedBlockStateChange(
                observer.observedBlockPos(), Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState());
    }

    private static ExecutionResult resultFor(ExecutionState offered, long consumedEventSequence, byte[] finalState) {
        return ExecutionResult.newBuilder()
                .setExecutionId(offered.getExecutionId())
                .setFinalState(ByteString.copyFrom(finalState))
                .setEventSeqNum(consumedEventSequence)
                .build();
    }

    private static int executionCount(ExecutionsRequest request) {
        return request.getPlayersList().stream()
                .mapToInt(player -> player.getExecutionsCount())
                .sum();
    }

    private static ExecutionState execution(ExecutionsRequest request, int index) {
        int remaining = index;
        for (cobbleware.internal.PlayerExecutionState player : request.getPlayersList()) {
            if (remaining < player.getExecutionsCount()) {
                return player.getExecutions(remaining);
            }
            remaining -= player.getExecutionsCount();
        }
        throw new IndexOutOfBoundsException("execution index " + index + " is not present");
    }

    private static ExecutionState executionForOwner(ExecutionsRequest request, UUID owner) {
        for (cobbleware.internal.PlayerExecutionState player : request.getPlayersList()) {
            if (player.getPlayerId().getHigh() == owner.getMostSignificantBits()
                    && player.getPlayerId().getLow() == owner.getLeastSignificantBits()) {
                return player.getExecutions(0);
            }
        }
        throw new IllegalArgumentException("owner is not present in execution request");
    }

    private static final class Fixture {
        private final FakeExecutionStateAccess stateStore;
        private final CapturingMainWorker worker = new CapturingMainWorker();
        private final FakeMinecraft minecraft = new FakeMinecraft();
        private final Instrumentation instrumentation = new Instrumentation(10);
        private final ExecutionPoolManager executionPoolManager;
        private final Map<UUID, ExecutionPoolManager.EligiblePlayer> eligiblePlayers = new HashMap<>();
        private final PlayerPriorityStore playerPriorityStore =
                new PlayerPriorityStore(LoggerFactory.getLogger(ExecutionDriverTest.class));
        private final ExecutionDriver driver;
        private int nextPos;

        private Fixture(long stateLimit) {
            this(stateLimit, 200);
        }

        private Fixture(long stateLimit, int queuedEventLimit) {
            this(stateLimit, queuedEventLimit, 10);
        }

        private Fixture(long stateLimit, int queuedEventLimit, int guaranteedPoolSize) {
            this(stateLimit, queuedEventLimit, guaranteedPoolSize, 0);
        }

        private Fixture(long stateLimit, int queuedEventLimit, int guaranteedPoolSize, int bestEffortPoolSize) {
            stateStore = new FakeExecutionStateAccess(stateLimit);
            executionPoolManager = new ExecutionPoolManager(guaranteedPoolSize, bestEffortPoolSize);
            executionPoolManager.initialize(10);
            driver = new ExecutionDriver(
                    worker,
                    stateStore,
                    instrumentation,
                    playerPriorityStore,
                    executionPoolManager,
                    10,
                    queuedEventLimit,
                    200,
                    stateLimit,
                    LoggerFactory.getLogger(ExecutionDriverTest.class));
        }

        private CobbleputerBlockEntity startNewExecution(byte[] initialState) {
            return startNewExecution(UUID.randomUUID(), initialState);
        }

        private CobbleputerBlockEntity startNewExecution(UUID owner, byte[] initialState) {
            CobbleputerBlockEntity cobbleputer = new CobbleputerBlockEntity(
                    testCobbleputerBlockEntity, new BlockPos(nextPos++, 64, 0), uninitializedState, stateStore);
            assertEquals(
                    ExecutionDriver.ExecutionInitResult.SUCCESS,
                    driver.startExecution(minecraft, cobbleputer, owner, "owner", initialState));
            eligiblePlayers.put(owner, new ExecutionPoolManager.EligiblePlayer(owner, "owner", 2, 0));
            executionPoolManager.refresh(eligiblePlayers.values());
            return cobbleputer;
        }

        private void refreshAndAdvancePools() {
            driver.refreshExecutionPools(eligiblePlayers.values());
        }

        private void serverTick() {
            instrumentation.advanceTick();
            driver.driveExecutions(minecraft);
        }
    }

    private static final class FakeExecutionStateAccess implements ExecutionStateAccess {
        private final long stateLimit;

        private FakeExecutionStateAccess(long stateLimit) {
            this.stateLimit = stateLimit;
        }

        @Override
        public void attach(CobbleputerBlockEntity blockEntity) {}

        @Override
        public void update(CobbleputerBlockEntity blockEntity, UUID priorOwner) {}

        @Override
        public void remove(CobbleputerBlockEntity blockEntity) {}

        @Override
        public long stateSizeLimit(UUID owner, UUID cobbleputerId, long ownerLimit) {
            return stateLimit;
        }

        @Override
        public boolean fitsStateUpdate(UUID owner, UUID cobbleputerId, byte[] candidate, long ownerLimit) {
            return candidate.length <= stateLimit;
        }

        @Override
        public Map<UUID, Map<UUID, Long>> stateSizeCensus() {
            return Map.of();
        }
    }

    private static final class CapturingMainWorker implements MainWorker {
        private final List<ExecutionsRequest> syncRequests = new ArrayList<>();
        private final Deque<CompletableFuture<ExecutionSyncResult>> syncResults = new ArrayDeque<>();
        private final List<ExecutionTransition> transitions = new ArrayList<>();
        private final List<PoolTransitionReport> poolTransitions = new ArrayList<>();
        private final List<String> limitReasons = new ArrayList<>();

        private CompletableFuture<ExecutionSyncResult> enqueueSyncResult() {
            CompletableFuture<ExecutionSyncResult> result = new CompletableFuture<>();
            syncResults.addLast(result);
            return result;
        }

        @Override
        public CompletableFuture<ExecutionSyncResult> syncExecutions(ExecutionsRequest executions) {
            syncRequests.add(executions);
            return syncResults.removeFirst();
        }

        @Override
        public void reportExecutionStats(cobbleware.internal.ExecutionStatsTick stats) {}

        @Override
        public void reportExecutionTransition(
                UUID playerId,
                UUID executionId,
                ExecutionTransition transition,
                int executionCount,
                int executionLimit,
                long totalExecutionStateLimit) {
            transitions.add(transition);
        }

        @Override
        public void reportExecutionPoolTransition(
                UUID playerId, cobbleware.internal.ExecutionPoolTransition transition) {
            poolTransitions.add(new PoolTransitionReport(playerId, transition));
        }

        @Override
        public void reportLimitExceeded(UUID playerId, UUID executionId, String reason) {
            limitReasons.add(reason);
        }

        private record PoolTransitionReport(UUID playerId, cobbleware.internal.ExecutionPoolTransition transition) {}
    }

    private static final class FakeMinecraft implements ExecutionDriver.Minecraft {
        private final List<String> nearbyMessages = new ArrayList<>();
        private Optional<CobbleputerBlockEntity> observedCobbleputer = Optional.empty();
        private final List<WirelessObserverBlockEntity> initializedObservers = new ArrayList<>();
        private final List<WirelessObserverBlockEntity> changedObservers = new ArrayList<>();
        private final Deque<WirelessObserverBlockEntity.ObservedBlockStateChange> observedStateChanges =
                new ArrayDeque<>();
        private Runnable onSetRedstonePower = () -> {};

        @Override
        public Optional<CobbleputerBlockEntity> loadedCobbleputer(net.minecraft.core.GlobalPos target) {
            return observedCobbleputer;
        }

        @Override
        public boolean isServerLevel(BlockEntity blockEntity) {
            return true;
        }

        @Override
        public OptionalLong gameTime(CobbleputerBlockEntity blockEntity) {
            return OptionalLong.of(100);
        }

        @Override
        public Optional<net.minecraft.core.GlobalPos> globalPos(BlockEntity blockEntity, BlockPos pos) {
            return Optional.of(net.minecraft.core.GlobalPos.of(Level.OVERWORLD, pos));
        }

        @Override
        public boolean isLoaded(BlockEntity blockEntity, BlockPos pos) {
            return true;
        }

        @Override
        public Optional<WirelessObserverBlockEntity.ObservedBlockStateChange> takeObservedStateChange(
                WirelessObserverBlockEntity observer) {
            changedObservers.add(observer);
            return Optional.of(observedStateChanges.removeFirst());
        }

        @Override
        public boolean initializeObservedState(WirelessObserverBlockEntity observer) {
            initializedObservers.add(observer);
            return true;
        }

        @Override
        public Optional<DyeColor> observerColor(WirelessObserverBlockEntity observer) {
            return Optional.of(DyeColor.WHITE);
        }

        @Override
        public boolean setCobbleputerState(CobbleputerBlockEntity blockEntity, CobbleputerState nextState) {
            BlockState current = blockEntity.getBlockState();
            if (current.getValue(CobbleputerBlock.STATE) == nextState) {
                return false;
            }
            blockEntity.setBlockState(lifecycleStateFor(nextState));
            return true;
        }

        @Override
        public void sendSystemMessage(UUID playerId, Component message) {}

        @Override
        public void sendNearbySystemMessage(CobbleputerBlockEntity blockEntity, Component message) {
            nearbyMessages.add(message.getString());
        }

        @Override
        public void setRedstonePower(
                CobbleputerBlockEntity blockEntity,
                Optional<UUID> observerId,
                Optional<DyeColor> observerColor,
                int powerLevel) {
            onSetRedstonePower.run();
        }
    }
}
