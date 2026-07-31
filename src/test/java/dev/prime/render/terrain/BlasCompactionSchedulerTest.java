package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.prime.render.vulkan.PreparedBlas;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BlasCompactionSchedulerTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    void dynamicBlasesAreExcludedFromCompaction() {
        assertEquals(
                PreparedBlas.CompactionPolicy.DISABLED,
                TerrainScene.compactionPolicy(true));
        assertEquals(
                PreparedBlas.CompactionPolicy.ENABLED,
                TerrainScene.compactionPolicy(false));
    }

    @Test
    void admitsMultipleReadyJobsUpToTheExactBudgetBoundary() {
        Job first = new Job(1, 4L * MIB, true);
        Job second = new Job(2, 12L * MIB, true);
        Job third = new Job(3, 1L, true);

        assertEquals(
                List.of(first, second),
                admit(0L, List.of(first, second, third)));
    }

    @Test
    void readyHeadThatDoesNotFitCannotBeBypassed() {
        Job head = new Job(1, 9L * MIB, true);
        Job younger = new Job(2, 1L * MIB, true);

        assertEquals(
                List.of(),
                admit(8L * MIB, List.of(head, younger)));
    }

    @Test
    void olderUnreadyOrCancelledJobsDoNotBlockReadyWork() {
        Job unready = new Job(1, 15L * MIB, false);
        Job ready = new Job(2, 8L * MIB, true);
        Job alsoReady = new Job(3, 8L * MIB, true);

        assertEquals(
                List.of(ready, alsoReady),
                admit(0L, List.of(unready, ready, alsoReady)));
    }

    @Test
    void oversizedHeadRunsAloneOnlyWhenNothingElseIsInFlight() {
        Job oversized = new Job(1, 17L * MIB, true);
        Job younger = new Job(2, 1L * MIB, true);

        assertEquals(
                List.of(oversized),
                admit(0L, List.of(oversized, younger)));
        assertEquals(
                List.of(),
                admit(1L, List.of(oversized, younger)));
    }

    @Test
    void retirementReleasesReservationWithoutReducingHighWater() {
        BlasCompactionScheduler.TargetBudget budget =
                new BlasCompactionScheduler.TargetBudget();
        budget.reserve(6L * MIB);
        budget.reserve(10L * MIB);

        assertEquals(16L * MIB, budget.reservedBytes());
        assertEquals(16L * MIB, budget.highWaterBytes());
        budget.release(6L * MIB);
        budget.release(10L * MIB);
        assertEquals(0L, budget.reservedBytes());
        assertEquals(16L * MIB, budget.highWaterBytes());
        assertThrows(IllegalStateException.class, () -> budget.release(1L));
    }

    private static List<Job> admit(long reservedBytes, List<Job> jobs) {
        return BlasCompactionScheduler.admitReadyPrefix(
                jobs, reservedBytes, Job::ready, Job::targetBytes);
    }

    private record Job(int sequence, long targetBytes, boolean ready) {}
}
