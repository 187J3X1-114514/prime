package dev.prime.render.scene.vanilla;

/** Stable inclusion rules for content submitted by Minecraft's world renderer. */
public final class VanillaSceneBoundary {
    private VanillaSceneBoundary() {
    }

    public static boolean includes(
            Element element, boolean localPlayer, boolean firstPersonCamera) {
        return switch (element) {
            case ENTITY -> !localPlayer || !firstPersonCamera;
            case BLOCK_ENTITY, PARTICLE, WEATHER -> true;
            case FIRST_PERSON_HAND, FIRST_PERSON_ITEM, SCREEN_OVERLAY -> false;
        };
    }

    public enum Element {
        ENTITY,
        BLOCK_ENTITY,
        PARTICLE,
        WEATHER,
        FIRST_PERSON_HAND,
        FIRST_PERSON_ITEM,
        SCREEN_OVERLAY
    }
}
