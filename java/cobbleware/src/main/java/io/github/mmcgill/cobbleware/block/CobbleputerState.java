package io.github.mmcgill.cobbleware.block;

import net.minecraft.util.StringRepresentable;

/** The visible lifecycle states of a Cobbleputer block. */
public enum CobbleputerState implements StringRepresentable {
    UNINITIALIZED("uninitialized"),
    RUNNING("running"),
    STOPPED("stopped"),
    CRASHED("crashed");

    private final String serializedName;

    CobbleputerState(String serializedName) {
        this.serializedName = serializedName;
    }

    /**
     * Temporary manual-use transition for the initial block milestone.
     *
     * <p>Execution management will replace this with transitions driven by the execution lifecycle.</p>
     */
    public CobbleputerState next() {
        return switch (this) {
            case UNINITIALIZED -> RUNNING;
            case RUNNING -> STOPPED;
            case STOPPED -> CRASHED;
            case CRASHED -> UNINITIALIZED;
        };
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
