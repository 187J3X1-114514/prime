package dev.prime.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ViewDistanceContractTest {
    @Test
    void maximumRenderDistanceIs128Chunks() {
        assertEquals(128, ViewDistanceLimits.MAXIMUM_RENDER_DISTANCE);
    }
}
