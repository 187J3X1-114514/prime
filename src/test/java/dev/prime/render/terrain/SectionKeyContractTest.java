package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

final class SectionKeyContractTest {
    @Test
    void sectionKeysRoundTripSignedCoordinates() {
        int[][] coordinates = new int[][] {
            {0, 0, 0},
            {-1, -4, 1},
            {1_875_000, 31, -1_875_000}
        };
        for (int[] coordinate : coordinates) {
            long key = SectionPos.asLong(coordinate[0], coordinate[1], coordinate[2]);
            assertEquals(coordinate[0], SectionPos.x(key));
            assertEquals(coordinate[1], SectionPos.y(key));
            assertEquals(coordinate[2], SectionPos.z(key));
        }
        assertNotEquals(SectionPos.asLong(-1, 0, 0), SectionPos.asLong(0, 0, 0));
    }

    @Test
    void oneBlockDirtyBorderReachesTheAdjacentSection() {
        assertEquals(Set.of(0, 1), expandedSectionCoordinates(15));
        assertEquals(Set.of(-1, 0), expandedSectionCoordinates(0));
        assertEquals(Set.of(0), expandedSectionCoordinates(8));
    }

    private static Set<Integer> expandedSectionCoordinates(int blockCoordinate) {
        Set<Integer> sections = new HashSet<>();
        for (int coordinate = blockCoordinate - 1; coordinate <= blockCoordinate + 1; coordinate++) {
            sections.add(SectionPos.blockToSectionCoord(coordinate));
        }
        return sections;
    }
}
