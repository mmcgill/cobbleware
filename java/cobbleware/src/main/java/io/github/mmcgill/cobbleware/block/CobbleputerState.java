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

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
