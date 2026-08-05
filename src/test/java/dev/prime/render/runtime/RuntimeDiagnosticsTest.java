package dev.prime.render.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.replay.RenderReplayVerification;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class RuntimeDiagnosticsTest {
    @Test
    void onlyTheCurrentReplayRequestOwnsCompletion() {
        RuntimeDiagnostics diagnostics = new RuntimeDiagnostics();
        CompletableFuture<RenderReplayVerification> first = new CompletableFuture<>();
        CompletableFuture<RenderReplayVerification> second = new CompletableFuture<>();

        assertTrue(diagnostics.claimReplay(first));
        assertTrue(diagnostics.replayPending());
        assertFalse(diagnostics.claimReplay(second));
        assertFalse(diagnostics.releaseReplay(second));
        assertTrue(diagnostics.releaseReplay(first));
        assertFalse(diagnostics.replayPending());
        assertTrue(diagnostics.claimReplay(second));

        diagnostics.clearReplay();
        assertFalse(diagnostics.releaseReplay(second));
    }
}
