package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NrdJitterPhaseAnalyzerTest {
    private static final int WIDTH = 96;
    private static final int HEIGHT = 72;
    private static final float[][] JITTER = {
        {0.0F, -1.0F / 6.0F},
        {-0.25F, 1.0F / 6.0F},
        {0.25F, -7.0F / 18.0F},
        {-0.375F, -1.0F / 18.0F},
        {0.125F, 5.0F / 18.0F},
        {-0.125F, -5.0F / 18.0F},
        {0.375F, 1.0F / 18.0F},
        {-0.4375F, 7.0F / 18.0F}
    };

    @Test
    void matchingBoundaryAndInteriorRecoverUnitJitterGain() {
        NrdJitterPhaseAnalyzer.Report report =
                NrdJitterPhaseAnalyzer.analyzeFrames(
                        frames(1.0F, 1.0F));

        assertTrue(report.measurable(), report.toString());
        assertTrue(report.matched(), report.toString());
        assertEquals(1.0, report.boundary().gain(), 0.18);
        assertEquals(1.0, report.interior().gain(), 0.18);
        assertEquals(1.0, report.amplitudeRatio(), 0.18);
    }

    @Test
    void dampedInteriorIsRejectedAgainstFullAmplitudeBoundary() {
        NrdJitterPhaseAnalyzer.Report report =
                NrdJitterPhaseAnalyzer.analyzeFrames(
                        frames(1.0F, 0.25F));

        assertTrue(report.measurable(), report.toString());
        assertFalse(report.matched(), report.toString());
        assertTrue(
                report.boundary().gain()
                        > report.interior().gain() * 2.0,
                report.toString());
        assertTrue(report.amplitudeRatio() > 2.0, report.toString());
    }

    @Test
    void dampedBoundaryIsMeasuredInsteadOfAssumedFromInputJitter() {
        NrdJitterPhaseAnalyzer.Report report =
                NrdJitterPhaseAnalyzer.analyzeFrames(
                        frames(0.4F, 1.0F));

        assertTrue(report.measurable(), report.toString());
        assertFalse(report.matched(), report.toString());
        assertEquals(0.4, report.boundary().gain(), 0.05);
        assertTrue(report.amplitudeRatio() < 0.6, report.toString());
    }

    @Test
    void uncorrelatedTemporalNoiseDoesNotMasqueradeAsPhaseError() {
        NrdJitterPhaseAnalyzer.Report report =
                NrdJitterPhaseAnalyzer.analyzeFrames(
                        frames(1.0F, 1.0F, 0.08F));

        assertTrue(report.measurable(), report.toString());
        assertTrue(report.matched(), report.toString());
    }

    private static List<NrdJitterPhaseAnalyzer.PhaseFrame> frames(
            float boundaryGain, float interiorGain) {
        return frames(boundaryGain, interiorGain, 0.0F);
    }

    private static List<NrdJitterPhaseAnalyzer.PhaseFrame> frames(
            float boundaryGain,
            float interiorGain,
            float noiseAmplitude) {
        ArrayList<NrdJitterPhaseAnalyzer.PhaseFrame> frames =
                new ArrayList<>(JITTER.length);
        for (int frame = 0; frame < JITTER.length; frame++) {
            float[] jitter = JITTER[frame];
            float[] color = new float[WIDTH * HEIGHT * 3];
            boolean[] transmissive = new boolean[WIDTH * HEIGHT];
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    float boundaryX = x + boundaryGain * jitter[0];
                    float boundaryY = y + boundaryGain * jitter[1];
                    boolean glass = boundaryX >= 14.0F
                            && boundaryX < WIDTH - 13.0F
                            && boundaryY >= 10.0F
                            && boundaryY < HEIGHT - 9.0F;
                    int pixel = y * WIDTH + x;
                    transmissive[pixel] = glass;
                    float sampleX = x + interiorGain * jitter[0];
                    float sampleY = y + interiorGain * jitter[1];
                    float pattern = 0.45F
                            + 0.18F * (float) Math.sin(sampleX * 0.42F)
                            + 0.14F * (float) Math.cos(sampleY * 0.37F);
                    int hash = Integer.rotateLeft(
                            pixel * 0x9e37_79b9, frame + 3);
                    float noise = noiseAmplitude
                            * (((hash >>> 8) & 0xffff) / 65_535.0F - 0.5F);
                    float value = glass ? pattern : 0.04F;
                    value += glass ? noise : 0.0F;
                    color[pixel * 3] = value;
                    color[pixel * 3 + 1] = value * 0.8F;
                    color[pixel * 3 + 2] = value * 0.6F;
                }
            }
            frames.add(new NrdJitterPhaseAnalyzer.PhaseFrame(
                    WIDTH,
                    HEIGHT,
                    jitter[0],
                    jitter[1],
                    boundaryGain * jitter[0],
                    boundaryGain * jitter[1],
                    64,
                    color,
                    transmissive));
        }
        return frames;
    }
}
