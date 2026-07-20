package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PostProcessingArchitectureContractTest {
    @Test
    void disabledModePresentsNativeOneSppWithoutAnyReconstructionBackend() throws IOException {
        String renderer = javaSource("render", "VulkanRenderer.java");
        String resources = javaSource("render", "RealtimeRenderResources.java");
        String processor = javaSource("render", "vulkan", "NoisyPostProcessor.java");
        String composite = shader("noisy_composite.comp");

        assertTrue(renderer.contains("effectiveMode == PostProcessingMode.DISABLED"));
        assertTrue(renderer.contains("renderWidth = width;\n            renderHeight = height;"));
        assertTrue(renderer.contains("images.mode == PostProcessingMode.DLSS_RR"));
        assertTrue(resources.contains("case DISABLED -> NoisyPostProcessor.create"));
        assertTrue(processor.contains("PostProcessingMode.DISABLED"));
        assertFalse(processor.contains("NrdDenoiser"));
        assertFalse(processor.contains("Fsr3Upscaler"));
        assertFalse(processor.contains("DlssRrNative"));
        assertTrue(composite.contains("primeNoisyDiffuse"));
        assertTrue(composite.contains("primeNoisySpecular"));
        assertTrue(composite.contains("primeNoisyStableRadiance"));
    }

    @Test
    void nativeDiagnosticsDoNotPolluteOrResetTemporalHistory() throws IOException {
        String nrd = javaSource("render", "vulkan", "nrd", "NrdDenoiser.java");
        String nrdFsr = javaSource("render", "vulkan", "NrdFsrPostProcessor.java");
        String fsr = javaSource("render", "vulkan", "fsr", "Fsr3Upscaler.java");
        String present = shader("native_debug_present.comp");
        String config = javaSource("config", "PrimeConfig.java");

        assertTrue(nrd.contains("this.height,\n                    0,\n                    sunRadianceMultiplier"));
        assertTrue(nrdFsr.contains("denoiser.validation(), displayOutput"));
        assertTrue(nrdFsr.contains("this.nrdDebugPresent.record(commandBuffer)"));
        assertFalse(fsr.contains("diagnosticChanged"));
        assertFalse(fsr.contains("previousFsrDebugView"));
        assertFalse(fsr.contains("previousNrdDebugView"));
        assertTrue(present.contains("texelFetch(primeNativeDebugInput"));
        assertFalse(present.contains("primeDisplayTransform"));
        assertFalse(config.substring(config.indexOf("static String serializedContents()"))
                .contains("debug_view"));
    }

    @Test
    void screenshotShortcutIsToggleOnlyWhileEscapeIsExitOnly() throws IOException {
        String controls = javaSource("render", "ScreenshotModeControls.java");
        String mixin = javaSource("mixin", "MinecraftMixin.java");

        assertTrue(controls.contains("ScreenshotMode.request(!ScreenshotMode.requested())"));
        assertTrue(controls.contains("GLFW.GLFW_KEY_LEFT_ALT"));
        assertTrue(controls.contains("minecraft.options.keyScreenshot.matches(key)"));
        assertTrue(controls.contains("controlDown"));
        assertTrue(controls.contains("GLFW.GLFW_KEY_ESCAPE"));
        assertTrue(controls.contains("ScreenshotMode.request(false)"));
        assertFalse(controls.substring(controls.indexOf("public static void tick"))
                .contains("ScreenshotMode.request(true)"));
        assertTrue(mixin.contains("handleGlobalKeyPress"));
        assertTrue(mixin.contains("callbackInfo.setReturnValue(true)"));
    }

    private static String shader(String name) throws IOException {
        return Files.readString(Path.of(System.getProperty("user.dir"), "shaders", name));
    }

    private static String javaSource(String... names) throws IOException {
        Path path = Path.of(System.getProperty("user.dir"), "src", "client", "java", "dev", "prime");
        for (String name : names) path = path.resolve(name);
        return Files.readString(path);
    }
}
