package dev.prime.render.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable geometry facts observed while Minecraft compiles one Section.
 *
 * <p>This value deliberately stops before Prime material encoding, triangle lowering, face
 * merging, opacity micromaps, light extraction, and GPU ABI packing. Referenced sprites belong to
 * the immutable resource epoch captured by the caller.
 */
public final class CapturedSectionGeometry {
    private final List<Quad> quads;

    private CapturedSectionGeometry(List<Quad> quads) {
        this.quads = List.copyOf(quads);
    }

    public List<Quad> quads() {
        return this.quads;
    }

    /** Thread-confined capture scratch. Values are copied when a quad is published. */
    public static final class MutableQuad {
        public final float[] x = new float[4];
        public final float[] y = new float[4];
        public final float[] z = new float[4];
        public final float[] u = new float[4];
        public final float[] v = new float[4];
        public float normalX;
        public float normalY;
        public float normalZ;
    }

    /** One accepted Minecraft quad with immutable vertex attributes and semantic facts. */
    public static final class Quad {
        private final float[] x;
        private final float[] y;
        private final float[] z;
        private final float[] u;
        private final float[] v;
        private final float normalX;
        private final float normalY;
        private final float normalZ;
        private final Surface surface;

        private Quad(MutableQuad source, Surface surface) {
            this.x = source.x.clone();
            this.y = source.y.clone();
            this.z = source.z.clone();
            this.u = source.u.clone();
            this.v = source.v.clone();
            this.normalX = source.normalX;
            this.normalY = source.normalY;
            this.normalZ = source.normalZ;
            this.surface = Objects.requireNonNull(surface, "surface");
        }

        public float x(int vertex) {
            return this.x[vertex];
        }

        public float y(int vertex) {
            return this.y[vertex];
        }

        public float z(int vertex) {
            return this.z[vertex];
        }

        public float u(int vertex) {
            return this.u[vertex];
        }

        public float v(int vertex) {
            return this.v[vertex];
        }

        public float normalX() {
            return this.normalX;
        }

        public float normalY() {
            return this.normalY;
        }

        public float normalZ() {
            return this.normalZ;
        }

        public Surface surface() {
            return this.surface;
        }
    }

    /**
     * Facts needed to translate an accepted surface without retaining its block state or renderer
     * implementation.
     */
    public record Surface(
            int color0,
            int color1,
            int color2,
            int color3,
            Layer layer,
            boolean alphaCutOverride,
            boolean collisionEmpty,
            boolean animated,
            boolean water,
            boolean foliage,
            boolean mergeable,
            boolean rasterOverlay,
            int lightEmission,
            CapturedSprite sprite,
            FluidFacts fluid) {
        public Surface {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(sprite, "sprite");
            if (lightEmission < 0 || lightEmission > 15) {
                throw new IllegalArgumentException(
                        "Captured light emission must be in [0, 15]");
            }
        }

        public int color(int vertex) {
            return switch (vertex) {
                case 0 -> this.color0;
                case 1 -> this.color1;
                case 2 -> this.color2;
                case 3 -> this.color3;
                default -> throw new IndexOutOfBoundsException(vertex);
            };
        }

        public static Surface uniform(
                int color,
                Layer layer,
                boolean alphaCutOverride,
                boolean collisionEmpty,
                boolean animated,
                boolean water,
                boolean foliage,
                boolean mergeable,
                boolean rasterOverlay,
                int lightEmission,
                CapturedSprite sprite) {
            return new Surface(
                    color,
                    color,
                    color,
                    color,
                    layer,
                    alphaCutOverride,
                    collisionEmpty,
                    animated,
                    water,
                    foliage,
                    mergeable,
                    rasterOverlay,
                    lightEmission,
                    sprite,
                    null);
        }
    }

    /** Raster-layer fact translated to physical material semantics only at cluster scope. */
    public enum Layer {
        OPAQUE,
        CUTOUT,
        TRANSLUCENT
    }

    /** World-query facts needed to translate one raw fluid quad without retaining the world. */
    public record FluidFacts(
            int localX,
            int localY,
            int localZ,
            boolean fullCeiling,
            int fullCollisionMask) {
        public FluidFacts {
            if (localX < 0 || localX > 15
                    || localY < 0 || localY > 15
                    || localZ < 0 || localZ > 15) {
                throw new IllegalArgumentException(
                        "Captured fluid owner must use Section-local block coordinates");
            }
            if ((fullCollisionMask & ~0x3f) != 0) {
                throw new IllegalArgumentException(
                        "Captured fluid collision mask exceeds six directions");
            }
        }

        public boolean fullCollision(int directionOrdinal) {
            if (directionOrdinal < 0 || directionOrdinal >= 6) {
                throw new IndexOutOfBoundsException(directionOrdinal);
            }
            return (this.fullCollisionMask & 1 << directionOrdinal) != 0;
        }
    }

    /** Single-use, thread-confined builder owned by one capture scope. */
    public static final class Builder {
        private final ArrayList<Quad> quads = new ArrayList<>();
        private boolean built;

        public void add(MutableQuad quad, Surface surface) {
            if (this.built) {
                throw new IllegalStateException("Captured Section was already built");
            }
            this.quads.add(new Quad(
                    Objects.requireNonNull(quad, "quad"),
                    Objects.requireNonNull(surface, "surface")));
        }

        public CapturedSectionGeometry build() {
            if (this.built) {
                throw new IllegalStateException("Captured Section was already built");
            }
            this.built = true;
            return new CapturedSectionGeometry(this.quads);
        }
    }
}
