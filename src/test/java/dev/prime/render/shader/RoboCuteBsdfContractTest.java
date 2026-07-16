package dev.prime.render.shader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

final class RoboCuteBsdfContractTest {
    private static final int GGX_LUT_RESOLUTION = 32;
    private static final int GGX_LUT_CHANNELS = 4;
    private static final String GGX_LUT_SHA256 =
            "2d655ae640d7f57da3ad0609d92fde55df1d9de18e9fbc19fa9aa2adbb0d8df4";

    @Test
    void completeClosureFamilyAndImportanceSamplersRemainBuildChecked() throws IOException {
        String common = shader("robocute_bsdf_common.glsl");
        String fresnel = shader("robocute_bsdf_fresnel.glsl");
        String microfacet = shader("robocute_bsdf_microfacet.glsl");
        String closures = shader("robocute_bsdf_closures.glsl");
        String openPbr = shader("robocute_bsdf_openpbr.glsl");
        String validation = shader("robocute_bsdf_validation.comp");

        assertTrue(common.contains("value is f(wi, wo) * abs(wo.z)"));
        assertTrue(common.contains("PRIME_RC_MAX_VOLUME_STACK_SIZE = 2u"));
        assertTrue(fresnel.contains("primeRcThinFilmSensitivity"));
        assertTrue(fresnel.contains("primeRcSpecularFresnelRt"));
        assertTrue(microfacet.contains("primeRcMicrofacetDirectionalAlbedoTransmission"));
        assertTrue(microfacet.contains("primeRcBeta"));
        assertTrue(microfacet.contains("primeRcReflectiveSampleBase"));
        assertTrue(microfacet.contains("primeRcRefractiveSample"));

        assertClosure(closures, "Diffuse");
        assertClosure(closures, "Lambert");
        assertClosure(closures, "Specular");
        assertClosure(closures, "Conductor");
        assertClosure(closures, "Coat");
        assertClosure(closures, "Fuzz");
        assertClosure(closures, "Subsurface");
        assertClosure(closures, "Transmission");
        assertClosure(closures, "Diffraction");
        assertTrue(closures.contains("primeRcLambertTintOut"));
        assertTrue(closures.contains("primeRcLambertTrans"));
        assertTrue(closures.contains("primeRcMicrofacetDirectionalAlbedoUnity"));
        assertTrue(closures.contains("primeRcVolumeFromTransmission"));
        assertTrue(closures.contains("primeRcVolumeFromSubsurface"));

        assertTrue(openPbr.contains("primeRcMakeMixState"));
        assertTrue(openPbr.contains("primeRcMakeLayerState"));
        assertTrue(openPbr.contains("primeRcOpenPbrEvaluate"));
        assertTrue(openPbr.contains("primeRcOpenPbrSample"));
        assertTrue(openPbr.contains("primeRcBasicMetallicEvaluate"));
        assertTrue(openPbr.contains("primeRcBasicMetallicSample"));
        assertComposite(openPbr, "BasicGlossy");
        assertComposite(openPbr, "BasicMetallic");
        assertComposite(openPbr, "SubsurfaceGlossy");
        assertTrue(openPbr.contains("primeRcTransmissionEvaluate"));
        assertTrue(openPbr.contains("primeRcTransmissionStateInit"));
        assertTrue(validation.contains("primeRcOpenPbrStateInit"));
        assertTrue(validation.contains("primeRcOpenPbrEvaluate"));
        assertTrue(validation.contains("primeRcOpenPbrSample"));
        assertTrue(validation.contains("primeRcBasicMetallicStateInit"));
        assertTrue(validation.contains("primeRcSubsurfaceGlossyStateInit"));
        assertTrue(validation.contains("primeRcTransmissionStateInit"));
        assertFalse(openPbr.contains("TODO"));
    }

