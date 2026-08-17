package dev.prime.render;

/**
 * Crosses Minecraft's option, surface-configuration and render boundaries.
 *
 * <p>The client thread owns mutations. Volatile whole-value publication lets display recording
 * observe one immutable capability without a lock or independently changing its fields.
 */
public final class HdrOutput {
    private static volatile boolean requested;
    private static volatile Capability capability = Capability.UNSUPPORTED;

    private HdrOutput() {
    }

    public static boolean requested() {
        return requested;
    }

    public static void setRequested(boolean value) {
        requested = value;
    }

    public static Capability capability() {
        return capability;
    }

    public static void updateCapability(boolean supported, float headroom) {
        capability = supported
                ? Capability.supported(headroom)
                : Capability.UNSUPPORTED;
    }

    public static float activeHeadroom() {
        Capability current = capability;
        return requested && current.supported()
                ? current.headroom()
                : AgxHsvOutput.MINIMUM_HEADROOM;
    }

    public record Capability(boolean supported, float headroom) {
        private static final Capability UNSUPPORTED =
                new Capability(false, AgxHsvOutput.MINIMUM_HEADROOM);

        public Capability {
            if (!Float.isFinite(headroom)
                    || headroom < AgxHsvOutput.MINIMUM_HEADROOM
                    || headroom > AgxHsvOutput.MAXIMUM_HEADROOM
                    || supported && headroom <= AgxHsvOutput.MINIMUM_HEADROOM) {
                throw new IllegalArgumentException("Invalid HDR output capability");
            }
            if (!supported && headroom != AgxHsvOutput.MINIMUM_HEADROOM) {
                throw new IllegalArgumentException("Unsupported HDR output has no headroom");
            }
        }

        public static Capability supported(float requestedHeadroom) {
            if (!Float.isFinite(requestedHeadroom)) {
                throw new IllegalArgumentException("HDR headroom must be finite");
            }
            float headroom = Math.clamp(
                    requestedHeadroom,
                    Math.nextUp(AgxHsvOutput.MINIMUM_HEADROOM),
                    AgxHsvOutput.MAXIMUM_HEADROOM);
            return new Capability(true, headroom);
        }
    }
}
