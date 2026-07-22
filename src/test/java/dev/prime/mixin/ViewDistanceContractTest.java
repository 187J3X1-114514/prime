package dev.prime.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ViewDistanceContractTest {
    @Test
    void playerDistanceAndReservedGraphLevelsFitTheUnsignedStorageContract() {
        int sentinelLevel = ViewDistanceLimits.MAXIMUM_RENDER_DISTANCE + 2;

        assertEquals(253, sentinelLevel);
        assertEquals(sentinelLevel, Byte.toUnsignedInt((byte) sentinelLevel));
    }
}
