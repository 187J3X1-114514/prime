package dev.prime.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

final class RealtimeSampleStateTest {
    private static final SunDirection NOON = SunDirection.fromVanillaAngle(0.0F);

    @Test
    void stableCommittedFramesAdvanceWithoutReset() {
        RealtimeSampleState state = RealtimeSampleState.initial();
        FrameCamera camera = camera(1.0);
        RealtimeSampleState.Plan first = state.plan(input(camera, 3L, 11L, NOON, false));
        assertTrue(first.reset());
        assertEquals(0, first.sampleIndex());
        int epoch = first.epoch();

        RealtimeSampleState.Plan second =
                first.committedState().plan(input(camera, 3L, 11L, NOON, false));
        assertFalse(second.reset());
        assertEquals(1, second.sampleIndex());
        assertEquals(epoch, second.epoch());
    }

    @Test
    void unsubmittedPlansDoNotConsumeSamples() {
        RealtimeSampleState state = RealtimeSampleState.initial();
        FrameCamera camera = camera(1.0);
        RealtimeSampleState.Plan first = state.plan(input(camera, 1L, 2L, NOON, false));
        RealtimeSampleState.Plan retried = state.plan(input(camera, 1L, 2L, NOON, false));

        assertEquals(first.sampleIndex(), retried.sampleIndex());
        assertEquals(first.epoch(), retried.epoch());
        assertTrue(retried.reset());
        RealtimeSampleState.Plan firstContinuation =
                first.committedState().plan(input(camera, 1L, 2L, NOON, false));
        RealtimeSampleState.Plan retryContinuation =
                retried.committedState().plan(input(camera, 1L, 2L, NOON, false));
        assertEquals(firstContinuation.sampleIndex(), retryContinuation.sampleIndex());
        assertEquals(firstContinuation.epoch(), retryContinuation.epoch());
    }

    @Test
    void allTransportIdentityChangesResetSequence() {
        FrameCamera camera = camera(1.0);
        RealtimeSampleState state = commit(
                RealtimeSampleState.initial(), input(camera, 1L, 2L, NOON, false));

        RealtimeSampleState.Plan world =
                state.plan(input(camera, 2L, 2L, NOON, false));
        assertTrue(world.reset());
        state = world.committedState();

        RealtimeSampleState.Plan ordinaryMotion =
                state.plan(input(camera(2.0), 2L, 2L, NOON, false));
        assertFalse(ordinaryMotion.reset());
        state = ordinaryMotion.committedState();
        assertEquals(2, state.sampleIndex());

        RealtimeSampleState.Plan cameraCut =
                state.plan(input(camera(35.0), 2L, 2L, NOON, false));
        assertTrue(cameraCut.reset());
        state = cameraCut.committedState();

        RealtimeSampleState.Plan atlas =
                state.plan(input(camera(35.0), 2L, 4L, NOON, false));
        assertTrue(atlas.reset());
        state = atlas.committedState();

        RealtimeSampleState.Plan lighting =
                state.plan(input(camera(35.0), 2L, 4L, 6L, 7L, NOON, false, false));
        assertTrue(lighting.reset());
        state = lighting.committedState();

        RealtimeSampleState.Plan material =
                state.plan(input(camera(35.0), 2L, 4L, 6L, 8L, NOON, false, false));
        assertTrue(material.reset());
        state = material.committedState();

        RealtimeSampleState.Plan medium =
                state.plan(input(camera(35.0), 2L, 4L, 6L, 8L, NOON, true, false));
        assertTrue(medium.reset());
        state = medium.committedState();

        RealtimeSampleState.Plan forced =
                state.plan(input(camera(35.0), 2L, 4L, 6L, 8L, NOON, true, true));
        assertTrue(forced.reset());
        assertEquals(0, forced.sampleIndex());

        RealtimeSampleState invalidated = state.invalidated();
        assertEquals(state.sampleIndex(), invalidated.sampleIndex());
        assertEquals(state.epoch(), invalidated.epoch());
        RealtimeSampleState.Plan invalidation =
                invalidated.plan(input(camera(35.0), 2L, 4L, 6L, 8L, NOON, true, false));
        assertTrue(invalidation.reset());
        assertEquals(0, invalidation.sampleIndex());
        assertEquals(state.epoch() + 1, invalidation.epoch());
    }

