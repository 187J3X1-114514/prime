package dev.prime.render.fsr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class FsrDispatchValidatorTest {
    @Test
    void acceptsPrimeNormalizedMotionAndFixedExposureContract() {
        assertDoesNotThrow(() -> FsrDispatchValidator.validate(
                2560, 1440, 3840, 2160,
                new FsrSettings.Jitter(-0.25F, 1.0F / 6.0F),
                1.0F, 2560.0F, 1440.0F));
    }

    @Test
    void rejectsInvalidTemporalInputsBeforeGpuDispatch() {
        assertThrows(IllegalArgumentException.class, () -> FsrDispatchValidator.validate(
                3841, 2160, 3840, 2160,
                new FsrSettings.Jitter(0.0F, 0.0F),
                1.0F, 3841.0F, 2160.0F));
        assertThrows(IllegalArgumentException.class, () -> FsrDispatchValidator.validate(
                1920, 1080, 3840, 2160,
                new FsrSettings.Jitter(0.75F, 0.0F),
                1.0F, 1920.0F, 1080.0F));
        assertThrows(IllegalArgumentException.class, () -> FsrDispatchValidator.validate(
                1920, 1080, 3840, 2160,
                new FsrSettings.Jitter(0.0F, 0.0F),
                0.0F, 1920.0F, 1080.0F));
        assertThrows(IllegalArgumentException.class, () -> FsrDispatchValidator.validate(
                1920, 1080, 3840, 2160,
                new FsrSettings.Jitter(0.0F, 0.0F),
                1.0F, 1.0F, 1.0F));
    }
}
