package dev.prime.render.terrain;

/** Proven physical topology used to choose the dielectric closure. */
public enum TransmissiveTopology {
    NONE,
    SOLID,
    THIN_SHEET;

    public boolean thinWalled() {
        return this == THIN_SHEET;
    }
}
