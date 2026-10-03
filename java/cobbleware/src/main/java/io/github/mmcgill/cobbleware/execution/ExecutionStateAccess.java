package io.github.mmcgill.cobbleware.execution;

import io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity;
import java.util.Map;
import java.util.UUID;

/** The durable execution-state operations used by Cobbleputer execution. */
public interface ExecutionStateAccess {
    /** Snapshot of the Mod-authoritative per-player chat token buckets. */
    record ChatTokens(int burst, int slow) {}

    /** Attaches the durable execution record to a loaded Cobbleputer. */
    void attach(CobbleputerBlockEntity blockEntity);

    /** Records a Cobbleputer's current execution state. */
    void update(CobbleputerBlockEntity blockEntity, UUID priorOwner);

    /** Removes a Cobbleputer's durable execution record. */
    void remove(CobbleputerBlockEntity blockEntity);

    /** Returns the largest state byte length an execution may next store. */
    long stateSizeLimit(UUID owner, UUID cobbleputerId, long ownerLimit);

    /** Returns whether replacing an execution's state with {@code candidate} fits its owner's limit. */
    boolean fitsStateUpdate(UUID owner, UUID cobbleputerId, byte[] candidate, long ownerLimit);

    /** Advances an owner's chat buckets through {@code gameTime} and returns their current contents. */
    ChatTokens chatTokens(UUID owner, long gameTime);

    /** Advances an owner's chat buckets and spends one burst token when one is available. */
    boolean tryConsumeBurstChatToken(UUID owner, long gameTime);

    /** Advances known owners' chat buckets through {@code gameTime} and returns their current contents. */
    Map<UUID, ChatTokens> chatTokenCensus(long gameTime);

    /** Returns durable execution-state byte lengths grouped by owner and Cobbleputer. */
    Map<UUID, Map<UUID, Long>> stateSizeCensus();
}
