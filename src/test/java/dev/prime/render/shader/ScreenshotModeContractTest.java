package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ScreenshotModeContractTest {
    @Test
    void screenshotPathAccumulatesTheCompleteUnfilteredEstimator() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        String rayGeneration = Files.readString(root.resolve("shaders/screenshot.rgen"));
        String camera = Files.readString(root.resolve("shaders/camera.glsl"));
        String display = Files.readString(root.resolve("shaders/screenshot_display.comp"));
        String renderer = Files.readString(root.resolve(
                "src/client/java/dev/prime/render/VulkanRenderer.java"))
                .replace("\r\n", "\n");

        assertFalse(rayGeneration.contains("#define PRIME_OPAQUE_PRIMARY_PASS"));
        assertTrue(rayGeneration.contains("sampleResult.diffuseRadiance"));
        assertTrue(rayGeneration.contains("+ sampleResult.specularRadiance"));
        assertTrue(rayGeneration.contains("+ sampleResult.stableRadiance"));
        assertTrue(rayGeneration.contains("primeApplyAerialPerspective"));
        assertTrue(rayGeneration.contains("previous.rgb + (sampleRadiance - previous.rgb) / sampleCount"));
        assertFalse(rayGeneration.contains("primeNrdSanitizeRadiance"));
        assertTrue(camera.contains("#if defined(PRIME_SCREENSHOT_MODE)"));
        assertTrue(camera.contains("primeSobolSample2D"));
        assertTrue(display.contains("primeDisplayTransformToSrgb"));
        assertTrue(renderer.contains("traceScreenshot(commandBuffer"));
        assertTrue(renderer.contains("images.display.record(commandBuffer"));
    }

    @Test
    void screenshotSessionFreezesWorldInputsAndBypassesRealtimeTemporalStages()
            throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        String renderer = Files.readString(root.resolve(
                "src/client/java/dev/prime/render/VulkanRenderer.java"))
                .replace("\r\n", "\n");
        String atlasMixin = Files.readString(root.resolve(
                "src/client/java/dev/prime/mixin/TextureAtlasMixin.java"));
        int methodStart = renderer.indexOf("private void renderScreenshot(");
        int methodEnd = renderer.indexOf("private void updateScreenshotSession(", methodStart);
        String screenshotMethod = renderer.substring(methodStart, methodEnd);

        assertTrue(renderer.contains("if (ScreenshotMode.active()) {\n            return;"));
        assertTrue(renderer.contains("this.screenshotScene = this.terrain.sceneView()"));
        assertTrue(renderer.contains("this.screenshotLighting = LightingSettings.snapshot()"));
        assertTrue(renderer.contains("this.terrain.invalidateAll()"));
        assertFalse(screenshotMethod.contains("denoiser.record("));
        assertFalse(screenshotMethod.contains("upscaler.record("));
        assertTrue(atlasMixin.contains("ScreenshotMode.active()"));
        assertTrue(atlasMixin.contains("callbackInfo.cancel()"));
    }
}
