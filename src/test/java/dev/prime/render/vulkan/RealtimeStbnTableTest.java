package dev.prime.render.vulkan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.prime.render.shader.ShaderAbi;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class RealtimeStbnTableTest {
    private static final int WIDTH = ShaderAbi.REALTIME_STBN_WIDTH;
    private static final int HEIGHT = ShaderAbi.REALTIME_STBN_HEIGHT;
    private static final int DEPTH = ShaderAbi.REALTIME_STBN_DEPTH;
    private static final int BANK_COUNT = ShaderAbi.REALTIME_STBN_BANK_COUNT;
    private static final int POINTS_PER_FRAME = WIDTH * HEIGHT;
    private static final int POINTS_PER_BANK = POINTS_PER_FRAME * DEPTH;

    @Test
    void resourceMatchesThePropertyValidatedArtifact() throws Exception {
        byte[] bytes = bytes();
        assertEquals(
                ShaderAbi.REALTIME_STBN_RESOURCE_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test
    void everyFrameAndBankRetainsItsExactDigitalNet() throws Exception {
        ByteBuffer table = table();
        int[] frameOccupancy = new int[POINTS_PER_FRAME];
        int[] bankOccupancy = new int[POINTS_PER_BANK];
        int stamp = 0;
        for (int bank = 0; bank < BANK_COUNT; bank++) {
            for (int frame = 0; frame < DEPTH; frame++) {
                int firstPoint = bank * POINTS_PER_BANK + frame * POINTS_PER_FRAME;
                for (int xBits = 0; xBits <= 14; xBits++) {
                    verifyNet(
                            table,
                            firstPoint,
                            POINTS_PER_FRAME,
                            14,
                            xBits,
                            frameOccupancy,
                            ++stamp);
                }
            }
            int firstPoint = bank * POINTS_PER_BANK;
            for (int xBits = 4; xBits <= 16; xBits++) {
                verifyNet(
                        table,
                        firstPoint,
                        POINTS_PER_BANK,
                        20,
                        xBits,
                        bankOccupancy,
                        ++stamp);
            }
        }
    }

    @Test
    void banksAreIndependentAndIntegerDecodingAvoidsEndpoints() throws Exception {
        ByteBuffer table = table();
        long[] fingerprints = new long[BANK_COUNT];
        int minimum = 0xffff;
        int maximum = 0;
        for (int bank = 0; bank < BANK_COUNT; bank++) {
            long fingerprint = 0xcbf2_9ce4_8422_2325L;
            int firstPoint = bank * POINTS_PER_BANK;
            for (int point = 0; point < POINTS_PER_BANK; point++) {
                int packed = table.getInt((firstPoint + point) * Integer.BYTES);
                fingerprint = (fingerprint ^ Integer.toUnsignedLong(packed))
                        * 0x0000_0100_0000_01b3L;
                minimum = Math.min(minimum, Math.min(packed & 0xffff, packed >>> 16));
                maximum = Math.max(maximum, Math.max(packed & 0xffff, packed >>> 16));
            }
            fingerprints[bank] = fingerprint;
        }
        assertNotEquals(fingerprints[0], fingerprints[1]);
        assertNotEquals(fingerprints[0], fingerprints[2]);
        assertNotEquals(fingerprints[1], fingerprints[2]);
        assertTrue((minimum + 0.5F) / 65_536.0F > 0.0F);
        assertTrue((maximum + 0.5F) / 65_536.0F < 1.0F);
        for (int firstBank = 0; firstBank < BANK_COUNT; firstBank++) {
            for (int secondBank = firstBank + 1; secondBank < BANK_COUNT; secondBank++) {
                for (int firstComponent = 0; firstComponent < 2; firstComponent++) {
                    for (int secondComponent = 0; secondComponent < 2; secondComponent++) {
                        double correlation = correlation(
                                table,
                                firstBank,
                                firstComponent,
                                secondBank,
                                secondComponent);
                        assertTrue(Math.abs(correlation) < 0.01,
                                () -> "bank correlation=" + correlation);
                    }
                }
            }
        }
    }

    @Test
    void thresholdErrorHasSpatialAndTemporalBlueNoiseSpectra() throws Exception {
        ByteBuffer table = table();
        double spatialRatio = spatialLowToHighPower(table);
        double temporalRatio = temporalLowToHighPower(table);
        assertTrue(spatialRatio < 0.30,
                () -> "spatial low/high power ratio=" + spatialRatio);
        assertTrue(temporalRatio < 0.50,
                () -> "temporal low/high power ratio=" + temporalRatio);
    }

    private static double spatialLowToHighPower(ByteBuffer table) {
        double lowPower = 0.0;
        double highPower = 0.0;
        int lowCount = 0;
        int highCount = 0;
        double[] real = new double[POINTS_PER_FRAME];
        double[] imaginary = new double[POINTS_PER_FRAME];
        double[] scratchReal = new double[WIDTH];
        double[] scratchImaginary = new double[WIDTH];
        for (int bank = 0; bank < BANK_COUNT; bank++) {
            for (int frame = 0; frame < DEPTH; frame += 8) {
                int firstPoint = bank * POINTS_PER_BANK + frame * POINTS_PER_FRAME;
                for (int component = 0; component < 2; component++) {
                    for (int threshold : new int[] {16_384, 32_768, 49_152}) {
                        for (int point = 0; point < POINTS_PER_FRAME; point++) {
                            int packed = table.getInt((firstPoint + point) * Integer.BYTES);
                            int value = component == 0 ? packed & 0xffff : packed >>> 16;
                            real[point] = value < threshold
                                    ? 1.0 - threshold / 65_536.0
                                    : -threshold / 65_536.0;
                            imaginary[point] = 0.0;
                        }
                        fft2(real, imaginary, scratchReal, scratchImaginary);
                        for (int y = 0; y < HEIGHT; y++) {
                            int signedY = Math.min(y, HEIGHT - y);
                            for (int x = 0; x < WIDTH; x++) {
                                int signedX = Math.min(x, WIDTH - x);
                                int radiusSquared = signedX * signedX + signedY * signedY;
                                int point = y * WIDTH + x;
                                double power = real[point] * real[point]
                                        + imaginary[point] * imaginary[point];
                                if (radiusSquared >= 1 && radiusSquared < 8 * 8) {
                                    lowPower += power;
                                    lowCount++;
                                } else if (radiusSquared >= 24 * 24
                                        && radiusSquared < 48 * 48) {
                                    highPower += power;
                                    highCount++;
                                }
                            }
                        }
                    }
                }
            }
        }
        return (lowPower / lowCount) / (highPower / highCount);
    }

    private static double temporalLowToHighPower(ByteBuffer table) {
        double lowPower = 0.0;
        double highPower = 0.0;
        int lowCount = 0;
        int highCount = 0;
        double[] real = new double[DEPTH];
        double[] imaginary = new double[DEPTH];
        for (int bank = 0; bank < BANK_COUNT; bank++) {
            for (int y = 0; y < HEIGHT; y += 4) {
                for (int x = 0; x < WIDTH; x += 4) {
                    for (int component = 0; component < 2; component++) {
                        for (int threshold : new int[] {16_384, 32_768, 49_152}) {
                            for (int frame = 0; frame < DEPTH; frame++) {
                                int point = bank * POINTS_PER_BANK
                                        + frame * POINTS_PER_FRAME + y * WIDTH + x;
                                int packed = table.getInt(point * Integer.BYTES);
                                int value = component == 0 ? packed & 0xffff : packed >>> 16;
                                real[frame] = value < threshold
                                        ? 1.0 - threshold / 65_536.0
                                        : -threshold / 65_536.0;
                                imaginary[frame] = 0.0;
                            }
                            fft(real, imaginary);
                            for (int frequency = 1; frequency <= 4; frequency++) {
                                lowPower += real[frequency] * real[frequency]
                                        + imaginary[frequency] * imaginary[frequency];
                                lowCount++;
                            }
                            for (int frequency = 16; frequency < 32; frequency++) {
                                highPower += real[frequency] * real[frequency]
                                        + imaginary[frequency] * imaginary[frequency];
                                highCount++;
                            }
                        }
                    }
                }
            }
        }
        return (lowPower / lowCount) / (highPower / highCount);
    }

    private static void fft2(
            double[] real,
            double[] imaginary,
            double[] scratchReal,
            double[] scratchImaginary) {
        for (int y = 0; y < HEIGHT; y++) {
            System.arraycopy(real, y * WIDTH, scratchReal, 0, WIDTH);
            System.arraycopy(imaginary, y * WIDTH, scratchImaginary, 0, WIDTH);
            fft(scratchReal, scratchImaginary);
            System.arraycopy(scratchReal, 0, real, y * WIDTH, WIDTH);
            System.arraycopy(scratchImaginary, 0, imaginary, y * WIDTH, WIDTH);
        }
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                scratchReal[y] = real[y * WIDTH + x];
                scratchImaginary[y] = imaginary[y * WIDTH + x];
            }
            fft(scratchReal, scratchImaginary);
            for (int y = 0; y < HEIGHT; y++) {
                real[y * WIDTH + x] = scratchReal[y];
                imaginary[y * WIDTH + x] = scratchImaginary[y];
            }
        }
    }

    private static void fft(double[] real, double[] imaginary) {
        int count = real.length;
        for (int index = 1, reversed = 0; index < count; index++) {
            int bit = count >> 1;
            while ((reversed & bit) != 0) {
                reversed ^= bit;
                bit >>= 1;
            }
            reversed ^= bit;
            if (index < reversed) {
                double realValue = real[index];
                real[index] = real[reversed];
                real[reversed] = realValue;
                double imaginaryValue = imaginary[index];
                imaginary[index] = imaginary[reversed];
                imaginary[reversed] = imaginaryValue;
            }
        }
        for (int length = 2; length <= count; length <<= 1) {
            double angle = -2.0 * Math.PI / length;
            double stepReal = Math.cos(angle);
            double stepImaginary = Math.sin(angle);
            for (int first = 0; first < count; first += length) {
                double twiddleReal = 1.0;
                double twiddleImaginary = 0.0;
                for (int offset = 0; offset < length / 2; offset++) {
                    int even = first + offset;
                    int odd = even + length / 2;
                    double oddReal = real[odd] * twiddleReal
                            - imaginary[odd] * twiddleImaginary;
                    double oddImaginary = real[odd] * twiddleImaginary
                            + imaginary[odd] * twiddleReal;
                    real[odd] = real[even] - oddReal;
                    imaginary[odd] = imaginary[even] - oddImaginary;
                    real[even] += oddReal;
                    imaginary[even] += oddImaginary;
                    double nextReal = twiddleReal * stepReal
                            - twiddleImaginary * stepImaginary;
                    twiddleImaginary = twiddleReal * stepImaginary
                            + twiddleImaginary * stepReal;
                    twiddleReal = nextReal;
                }
            }
        }
    }

    private static void verifyNet(
            ByteBuffer table,
            int firstPoint,
            int pointCount,
            int totalBits,
            int xBits,
            int[] occupancy,
            int stamp) {
        int yBits = totalBits - xBits;
        for (int point = 0; point < pointCount; point++) {
            int packed = table.getInt((firstPoint + point) * Integer.BYTES);
            int x = packed & 0xffff;
            int y = packed >>> 16;
            int xBin = xBits == 0 ? 0 : x >>> (16 - xBits);
            int yBin = yBits == 0 ? 0 : y >>> (16 - yBits);
            int bin = (xBin << yBits) | yBin;
            if (occupancy[bin] == stamp) {
                throw new AssertionError("duplicate digital-net bin " + bin
                        + " for partition " + xBits + "+" + yBits);
            }
            occupancy[bin] = stamp;
        }
    }

    private static double correlation(
            ByteBuffer table,
            int firstBank,
            int firstComponent,
            int secondBank,
            int secondComponent) {
        double covariance = 0.0;
        double firstVariance = 0.0;
        double secondVariance = 0.0;
        int firstPoint = firstBank * POINTS_PER_BANK;
        int secondPoint = secondBank * POINTS_PER_BANK;
        for (int point = 0; point < POINTS_PER_BANK; point++) {
            int firstPacked = table.getInt((firstPoint + point) * Integer.BYTES);
            int secondPacked = table.getInt((secondPoint + point) * Integer.BYTES);
            double first = (firstComponent == 0
                    ? firstPacked & 0xffff
                    : firstPacked >>> 16) - 32_767.5;
            double second = (secondComponent == 0
                    ? secondPacked & 0xffff
                    : secondPacked >>> 16) - 32_767.5;
            covariance += first * second;
            firstVariance += first * first;
            secondVariance += second * second;
        }
        return covariance / Math.sqrt(firstVariance * secondVariance);
    }

    private static ByteBuffer table() throws Exception {
        return ByteBuffer.wrap(bytes()).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] bytes() throws Exception {
        byte[] bytes;
        try (InputStream input = RealtimeStbnTableTest.class.getResourceAsStream(
                RealtimeStbnTable.RESOURCE)) {
            assertTrue(input != null, "missing realtime STBN resource");
            bytes = input.readAllBytes();
        }
        assertEquals(RealtimeStbnTable.BYTE_SIZE, bytes.length);
        return bytes;
    }
}
