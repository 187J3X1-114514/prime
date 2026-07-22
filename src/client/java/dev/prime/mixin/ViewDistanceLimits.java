package dev.prime.mixin;

/** One source of truth shared by the client control and its integrated-server producer. */
final class ViewDistanceLimits {
    // Minecraft's distance graph accepts fewer than 254 levels. The player tracker reserves two
    // levels above the configured distance, making 251 its representable maximum.
    static final int MAXIMUM_RENDER_DISTANCE = 251;

    private ViewDistanceLimits() {
    }
}
