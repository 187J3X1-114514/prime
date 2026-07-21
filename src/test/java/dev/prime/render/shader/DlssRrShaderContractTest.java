package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DlssRrShaderContractTest {
    @Test
    void rrOmitsTransparencyInputAndSupplementalTransport() throws IOException {
        String raygen = shader("world.rgen");
        String prepare = shader("rr_prepare.comp");
        String bsdf = shader("bsdf.glsl");
        String nativeBridge = nativeSource("dlss_rr", "prime_dlss_rr_bridge.cpp");

        assertFalse(raygen.contains("denoiser_guides.glsl"));
        assertFalse(raygen.contains("TransparencyGuide"));
        assertFalse(prepare.contains("rrTransparencyGuide"));
        assertFalse(prepare.contains("rrColorBeforeTransparency"));
        assertFalse(bsdf.contains(
                "PrimeTransmissiveBsdfSample primeSampleMinecraftTransmissionBranch("));
        assertTrue(nativeBridge.contains("evaluate.pInColorBeforeTransparency = nullptr;"));
        assertFalse(nativeBridge.contains("COLOR_BEFORE_TRANSPARENCY,"));
    }

    @Test
    void rrPreparationUsesBitIdenticalOpaqueColorCurrentToPreviousMotionAndLinearViewZ()
            throws IOException {
        String prepare = shader("rr_prepare.comp");
        String preparePass = javaSource("render", "vulkan", "dlss", "DlssRrPreparePass.java");
        String postProcessor = javaSource(
                "render", "vulkan", "dlss", "DlssRrPostProcessor.java");
        String nativeBridge = nativeSource("dlss_rr", "prime_dlss_rr_bridge.cpp");

        assertTrue(prepare.contains("vec2 currentJitterPixels;"));
        assertTrue(prepare.contains(
                "vec2 currentSampleUv = (vec2(pixel) + vec2(0.5) + rrPush.currentJitterPixels)"));
        assertTrue(prepare.contains("rrRayDirection(currentSampleUv)"));
        assertTrue(prepare.contains("return previousUv - currentSampleUv;"));
        assertFalse(prepare.contains("return previousUv - currentUv;"));
        assertTrue(prepare.contains("viewZ = abs(viewPosition.z);"));
        assertTrue(prepare.contains("vec3 diffuseAlbedo = sky"));
        assertTrue(prepare.contains("vec3 specularAlbedo = sky"));
        assertTrue(prepare.contains("? vec3(0.5)"));
        assertTrue(prepare.contains("rrSpecularHitDistance,\n            pixel"));
        assertTrue(preparePass.contains("private static final int PUSH_SIZE = 208;"));
        assertTrue(preparePass.contains("push.putFloat(192, sunRadianceMultiplier);"));
        assertTrue(preparePass.contains("push.putFloat(200, currentJitterPixels.x());"));
        assertTrue(preparePass.contains("push.putFloat(204, currentJitterPixels.y());"));
        assertTrue(postProcessor.contains("-token.jitter.x()"));
        assertTrue(postProcessor.contains("-token.jitter.y()"));
        assertTrue(postProcessor.contains(
                "token.historyCamera,\n                token.jitter,\n                sunRadianceMultiplier"));
        assertFalse(nativeBridge.contains("NVSDK_NGX_DLSS_Feature_Flags_MVJittered"));
        assertTrue(nativeBridge.contains("evaluate.InMVScaleX = description->motionScaleX;"));
        assertTrue(nativeBridge.contains("evaluate.InMVScaleY = description->motionScaleY;"));
    }

    @Test
    void releaseOverlaySamplesEveryActualNgxImageAndHasNoOffShaderMode() throws IOException {
        String debug = shader("rr_debug.comp");
        String pass = javaSource("render", "vulkan", "dlss", "DlssRrDebugPass.java");

        assertTrue(debug.contains("const uint panels[10]"));
        assertFalse(debug.contains("TRANSPARENCY"));
        assertTrue(debug.contains("log2(1.0 + max(imageLoad(rrDebugDepth"));
        assertTrue(debug.contains("log2(1.0 + max(imageLoad(rrDebugSpecularHitDistance"));
        assertTrue(pass.contains("targets.inputColor()"));
        assertFalse(pass.contains("targets.colorBeforeTransparency()"));
        assertTrue(pass.contains("targets.rrOutput()"));
        assertTrue(pass.contains("if (view == DlssRrDebugView.OFF) {\n            return;"));
    }

    private static String shader(String name) throws IOException {
        return Files.readString(Path.of(System.getProperty("user.dir"), "shaders", name));
    }

    private static String javaSource(String... names) throws IOException {
        Path path = Path.of(System.getProperty("user.dir"), "src", "client", "java", "dev", "prime");
        for (String name : names) path = path.resolve(name);
        return Files.readString(path);
    }

    private static String nativeSource(String... names) throws IOException {
        Path path = Path.of(System.getProperty("user.dir"), "native");
        for (String name : names) path = path.resolve(name);
        return Files.readString(path);
    }
}
