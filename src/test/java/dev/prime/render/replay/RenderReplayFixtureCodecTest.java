package dev.prime.render.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RenderReplayFixtureCodecTest {
    @Test
    void fixtureRoundTripsCanonically() {
        RenderReplaySequence sequence = new RenderReplaySequence(List.of(
                NrdInputSemanticValidatorTest.capture(false, false)));
        byte[] encoded = RenderReplayFixtureCodec.encode(sequence);

        RenderReplaySequence decoded =
                RenderReplayFixtureCodec.decode(encoded);

        assertArrayEquals(
                encoded, RenderReplayFixtureCodec.encode(decoded));
    }

    @Test
    void unknownVersionIsRejected() {
        byte[] encoded = RenderReplayFixtureCodec.encode(
                new RenderReplaySequence(List.of(
                        NrdInputSemanticValidatorTest.capture(false, false))));
        encoded[Integer.BYTES] ^= 1;

        assertThrows(
                IllegalArgumentException.class,
                () -> RenderReplayFixtureCodec.decode(encoded));
    }

    @Test
    void truncatedEnvelopeIsRejected() {
        byte[] encoded = RenderReplayFixtureCodec.encode(
                new RenderReplaySequence(List.of(
                        NrdInputSemanticValidatorTest.capture(false, false))));

        assertThrows(
                IllegalArgumentException.class,
                () -> RenderReplayFixtureCodec.decode(
                        Arrays.copyOf(encoded, encoded.length - 1)));
    }
}
