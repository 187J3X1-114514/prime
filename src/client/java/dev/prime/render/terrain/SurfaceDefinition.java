package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.List;
import java.util.Objects;

/** CPU-only physical surface semantics, independent of the packed GPU relation ABI. */
final class SurfaceDefinition {
    enum InterfaceMode {
        SINGLE,
        OVERLAY,
        BILATERAL,
        BOUNDARY,
        THIN_AIR_FILM
    }

    enum Coverage {
        FULL,
        ALPHA_CUTOUT
    }

    record UvMapping(
            float u0,
            float v0,
            float u1,
            float v1,
            float u2,
            float v2,
            float u3,
            float v3) {
        static UvMapping of(CapturedSectionGeometry.Quad quad) {
            Objects.requireNonNull(quad, "quad");
            return new UvMapping(
                    quad.u(0), quad.v(0),
                    quad.u(1), quad.v(1),
                    quad.u(2), quad.v(2),
                    quad.u(3), quad.v(3));
        }

        float u(int vertex) {
            return switch (vertex) {
                case 0 -> this.u0;
                case 1 -> this.u1;
                case 2 -> this.u2;
                case 3 -> this.u3;
                default -> throw new IndexOutOfBoundsException(vertex);
            };
        }

        float v(int vertex) {
            return switch (vertex) {
                case 0 -> this.v0;
                case 1 -> this.v1;
                case 2 -> this.v2;
                case 3 -> this.v3;
                default -> throw new IndexOutOfBoundsException(vertex);
            };
        }
    }

    record MaterialBinding(
            CapturedSectionGeometry.Surface surface,
            UvMapping uv) {
        MaterialBinding {
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(uv, "uv");
        }

        static MaterialBinding of(CapturedSectionGeometry.Quad quad) {
            return new MaterialBinding(quad.surface(), UvMapping.of(quad));
        }
    }

    record SurfaceLayer(MaterialBinding material, Coverage coverage) {
        SurfaceLayer {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(coverage, "coverage");
        }
    }

    record SurfaceSide(
            List<SurfaceLayer> overlays,
            MaterialBinding substrate) {
        SurfaceSide {
            overlays = List.copyOf(overlays);
        }

        static SurfaceSide substrate(MaterialBinding material) {
            return new SurfaceSide(List.of(), material);
        }

        static SurfaceSide overlay(
                MaterialBinding overlay,
                MaterialBinding substrate) {
            return new SurfaceSide(
                    List.of(new SurfaceLayer(overlay, Coverage.ALPHA_CUTOUT)),
                    substrate);
        }
    }

    record MediumEndpoint(
            CapturedSectionGeometry.Surface surface,
            float referenceU,
            float referenceV) {
        MediumEndpoint {
            Objects.requireNonNull(surface, "surface");
            if (!Float.isFinite(referenceU) || !Float.isFinite(referenceV)) {
                throw new IllegalArgumentException("Medium reference UV must be finite");
            }
        }
    }

    private final MaterialBinding primary;
    private final MaterialBinding secondary;
    private final SurfaceSide positiveSide;
    private final SurfaceSide negativeSide;
    private final MediumEndpoint positiveMedium;
    private final MediumEndpoint negativeMedium;
    private final InterfaceMode interfaceMode;

    private SurfaceDefinition(
            MaterialBinding primary,
            MaterialBinding secondary,
            SurfaceSide positiveSide,
            SurfaceSide negativeSide,
            MediumEndpoint positiveMedium,
            MediumEndpoint negativeMedium,
            InterfaceMode interfaceMode) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.secondary = secondary;
        this.positiveSide = Objects.requireNonNull(positiveSide, "positiveSide");
        this.negativeSide = Objects.requireNonNull(negativeSide, "negativeSide");
        this.positiveMedium = positiveMedium;
        this.negativeMedium = negativeMedium;
        this.interfaceMode = Objects.requireNonNull(interfaceMode, "interfaceMode");
    }

    static SurfaceDefinition single(MaterialBinding material) {
        SurfaceSide side = SurfaceSide.substrate(material);
        return new SurfaceDefinition(
                material, null, side, side, null, null, InterfaceMode.SINGLE);
    }

    static SurfaceDefinition bilateral(
            MaterialBinding positive,
            MaterialBinding negative) {
        return new SurfaceDefinition(
                positive,
                negative,
                SurfaceSide.substrate(positive),
                SurfaceSide.substrate(negative),
                null,
                null,
                InterfaceMode.BILATERAL);
    }

    static SurfaceDefinition overlay(
            MaterialBinding overlay,
            MaterialBinding substrate,
            boolean positiveOnly) {
        SurfaceSide covered = SurfaceSide.overlay(overlay, substrate);
        SurfaceSide plain = SurfaceSide.substrate(substrate);
        return new SurfaceDefinition(
                overlay,
                substrate,
                covered,
                positiveOnly ? plain : covered,
                null,
                null,
                InterfaceMode.OVERLAY);
    }

    SurfaceDefinition withBoundary(
            MediumEndpoint positiveMedium,
            MediumEndpoint negativeMedium,
            boolean thinAirFilm) {
        return new SurfaceDefinition(
                this.primary,
                this.secondary,
                this.positiveSide,
                this.negativeSide,
                positiveMedium,
                negativeMedium,
                thinAirFilm ? InterfaceMode.THIN_AIR_FILM : InterfaceMode.BOUNDARY);
    }

    MaterialBinding primary() {
        return this.primary;
    }

    MaterialBinding secondary() {
        return this.secondary;
    }

    SurfaceSide positiveSide() {
        return this.positiveSide;
    }

    SurfaceSide negativeSide() {
        return this.negativeSide;
    }

    MediumEndpoint positiveMedium() {
        return this.positiveMedium;
    }

    MediumEndpoint negativeMedium() {
        return this.negativeMedium;
    }

    InterfaceMode interfaceMode() {
        return this.interfaceMode;
    }

    boolean overlayPositiveOnly() {
        return this.interfaceMode == InterfaceMode.OVERLAY
                && this.negativeSide.overlays().isEmpty();
    }
}
