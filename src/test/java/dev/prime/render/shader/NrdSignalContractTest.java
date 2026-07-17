package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class NrdSignalContractTest {
    @Test
    void primaryMissNeverConsumesSurfaceDenoiserHistory() throws IOException {
        Path shaderRoot = Path.of(System.getProperty("user.dir"), "shaders");
        String rayGeneration = Files.readString(shaderRoot.resolve("world.rgen"));
        String preparation = Files.readString(shaderRoot.resolve("nrd_motion.comp"));
        String composite = Files.readString(shaderRoot.resolve("nrd_composite.comp"));
        String transparent = Files.readString(shaderRoot.resolve("transparent.rgen"));
        String transparentPreparation = Files.readString(
                shaderRoot.resolve("nrd_transparent_motion.comp"));
        String transparentComposite = Files.readString(
                shaderRoot.resolve("nrd_transparent_composite.comp"));
        String opaqueAnyHit = Files.readString(shaderRoot.resolve("world_opaque.rahit"));
        String integrator = Files.readString(shaderRoot.resolve("integrator.glsl"));

        assertTrue(rayGeneration.contains(
                "vec4(sampleResult.primaryBaseColor, sampleResult.primaryDistance)"));
        assertTrue(rayGeneration.contains("primeNrdNoisySpecular"));
        assertTrue(rayGeneration.contains("private raygen -> NRD-preparation scratch contract"));
        assertTrue(preparation.contains("primeNrdMaterialFactors("));
        assertTrue(preparation.contains("primeNrdPackRadianceAndHitDistance("));
        assertTrue(preparation.contains("primeNrdPackNormalRoughness("));
        assertTrue(preparation.contains("vec4(diffuseMaterialFactor, primaryDistance)"));
        assertTrue(preparation.contains("uint diagnosticMode;"));
        assertTrue(preparation.contains(
                "if (primeMotionPush.diagnosticMode != PRIME_DIAGNOSTIC_REPROJECTION_ERROR)"));
        int primaryMaterialLoad = preparation.indexOf(
                "vec4 rawMaterial = imageLoad(primeMaterial, pixel)");
        int primaryMiss = preparation.indexOf(
                "if (primaryDistance < 0.0)", primaryMaterialLoad);
        int primarySurfaceLoads = preparation.indexOf(
                "vec4 primary = imageLoad(primePrimaryPosition, pixel)", primaryMiss);
        assertTrue(primaryMaterialLoad >= 0
                && primaryMiss > primaryMaterialLoad
                && primarySurfaceLoads > primaryMiss);
        assertTrue(composite.contains("if (material.a < 0.0)"));
        assertTrue(composite.contains("return vec3(0.0);"));
        assertTrue(composite.contains("primeCompositeSurfaceSignal("));
        assertTrue(composite.contains("primeDenoisedSpecular"));
        // One declaration and one load: composition alpha and specular RGB share the same fetch.
        assertEquals(2, occurrences(composite, "primeCompositeSpecularMaterial"));
        assertTrue(opaqueAnyHit.contains("primeMaterialIsTransmissive"));
        assertTrue(integrator.contains("primeTraceSurfaceWithSbtOffset(path.traceOrigin, path.rayDirection, 2u)"));
        assertTrue(transparent.contains("primeTraceFirstInterfaceBranch("));
        assertTrue(transparent.contains("bool splitSmoothInterface"));
        assertTrue(transparent.contains("surface.materialFlags) == 0.0"));
        assertTrue(transparent.contains("if (splitSmoothInterface)"));
        assertTrue(transparent.contains("splitSmoothInterface ? 2u : 1u"));
        assertTrue(transparent.contains("if (!splitInterface)"));
        assertTrue(transparent.contains("primeTransparentReflectionNoisy"));
        assertTrue(transparent.contains("primeTransparentReflectionSpecular"));
        assertTrue(transparent.contains("primeTransparentTransmissionNoisy"));
        assertTrue(transparent.contains("primeTransparentTransmissionSpecular"));
        int transparentMain = transparent.indexOf("void main() {");
        int primaryTrace = transparent.indexOf("primeTraceSurface(", transparentMain);
        int earlyExit = transparent.indexOf("if (surface.hitKind", primaryTrace);
        int conditionalClear = transparent.indexOf(
                "primeClearTransparentBranches(ivec2(pixel))", earlyExit);
        assertTrue(transparentMain >= 0 && primaryTrace > transparentMain);
        assertTrue(!transparent.substring(transparentMain, primaryTrace).contains("imageStore("));
        assertTrue(earlyExit > primaryTrace && conditionalClear > earlyExit);
        assertTrue(transparent.contains("result.guidePosition = surface.position"));
        assertTrue(transparent.contains(
                "firstInterface.position - primePush.cameraPosition"));
        assertTrue(transparent.contains("vec4(visibleRadiance, -(max(surface.t, 0.0) + 1.0))"));
        assertTrue(transparent.contains("imageStore(primeFsrTransparencyCompositionMask"));
        assertTrue(transparentPreparation.contains("if (metadata.a < 0.0)"));
        int transparentMetadataLoad = transparentPreparation.indexOf(
                "vec4 metadata = imageLoad(primeMetadata, pixel)");
        int transparentMiss = transparentPreparation.indexOf(
                "if (metadata.a < 0.0)", transparentMetadataLoad);
        int transparentSurfaceLoads = transparentPreparation.indexOf(
                "vec4 rawDiffuse = imageLoad(primeNoisyDiffuse, pixel)", transparentMiss);
        assertTrue(transparentMetadataLoad >= 0
                && transparentMiss > transparentMetadataLoad
                && transparentSurfaceLoads > transparentMiss);
        assertTrue(integrator.contains("struct PrimeDeltaChain"));
        assertTrue(integrator.contains("PRIME_DELTA_CHAIN_CAPACITY = 8u"));
        assertTrue(integrator.contains("primeAppendDeltaInterface(deltaChain, surface, bsdf)"));
        assertTrue(transparent.contains("primeBuildDeltaVirtualGuide("));
        assertTrue(transparent.contains("primeRelaxPreviousDeltaInterface("));
        assertTrue(transparent.contains("previousVirtualPosition - result.currentVirtualPosition"));
        assertTrue(transparentPreparation.contains(
                "previousVirtualPosition = currentVirtualPosition + interfaceData.xyz"));
        assertTrue(transparentPreparation.contains("vec2 currentCameraJitter"));
        assertTrue(transparentPreparation.contains("previousUv - currentSampleUv"));
        assertTrue(transparentPreparation.contains("primeNrdMaterialFactors("));
        assertTrue(transparentPreparation.contains("primeNrdPackRadianceAndHitDistance("));
        assertTrue(transparentComposite.contains("primeReflectionDenoised"));
        assertTrue(transparentComposite.contains("primeReflectionSpecularDenoised"));
        assertTrue(transparentComposite.contains("primeTransmissionDenoised"));
        assertTrue(transparentComposite.contains("primeTransmissionSpecularDenoised"));
        assertTrue(transparentComposite.contains("primeOpaqueValidation"));
        assertTrue(transparentComposite.contains("primeReflectionValidation"));
        assertTrue(transparentComposite.contains("primeTransmissionValidation"));
        int validationSelection = transparentComposite.indexOf(
                "if (validationSource != PRIME_VALIDATION_OFF)");
        int completedSceneLoad = transparentComposite.indexOf(
                "vec4 scene = imageLoad(primeTransparentSceneColor", validationSelection);
        assertTrue(validationSelection >= 0 && completedSceneLoad > validationSelection);
        assertTrue(transparentComposite.contains("metadata.a < 0.0"));
        assertTrue(integrator.contains("primary-surface-replacement contract"));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int offset = source.indexOf(needle); offset >= 0;
                offset = source.indexOf(needle, offset + needle.length())) {
            count++;
        }
        return count;
    }
}
