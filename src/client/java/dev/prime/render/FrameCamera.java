package dev.prime.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Canonical camera data shared by ray generation and temporal reconstruction.
 *
 * <p>Minecraft renders the world with {@code renderedProjection * cameraViewRotation}, but view
 * bob and hurt effects are appended to the projection even though they are affine camera-space
 * transforms. NRD requires a non-jittered projection and a world-to-view matrix. For rigid camera
 * effects Prime therefore decomposes the exact rendered transform into:
 *
 * <ul>
 *   <li>{@code projection}: Minecraft's untouched perspective projection;</li>
 *   <li>{@code viewRotation}: an orthonormal world-to-view rotation with no translation;</li>
 *   <li>{@code renderX/Y/Z}: the effective pinhole, including the affine view-effect offset.</li>
 * </ul>
 *
 * <p>{@code x/y/z} remain the physical Minecraft camera position for terrain streaming and
 * atmospheric altitude. {@code inverseViewProjection} remains the inverse of the exact Mojang
 * transform, so ray directions are bit-for-bit based on the matrix used for world rendering.
 * JOML, GLSL and NRD all use column vectors and column-major storage; no transpose is applied.
 * Minecraft Vulkan depth is [0, 1] reversed-Z (near=1, far=0), and its internal target maps image
 * row zero to NDC y=-1 before the presentation flip.
 */
public record FrameCamera(
        Matrix4f projection,
        Matrix4f viewRotation,
        Matrix4f inverseViewProjection,
        double x,
        double y,
        double z,
        double renderX,
        double renderY,
        double renderZ) {
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    FrameCamera(Matrix4f inverseViewProjection, double x, double y, double z) {
        this(new Matrix4f(), new Matrix4f(), inverseViewProjection, x, y, z, x, y, z);
    }

    static FrameCamera tryCreate(
            Matrix4fc renderedProjection,
            Matrix4fc baseProjection,
            Matrix4fc cameraViewRotation,
            double x,
            double y,
            double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        Matrix4f projectionCopy = new Matrix4f(baseProjection);
        Matrix4f exactViewProjection = new Matrix4f(renderedProjection).mul(cameraViewRotation);
        if (!isInvertible(exactViewProjection) || !isInvertible(projectionCopy)) {
            return null;
        }

        Scratch scratch = SCRATCH.get();
        Matrix4f cameraEffect = scratch.cameraEffect.set(projectionCopy)
                .invert()
                .mul(renderedProjection);
        Matrix4f effectedWorldToView = cameraEffect.mul(cameraViewRotation);
        Matrix4f canonicalWorldToView = new Matrix4f(effectedWorldToView);
        double effectiveX = x;
        double effectiveY = y;
        double effectiveZ = z;
        if (isRigid(effectedWorldToView)) {
            Vector3f cameraOffset = effectedWorldToView.invert()
                    .transformPosition(scratch.cameraOffset.zero());
            if (!cameraOffset.isFinite()) {
                return null;
            }
            effectiveX += cameraOffset.x;
            effectiveY += cameraOffset.y;
            effectiveZ += cameraOffset.z;
            canonicalWorldToView.m30(0.0F).m31(0.0F).m32(0.0F);
        } else {
            // Portal/nausea scaling is not an orthonormal camera. Preserve Mojang's exact rays and
            // the previous NRD fallback rather than pretending the scale is a rigid transform.
            projectionCopy.set(renderedProjection);
            canonicalWorldToView.set(cameraViewRotation);
        }

        Matrix4f inverse = exactViewProjection.invert();
        return inverse.isFinite()
                ? new FrameCamera(
                        projectionCopy,
                        canonicalWorldToView,
                        inverse,
                        x,
                        y,
                        z,
                        effectiveX,
                        effectiveY,
                        effectiveZ)
                : null;
    }

    private static boolean isInvertible(Matrix4fc matrix) {
        float determinant = matrix.determinant();
        return matrix.isFinite()
                && Float.isFinite(determinant)
                && Math.abs(determinant) >= 1.0e-20F;
    }

    private static boolean isRigid(Matrix4fc matrix) {
        float tolerance = 1.0e-3F;
        if (Math.abs(matrix.m03()) > tolerance
                || Math.abs(matrix.m13()) > tolerance
                || Math.abs(matrix.m23()) > tolerance
                || Math.abs(matrix.m33() - 1.0F) > tolerance) {
            return false;
        }
        float xLengthSquared = matrix.m00() * matrix.m00()
                + matrix.m01() * matrix.m01()
                + matrix.m02() * matrix.m02();
        float yLengthSquared = matrix.m10() * matrix.m10()
                + matrix.m11() * matrix.m11()
                + matrix.m12() * matrix.m12();
        float zLengthSquared = matrix.m20() * matrix.m20()
                + matrix.m21() * matrix.m21()
                + matrix.m22() * matrix.m22();
        float xy = matrix.m00() * matrix.m10()
                + matrix.m01() * matrix.m11()
                + matrix.m02() * matrix.m12();
        float xz = matrix.m00() * matrix.m20()
                + matrix.m01() * matrix.m21()
                + matrix.m02() * matrix.m22();
        float yz = matrix.m10() * matrix.m20()
                + matrix.m11() * matrix.m21()
                + matrix.m12() * matrix.m22();
        return Math.abs(xLengthSquared - 1.0F) <= tolerance
                && Math.abs(yLengthSquared - 1.0F) <= tolerance
                && Math.abs(zLengthSquared - 1.0F) <= tolerance
                && Math.abs(xy) <= tolerance
                && Math.abs(xz) <= tolerance
                && Math.abs(yz) <= tolerance;
    }

    private static final class Scratch {
        private final Matrix4f cameraEffect = new Matrix4f();
        private final Vector3f cameraOffset = new Vector3f();
    }
}
