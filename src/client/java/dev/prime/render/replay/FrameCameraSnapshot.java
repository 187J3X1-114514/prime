package dev.prime.render.replay;

import dev.prime.render.FrameCamera;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Immutable, raw-bit representation of a {@link FrameCamera}. */
public final class FrameCameraSnapshot {
    static final int ENCODED_BYTES = 3 * 16 * Integer.BYTES + 6 * Long.BYTES;
    private static final int MATRIX_WORDS = 16;

    private final int[] projection;
    private final int[] viewRotation;
    private final int[] inverseViewProjection;
    private final long x;
    private final long y;
    private final long z;
    private final long renderX;
    private final long renderY;
    private final long renderZ;

    private FrameCameraSnapshot(
            int[] projection,
            int[] viewRotation,
            int[] inverseViewProjection,
            long x,
            long y,
            long z,
            long renderX,
            long renderY,
            long renderZ) {
        this.projection = requireMatrix(projection, "projection");
        this.viewRotation = requireMatrix(viewRotation, "view rotation");
        this.inverseViewProjection =
                requireMatrix(inverseViewProjection, "inverse view projection");
        this.x = x;
        this.y = y;
        this.z = z;
        this.renderX = renderX;
        this.renderY = renderY;
        this.renderZ = renderZ;
    }

    public static FrameCameraSnapshot capture(FrameCamera camera) {
        Objects.requireNonNull(camera, "camera");
        return new FrameCameraSnapshot(
                matrixBits(camera.projection()),
                matrixBits(camera.viewRotation()),
                matrixBits(camera.inverseViewProjection()),
                Double.doubleToRawLongBits(camera.x()),
                Double.doubleToRawLongBits(camera.y()),
                Double.doubleToRawLongBits(camera.z()),
                Double.doubleToRawLongBits(camera.renderX()),
                Double.doubleToRawLongBits(camera.renderY()),
                Double.doubleToRawLongBits(camera.renderZ()));
    }

    static FrameCameraSnapshot decode(ByteBuffer input) {
        return new FrameCameraSnapshot(
                getMatrix(input),
                getMatrix(input),
                getMatrix(input),
                input.getLong(),
                input.getLong(),
                input.getLong(),
                input.getLong(),
                input.getLong(),
                input.getLong());
    }

    void encode(ByteBuffer output) {
        putMatrix(output, this.projection);
        putMatrix(output, this.viewRotation);
        putMatrix(output, this.inverseViewProjection);
        output.putLong(this.x);
        output.putLong(this.y);
        output.putLong(this.z);
        output.putLong(this.renderX);
        output.putLong(this.renderY);
        output.putLong(this.renderZ);
    }

    public FrameCamera materialize() {
        return new FrameCamera(
                matrix(this.projection),
                matrix(this.viewRotation),
                matrix(this.inverseViewProjection),
                Double.longBitsToDouble(this.x),
                Double.longBitsToDouble(this.y),
                Double.longBitsToDouble(this.z),
                Double.longBitsToDouble(this.renderX),
                Double.longBitsToDouble(this.renderY),
                Double.longBitsToDouble(this.renderZ));
    }

    @Override
    public boolean equals(Object object) {
        if (object == this) {
            return true;
        }
        if (!(object instanceof FrameCameraSnapshot other)) {
            return false;
        }
        return Arrays.equals(this.projection, other.projection)
                && Arrays.equals(this.viewRotation, other.viewRotation)
                && Arrays.equals(
                        this.inverseViewProjection, other.inverseViewProjection)
                && this.x == other.x
                && this.y == other.y
                && this.z == other.z
                && this.renderX == other.renderX
                && this.renderY == other.renderY
                && this.renderZ == other.renderZ;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(this.projection);
        result = 31 * result + Arrays.hashCode(this.viewRotation);
        result = 31 * result + Arrays.hashCode(this.inverseViewProjection);
        result = 31 * result + Long.hashCode(this.x);
        result = 31 * result + Long.hashCode(this.y);
        result = 31 * result + Long.hashCode(this.z);
        result = 31 * result + Long.hashCode(this.renderX);
        result = 31 * result + Long.hashCode(this.renderY);
        return 31 * result + Long.hashCode(this.renderZ);
    }

    private static int[] matrixBits(Matrix4fc matrix) {
        float[] values = matrix.get(new float[MATRIX_WORDS]);
        int[] result = new int[MATRIX_WORDS];
        for (int index = 0; index < result.length; index++) {
            result[index] = Float.floatToRawIntBits(values[index]);
        }
        return result;
    }

    private static Matrix4f matrix(int[] bits) {
        float[] values = new float[MATRIX_WORDS];
        for (int index = 0; index < values.length; index++) {
            values[index] = Float.intBitsToFloat(bits[index]);
        }
        return new Matrix4f().set(values);
    }

    private static int[] requireMatrix(int[] matrix, String label) {
        Objects.requireNonNull(matrix, label);
        if (matrix.length != MATRIX_WORDS) {
            throw new IllegalArgumentException(
                    "Camera " + label + " must contain sixteen floats");
        }
        return matrix.clone();
    }

    private static void putMatrix(ByteBuffer output, int[] matrix) {
        for (int value : matrix) {
            output.putInt(value);
        }
    }

    private static int[] getMatrix(ByteBuffer input) {
        int[] result = new int[MATRIX_WORDS];
        for (int index = 0; index < result.length; index++) {
            result[index] = input.getInt();
        }
        return result;
    }
}
