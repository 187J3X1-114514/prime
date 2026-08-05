package dev.prime.render.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.replay.RenderReplayFixtureStore;
import dev.prime.render.replay.RenderReplayVerification;
import dev.prime.render.vulkan.VulkanBootstrap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** Client boundary for runtime logs, toasts and the asynchronous replay diagnostic request. */
public final class RuntimeDiagnostics {
    private static final SystemToast.SystemToastId UNAVAILABLE_TOAST =
            new SystemToast.SystemToastId(8_000L);
    private static final SystemToast.SystemToastId REPLAY_TEST_TOAST =
            new SystemToast.SystemToastId(8_001L);

    private boolean notificationShown;
    private boolean unavailabilityLogged;
    private CompletableFuture<RenderReplayVerification> replayTest;

    public void resetAvailabilityNotifications() {
        this.notificationShown = false;
        this.unavailabilityLogged = false;
    }

    public String finalizeUnavailableReason(String failureReason, RuntimeState state) {
        if (this.unavailabilityLogged || state != RuntimeState.UNAVAILABLE) {
            return failureReason;
        }
        String resolved = failureReason;
        if (VulkanBootstrap.snapshot().device() == null) {
            String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
            resolved = "Minecraft is using " + backend
                    + "; select the Vulkan graphics backend";
        }
        this.unavailabilityLogged = true;
        PrimeInfo.LOGGER.warn("Prime will use vanilla rendering: {}", resolved);
        return resolved;
    }

    public void showFailureOnce(
            Minecraft minecraft, RuntimeState state, String failureReason) {
        if (this.notificationShown
                || state != RuntimeState.UNAVAILABLE && state != RuntimeState.FAILED
                || minecraft.gui == null) {
            return;
        }
        this.notificationShown = true;
        SystemToast.add(
                minecraft.gui.toastManager(),
                UNAVAILABLE_TOAST,
                Component.literal("Prime ray tracing unavailable"),
                Component.literal(failureReason));
    }

    public void requestReplay(
            Minecraft minecraft,
            Supplier<CompletableFuture<RenderReplayVerification>> requestFactory) {
        if (replayPending()) {
            return;
        }
        CompletableFuture<RenderReplayVerification> requested =
                java.util.Objects.requireNonNull(requestFactory.get(), "replay request");
        if (!claimReplay(requested)) {
            return;
        }
        requested.whenComplete((verification, failure) ->
                minecraft.execute(() -> reportReplay(
                        minecraft, requested, verification, failure)));
    }

    public void clearReplay() {
        this.replayTest = null;
    }

    boolean replayPending() {
        return this.replayTest != null && !this.replayTest.isDone();
    }

    boolean claimReplay(CompletableFuture<RenderReplayVerification> request) {
        java.util.Objects.requireNonNull(request, "request");
        if (replayPending()) {
            return false;
        }
        this.replayTest = request;
        return true;
    }

    boolean releaseReplay(CompletableFuture<RenderReplayVerification> request) {
        if (this.replayTest != request) {
            return false;
        }
        this.replayTest = null;
        return true;
    }

    private void reportReplay(
            Minecraft minecraft,
            CompletableFuture<RenderReplayVerification> request,
            RenderReplayVerification verification,
            Throwable failure) {
        if (!releaseReplay(request)) {
            return;
        }
        boolean passed = failure == null
                && verification != null
                && verification.valid();
        boolean phaseMeasured = verification != null
                && verification.referenceJitterPhase().measurable()
                && verification.replayJitterPhase().measurable();
        if (passed) {
            var fixture = minecraft.gameDirectory
                    .toPath()
                    .resolve("prime/replay/last-success.prseq");
            try {
                RenderReplayFixtureStore.save(fixture, verification.reference());
                PrimeInfo.LOGGER.info(
                        "Saved validated Prime replay fixture to {}",
                        fixture.toAbsolutePath().normalize());
            } catch (java.io.IOException | RuntimeException exception) {
                PrimeInfo.LOGGER.warn(
                        "Prime replay self-test passed, but its fixture could not be saved",
                        exception);
            }
            PrimeInfo.LOGGER.info(
                    "Prime 64x64 deterministic NRD jitter replay self-test passed: {}",
                    verification.referenceJitterPhase());
        } else if (failure != null) {
            PrimeInfo.LOGGER.error(
                    "Prime deterministic NRD replay self-test failed", failure);
        } else {
            var replayDirectory = minecraft.gameDirectory.toPath().resolve("prime/replay");
            var referenceFixture = replayDirectory.resolve("last-failure-reference.prseq");
            var replayFixture = replayDirectory.resolve("last-failure-replay.prseq");
            try {
                RenderReplayFixtureStore.save(referenceFixture, verification.reference());
                RenderReplayFixtureStore.save(replayFixture, verification.replay());
                PrimeInfo.LOGGER.info(
                        "Saved failed Prime replay captures to reference={} and replay={}",
                        referenceFixture.toAbsolutePath().normalize(),
                        replayFixture.toAbsolutePath().normalize());
            } catch (java.io.IOException | RuntimeException exception) {
                PrimeInfo.LOGGER.warn(
                        "Prime replay self-test failed, and its captures could not be saved",
                        exception);
            }
            PrimeInfo.LOGGER.error(
                    "Prime deterministic NRD jitter replay self-test failed: semantic reference={}, semantic replay={}, jitter reference={}, jitter replay={}, first divergence={}",
                    verification.referenceSemantics(),
                    verification.replaySemantics(),
                    verification.referenceJitterPhase(),
                    verification.replayJitterPhase(),
                    verification.determinism().firstMismatch());
        }
        if (minecraft.gui != null) {
            SystemToast.add(
                    minecraft.gui.toastManager(),
                    REPLAY_TEST_TOAST,
                    Component.literal("Prime jitter self-test"),
                    Component.literal(!passed
                            ? "Phase mismatch; see the measured amplitudes in the log"
                            : phaseMeasured
                                    ? "NRD boundary/interior phase matched"
                                    : "Aim at detailed glass to measure phase"));
        }
    }
}
