package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class RendererModeLifecycleTest {
    @Test
    void sizedGraphsAreMutuallyExclusiveAcrossModeSwitches() {
        RendererModeLifecycle realtime = RendererModeLifecycle.initial().allocateRealtimeSized();
        assertThrows(IllegalStateException.class, realtime::enterOffline);

        RendererModeLifecycle offline = realtime
                .releaseRealtimeSized()
                .enterOffline()
                .allocateOfflineSized();
        assertEquals(RendererModeLifecycle.Mode.OFFLINE, offline.mode());
        assertThrows(IllegalStateException.class, offline::exitOffline);

        RendererModeLifecycle restored = offline
                .releaseOfflineSized()
                .exitOffline()
                .allocateRealtimeSized();
        assertEquals(RendererModeLifecycle.Mode.REALTIME, restored.mode());
    }

    @Test
    void constructorRejectsCrossOwnedResources() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RendererModeLifecycle(
                        RendererModeLifecycle.Mode.REALTIME, true, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RendererModeLifecycle(
                        RendererModeLifecycle.Mode.REALTIME, false, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RendererModeLifecycle(
                        RendererModeLifecycle.Mode.OFFLINE, true, false));
    }

    @Test
    void sizedOwnershipCannotChangeRendererMode() {
        RendererModeLifecycle realtime = RendererModeLifecycle.initial();
        assertThrows(IllegalStateException.class, realtime::allocateOfflineSized);
        assertThrows(IllegalStateException.class, realtime::releaseOfflineSized);

        RendererModeLifecycle offline = realtime.enterOffline();
        assertThrows(IllegalStateException.class, offline::allocateRealtimeSized);
        assertThrows(IllegalStateException.class, offline::releaseRealtimeSized);
    }
}