    @Test
    void repeatedInvalidationIsConsumedOnceByTheNextPlan() {
        FrameCamera camera = camera(1.0);
        RealtimeSampleState state = commit(
                RealtimeSampleState.initial(), input(camera, 1L, 2L, NOON, false));

        RealtimeSampleState invalidated = state.invalidated().invalidated();
        RealtimeSampleState.Plan reset =
                invalidated.plan(input(camera, 1L, 2L, NOON, false));
        assertTrue(reset.reset());
        assertEquals(0, reset.sampleIndex());
        assertEquals(state.epoch() + 1, reset.epoch());

        RealtimeSampleState.Plan continuation =
                reset.committedState().plan(input(camera, 1L, 2L, NOON, false));
        assertFalse(continuation.reset());
        assertEquals(1, continuation.sampleIndex());
        assertEquals(reset.epoch(), continuation.epoch());
    }

    @Test
    void gradualSunMotionKeepsAdvancingTheSampleSequence() {
        FrameCamera camera = camera(1.0);
        RealtimeSampleState state = commit(
                RealtimeSampleState.initial(), input(camera, 1L, 2L, NOON, false));
        int epoch = state.epoch();

        for (int step = 1; step <= 20; step++) {
            SunDirection movingSun =
                    SunDirection.fromVanillaAngle((float) Math.toRadians(step * 0.01));
            RealtimeSampleState.Plan plan =
                    state.plan(input(camera, 1L, 2L, movingSun, false));
            assertFalse(plan.reset());
            assertEquals(epoch, plan.epoch());
            state = plan.committedState();
            assertEquals(step + 1, state.sampleIndex());
        }
    }

    @Test
    void changingSunDirectionInvalidatesAccumulatedRadiance() {
        FrameCamera camera = camera(1.0);
        RealtimeSampleState state = commit(
                RealtimeSampleState.initial(), input(camera, 1L, 2L, NOON, false));
        int epoch = state.epoch();

        SunDirection sunset = SunDirection.fromVanillaAngle((float) (Math.PI * 0.5));
        RealtimeSampleState.Plan plan =
                state.plan(input(camera, 1L, 2L, sunset, false));
        assertTrue(plan.reset());
        assertEquals(0, plan.sampleIndex());
        assertEquals(epoch + 1, plan.epoch());
    }

    @Test
    void sobolSequenceStartsANewEpochBeforeItsSixteenBitIndexRepeats() {
        FrameCamera camera = camera(1.0);
        RealtimeSampleState state = RealtimeSampleState.initial();
        int initialEpoch = -1;
        for (int index = 0; index < (1 << 16); index++) {
            RealtimeSampleState.Plan plan =
                    state.plan(input(camera, 1L, 2L, NOON, false));
            if (index == 0) {
                initialEpoch = plan.epoch();
            } else {
                assertFalse(plan.reset());
            }
            assertEquals(index, plan.sampleIndex());
            state = plan.committedState();
        }

        RealtimeSampleState.Plan wrapped =
                state.plan(input(camera, 1L, 2L, NOON, false));
        assertFalse(wrapped.reset());
        assertEquals(0, wrapped.sampleIndex());
        assertEquals(initialEpoch + 1, wrapped.epoch());
    }

    private static RealtimeSampleState commit(
            RealtimeSampleState state, RealtimeSampleState.Input input) {
        return state.plan(input).committedState();
    }

    private static RealtimeSampleState.Input input(
            FrameCamera camera,
            long revision,
            long textureRevision,
            SunDirection sun,
            boolean forceReset) {
        return new RealtimeSampleState.Input(
                camera,
                revision,
                textureRevision,
                5L,
                7L,
                sun,
                false,
                forceReset);
    }

    private static RealtimeSampleState.Input input(
            FrameCamera camera,
            long revision,
            long textureRevision,
            long lightingRevision,
            long materialRevision,
            SunDirection sun,
            boolean cameraInWater,
            boolean forceReset) {
        return new RealtimeSampleState.Input(
                camera,
                revision,
                textureRevision,
                lightingRevision,
                materialRevision,
                sun,
                cameraInWater,
                forceReset);
    }

    private static FrameCamera camera(double x) {
        return new FrameCamera(new Matrix4f(), x, 2.0, 3.0);
    }
}
