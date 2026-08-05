package dev.prime.render.vulkan.terrain;

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
        Job first = new Job(1, 16L * MIB, true);
        Job second = new Job(2, 48L * MIB, true);
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
                admit(56L * MIB, List.of(head, younger)));
    }

    @Test
    void olderUnreadyOrCancelledJobsDoNotBlockReadyWork() {
        Job unready = new Job(1, 63L * MIB, false);
        Job ready = new Job(2, 32L * MIB, true);
        Job alsoReady = new Job(3, 32L * MIB, true);

        assertEquals(
                List.of(ready, alsoReady),
                admit(0L, List.of(unready, ready, alsoReady)));
    }

    @Test
    void oversizedHeadRunsAloneOnlyWhenNothingElseIsInFlight() {
        Job oversized = new Job(1, 65L * MIB, true);
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
        budget.reserve(24L * MIB);
        budget.reserve(40L * MIB);

        assertEquals(64L * MIB, budget.reservedBytes());
        assertEquals(64L * MIB, budget.highWaterBytes());
        budget.release(24L * MIB);
        budget.release(40L * MIB);
        assertEquals(0L, budget.reservedBytes());
        assertEquals(64L * MIB, budget.highWaterBytes());
        assertThrows(IllegalStateException.class, () -> budget.release(1L));
    }

    private static List<Job> admit(long reservedBytes, List<Job> jobs) {
        return BlasCompactionScheduler.admitReadyPrefix(
                jobs, reservedBytes, Job::ready, Job::targetBytes);
    }

    private record Job(int sequence, long targetBytes, boolean ready) {}
}
