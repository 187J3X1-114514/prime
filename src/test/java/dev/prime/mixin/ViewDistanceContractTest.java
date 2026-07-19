package dev.prime.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ViewDistanceContractTest {
    @Test
    void clientAndIntegratedServerShareThe128ChunkLimit() throws IOException {
        assertEquals(128, ViewDistanceLimits.MAXIMUM_RENDER_DISTANCE);
        Path clientRoot = Path.of(System.getProperty("user.dir"), "src", "client");
        String chunkMap = Files.readString(
                clientRoot.resolve("java/dev/prime/mixin/ChunkMapMixin.java"));
        String mixins = Files.readString(clientRoot.resolve("resources/prime.mixins.json"));
        assertTrue(chunkMap.contains("ViewDistanceLimits.MAXIMUM_RENDER_DISTANCE"));
        assertTrue(mixins.contains("\"OptionsMixin\""));
        assertTrue(mixins.contains("\"ChunkMapMixin\""));
    }
}
