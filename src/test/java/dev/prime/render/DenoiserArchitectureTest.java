package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.post.Denoiser;
import dev.prime.render.post.RealtimePostProcessor;
import org.junit.jupiter.api.Test;

final class DenoiserArchitectureTest {
    @Test
    void realtimeAndReferenceBackendsShareTheDenoiserBoundary() throws ClassNotFoundException {
        assertTrue(Denoiser.class.isAssignableFrom(RealtimePostProcessor.class));
        assertTrue(Denoiser.class.isAssignableFrom(
                Class.forName("dev.prime.render.ReferenceAccumulator")));
        assertEquals(4, Denoiser.Kind.values().length);
        assertEquals(Denoiser.Kind.NOISY, Denoiser.Kind.valueOf("NOISY"));
        assertEquals(Denoiser.Kind.REFERENCE_ACCUMULATION,
                Denoiser.Kind.valueOf("REFERENCE_ACCUMULATION"));
    }
}
