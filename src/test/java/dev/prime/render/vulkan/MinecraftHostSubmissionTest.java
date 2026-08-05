package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MinecraftHostSubmissionTest {
    @Test
    void ownershipChangesOnlyAfterExplicitHostAcceptance() {
        MinecraftHostSubmission submission = new MinecraftHostSubmission();
        assertFalse(submission.wasAcceptedByMinecraftHostSubmission());

        submission.acceptedByMinecraftHostSubmission();

        assertTrue(submission.wasAcceptedByMinecraftHostSubmission());
        assertThrows(
                IllegalStateException.class,
                submission::acceptedByMinecraftHostSubmission);
    }
}
