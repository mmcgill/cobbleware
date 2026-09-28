package io.github.mmcgill.cobbleware.execution;

import io.github.mmcgill.cobbleware.block.CobbleputerBlockEntity;
import java.util.Map;
import java.util.UUID;

/** The execution-state operations needed by {@link ExecutionDriver}. */
public interface ExecutionStateAccess {
    /** Attaches the durable execution record to a loaded Cobbleputer. */
    void attach(CobbleputerBlockEntity blockEntity);

    /** Returns the largest state byte length an execution may next store. */
    long stateSizeLimit(UUID owner, UUID cobbleputerId, long ownerLimit);

    /** Returns whether replacing an execution's state with {@code candidate} fits its owner's limit. */
    boolean fitsStateUpdate(UUID owner, UUID cobbleputerId, byte[] candidate, long ownerLimit);

    /** Returns durable execution-state byte lengths grouped by owner and Cobbleputer. */
    Map<UUID, Map<UUID, Long>> stateSizeCensus();
}
