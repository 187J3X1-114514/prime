package dev.prime.render;

/**
 * Session-only control and observable state for Prime's converged screenshot renderer.
 *
 * <p>The UI records a request; VulkanRenderer is the only owner allowed to publish the active
 * state after it has frozen a complete render snapshot. Keeping requested and active separate
 * prevents atlas animation or terrain streaming from being paused by a mode that failed to enter.
 */
public final class ScreenshotMode {
    private static volatile boolean requested;
    private static volatile boolean active;

    private ScreenshotMode() {
    }

    public static boolean requested() {
        return requested;
    }

    public static void request(boolean enabled) {
        requested = enabled;
    }

    public static boolean active() {
        return active;
    }

    static void activate() {
        active = true;
    }

    static void deactivate() {
        active = false;
    }

    static void reset() {
        requested = false;
        active = false;
    }
}
