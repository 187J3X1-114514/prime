package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.AstronomySettings;
import dev.prime.render.AstronomyState;
import dev.prime.render.SunDirection;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SunShadowClipmapTest {
    @Test
    void twoToOneCascadesCoverOneKilometreWithAFarGuard() {
        assertEquals(0.5F, SunShadowClipmap.texelSize(0));
        assertEquals(1.0F, SunShadowClipmap.texelSize(1));
        assertEquals(2.0F, SunShadowClipmap.texelSize(2));
        assertEquals(4.0F, SunShadowClipmap.texelSize(3));
        assertEquals(8.0F, SunShadowClipmap.texelSize(4));
        assertEquals(128.0F, SunShadowClipmap.cascadeRadius(0));
        assertEquals(1_024.0F, SunShadowClipmap.cascadeRadius(3));
        assertEquals(2_048.0F, SunShadowClipmap.cascadeRadius(4));
        assertEquals(3, SunShadowClipmap.cascadeForProjectedDistance(1_000.0F));
        assertEquals(4, SunShadowClipmap.cascadeForProjectedDistance(1_500.0F));
        assertEquals(-1, SunShadowClipmap.cascadeForProjectedDistance(2_049.0F));
    }

    @Test
    void unknownDepthCanOnlyRemoveSunlight() {
        assertFalse(SunShadowClipmap.conservativeVisibility(
                100.0F, SunShadowClipmap.UNKNOWN_DEPTH));
        assertTrue(SunShadowClipmap.conservativeVisibility(
                100.0F, SunShadowClipmap.NO_HIT_DEPTH));
        assertFalse(SunShadowClipmap.conservativeVisibility(4.0F, 5.0F));
        assertTrue(SunShadowClipmap.conservativeVisibility(5.0F, 5.0F));
    }

    @Test
    void initialBuildCoversAllFourTilesTouchingTheCamera() {
        assertEquals(
                Set.of(5, 6, 9, 10),
                Set.of(
                        SunShadowClipmap.primaryTileForBuild(0),
                        SunShadowClipmap.primaryTileForBuild(1),
                        SunShadowClipmap.primaryTileForBuild(2),
                        SunShadowClipmap.primaryTileForBuild(3)));
    }

    @Test
    void streamedDirtyTilesRemainRepairableBeforeTheBankIsPublished() {
        assertFalse(SunShadowClipmap.readyForDirtyRepair(
                true, SunShadowClipmap.TILE_COUNT - 1));
        assertTrue(SunShadowClipmap.readyForDirtyRepair(
                true, SunShadowClipmap.TILE_COUNT));
        assertFalse(SunShadowClipmap.readyForDirtyRepair(
                false, SunShadowClipmap.TILE_COUNT));
    }

    @Test
    void streamedChangesNeverInvalidateThePublishedBank() {
        assertFalse(SunShadowClipmap.deferInvalidation(0, 0));
        assertFalse(SunShadowClipmap.deferInvalidation(1, 1));
        assertTrue(SunShadowClipmap.deferInvalidation(1, 0));
    }

    @Test
    void lightSpaceBasisRemainsStableThroughTheSolarZenith() {
        AstronomySettings equatorAtEquinox =
                new AstronomySettings(0, 0);
        SunDirection before = AstronomyState.atSolarHourAngle(
                -0.01F, equatorAtEquinox).sunDirection();
        SunDirection after = AstronomyState.atSolarHourAngle(
                0.01F, equatorAtEquinox).sunDirection();

        assertTrue(
                SunShadowClipmap.basisDirectionCosine(before, after) > 0.999F);
    }

    @Test
    void lightSpaceBasisDoesNotFlipWhereTheSunCrossesWorldX() {
        AstronomySettings equatorAtEquinox =
                new AstronomySettings(0, 0);
        SunDirection before = AstronomyState.atSolarHourAngle(
                (float) -Math.asin(0.9985),
                equatorAtEquinox).sunDirection();
        SunDirection after = AstronomyState.atSolarHourAngle(
                (float) -Math.asin(0.9995),
                equatorAtEquinox).sunDirection();

        assertTrue(
                SunShadowClipmap.basisDirectionCosine(before, after) > 0.999F);
    }
}