    @Test
    void transmissionGgxEnergyTableIsExactAndFinite()
            throws IOException, NoSuchAlgorithmException {
        Path encodedPath = Path.of(
                System.getProperty("user.dir"),
                "src", "client", "resources", "prime", "bsdf",
                "trans_ggx.bytes.gz.b64");
        byte[] compressed = Base64.getMimeDecoder().decode(Files.readString(encodedPath));
        byte[] decoded;
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            decoded = output.toByteArray();
        }
        assertEquals(
                GGX_LUT_RESOLUTION * GGX_LUT_RESOLUTION * GGX_LUT_RESOLUTION
                        * GGX_LUT_CHANNELS * Float.BYTES,
                decoded.length);
        assertEquals(
                GGX_LUT_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(decoded)));

        ByteBuffer values = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN);
        while (values.hasRemaining()) {
            float value = values.getFloat();
            assertTrue(Float.isFinite(value));
            assertTrue(value >= 0.0F && value <= 1.0F);
        }
    }

    @Test
    void transmissionClosureIsConnectedToTerrainWithPersistentVolumes() throws IOException {
        String adapter = shader("bsdf.glsl");
        String defaultMaterial = shader("default_material.glsl");
        String integrator = shader("integrator.glsl");
        String closestHit = shader("world.rchit");
        String anyHit = shader("world.rahit");

        assertTrue(adapter.contains(
                "PRIME_RC_TRANSMISSION_GGX_BINDING PRIME_DESCRIPTOR_TRANSMISSION_GGX_ENERGY"));
        assertTrue(adapter.contains("primeRcTransmissionEvaluate"));
        assertTrue(adapter.contains("primeRcTransmissionSample"));
        assertTrue(adapter.contains("primeMinecraftMirrorSplit"));
        assertTrue(adapter.contains("primeSampleMinecraftTransmissionBranch"));
        assertTrue(adapter.contains("primeRcMicrofacetDirectionalAlbedoTransmission"));
        assertTrue(adapter.contains("state.samplingFlags = PRIME_RC_FLAG_TRANSMISSION"));
        assertTrue(adapter.contains("reflect(-viewDirection, outwardNormal)"));
        assertTrue(adapter.contains("result.bsdfSample.weight = mirror.reflectance"));
        assertTrue(adapter.contains("state.samplingFlags = reflectionBranch"));
        assertTrue(adapter.contains("/ sampled.bsdfSample.pdf"));
        assertTrue(adapter.contains(
                "result.bsdfSample.eventFlags = PRIME_BSDF_EVENT_REFLECTION"));
        assertTrue(adapter.contains("result.volumeStack = sampled.volumeStack"));
        assertTrue(adapter.contains("primeCameraWaterVolumeStack"));
        assertTrue(adapter.contains("PRIME_GLASS_MINIMUM_TINT_WEIGHT = 0.75"));
        assertTrue(adapter.contains("PRIME_WATER_REFERENCE_DEPTH = 16.0"));
        assertTrue(adapter.contains("decodedColor / peak"));
        assertTrue(defaultMaterial.contains(
                "visible interface is split into deterministic reflection and"));
        assertTrue(defaultMaterial.contains("return 0.0;"));
        assertTrue(integrator.contains("PrimeRcVolumeStack volumeStack"));
        assertTrue(integrator.contains("volumeStack = transmitted.volumeStack"));
        assertTrue(integrator.contains("exp(-medium.extinction * max(surface.t, 0.0))"));
        assertTrue(integrator.contains(
                "(primePush.path.z & PRIME_PATH_CAMERA_IN_WATER_MASK) != 0u"));
        assertTrue(closestHit.contains("primePayload.geometricNormal = normal"));
        assertFalse(closestHit.contains("normal = -normal"));
        assertTrue(anyHit.contains("primeMaterialIsTransmissive(primitive.flags)"));
    }

    @Test
    void foliageUsesAlphaTestedOpenPbrThinWallsWithoutVolumeTransitions() throws IOException {
        String defaults = shader("default_material.glsl");
        String adapter = shader("bsdf.glsl");
        String integrator = shader("integrator.glsl");
        String anyHit = shader("world.rahit");

        assertTrue(defaults.contains("PRIME_MATERIAL_FLAG_FOLIAGE = 32u"));
        assertTrue(adapter.contains("PRIME_FOLIAGE_TRANSMISSION_WEIGHT = 0.15"));
        assertTrue(adapter.contains("material.geometry.thinWalled = 1u"));
        assertTrue(adapter.contains("primeRcOpenPbrEvaluate"));
        assertTrue(adapter.contains("primeRcOpenPbrSample"));
        assertTrue(integrator.contains("primeMaterialIsFoliage(surface.materialFlags)"));
        assertTrue(integrator.contains("primeSampleMinecraftFoliage"));
        assertTrue(anyHit.contains("opacity < PRIME_CUTOUT_ALPHA_THRESHOLD"));
    }

    private static String shader(String name) throws IOException {
        return Files.readString(Path.of(System.getProperty("user.dir"), "shaders", name));
    }

    private static void assertClosure(String source, String name) {
        assertTrue(source.contains("primeRc" + name + "Eval"), name + " eval");
        assertTrue(source.contains("primeRc" + name + "Sample"), name + " sample");
        assertTrue(source.contains("primeRc" + name + "Pdf"), name + " pdf");
        assertTrue(source.contains("primeRc" + name + "Energy"), name + " energy");
    }

    private static void assertComposite(String source, String name) {
        assertTrue(source.contains("primeRc" + name + "Eval"), name + " eval");
        assertTrue(source.contains("primeRc" + name + "Sample"), name + " sample");
        assertTrue(source.contains("primeRc" + name + "Pdf"), name + " pdf");
        assertTrue(source.contains("primeRc" + name + "TintOut"), name + " tint out");
        assertTrue(source.contains("primeRc" + name + "Trans"), name + " trans");
        assertTrue(source.contains("primeRc" + name + "Energy"), name + " energy");
    }
}
