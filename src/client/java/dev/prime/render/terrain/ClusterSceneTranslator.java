package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.Objects;
import net.minecraft.core.Direction;

/**
 * Pure translation entry from one captured 4x4x4 cluster to Prime's CPU upload payload.
 *
 * <p>Mutable accumulators are invocation-local implementation details. No input is mutated and no
 * state escapes except the returned immutable payload.
 */
final class ClusterSceneTranslator {
    private ClusterSceneTranslator() {
    }

    static CpuClusterMesh translate(
            CapturedCluster captured,
            LabPbrMaterialSet materials,
            ClusterTranslationSettings settings) {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(materials, "materials");
        Objects.requireNonNull(settings, "settings");

        SectionClusterMeshBuilder cluster = new SectionClusterMeshBuilder(
                captured.clusterX(),
                captured.clusterY(),
                captured.clusterZ(),
                settings.segmentTriangleTarget(),
                settings.maxOpacityMicromapSubdivisionLevel(),
                settings.voxelSurfacesEnabled(),
                settings.voxelSurfaceMaximumHeight());
        for (int localIndex = 0;
                localIndex < SectionCluster.SECTION_COUNT;
                localIndex++) {
            CapturedSectionGeometry section = captured.section(localIndex);
            if (section == null) {
                continue;
            }
            int sectionX = captured.clusterX() + CapturedCluster.sectionX(localIndex);
            int sectionY = captured.clusterY() + CapturedCluster.sectionY(localIndex);
            int sectionZ = captured.clusterZ() + CapturedCluster.sectionZ(localIndex);
            cluster.add(
                    sectionX,
                    sectionY,
                    sectionZ,
                    translateSection(section, materials, settings));
        }
        return cluster.build();
    }

    private static CpuSectionGeometry translateSection(
            CapturedSectionGeometry captured,
            LabPbrMaterialSet materials,
            ClusterTranslationSettings settings) {
        SectionMeshAccumulator accumulator = new SectionMeshAccumulator(
                materials,
                settings.buildOpacityMicromap(),
                settings.segmentTriangleTarget());
        SectionMeshAccumulator.Quad quad = new SectionMeshAccumulator.Quad();
        SectionMeshAccumulator.Surface surface = new SectionMeshAccumulator.Surface();
        for (TwoSidedQuadReducer.ResolvedQuad resolved
                : TwoSidedQuadReducer.resolve(captured.quads())) {
            CapturedSectionGeometry.Quad capturedQuad = resolved.quad();
            for (int vertex = 0; vertex < 4; vertex++) {
                quad.x[vertex] = capturedQuad.x(vertex);
                quad.y[vertex] = capturedQuad.y(vertex);
                quad.z[vertex] = capturedQuad.z(vertex);
                quad.u[vertex] = capturedQuad.u(vertex);
                quad.v[vertex] = capturedQuad.v(vertex);
            }
            quad.normalX = capturedQuad.normalX();
            quad.normalY = capturedQuad.normalY();
            quad.normalZ = capturedQuad.normalZ();
            CapturedSectionGeometry.Surface capturedSurface = capturedQuad.surface();
            if (capturedSurface.fluid() != null
                    && !translateFluidQuad(
                            quad, capturedSurface.fluid(), settings)) {
                continue;
            }
            boolean cutout = isCutout(capturedSurface);
            boolean transmissive = isTransmissive(capturedSurface);
            accumulator.addQuad(quad, surface.set(
                    averageColor(capturedSurface),
                    cutout,
                    capturedSurface.animated(),
                    transmissive,
                    capturedSurface.foliage()
                            || transmissive && capturedSurface.collisionEmpty(),
                    capturedSurface.water(),
                    capturedSurface.foliage(),
                    capturedSurface.mergeable(),
                    capturedSurface.rasterOverlay(),
                    resolved.frontFaceOnly(),
                    capturedSurface.lightEmission(),
                    capturedSurface.sprite()));
        }
        return accumulator.build();
    }

    static boolean isCutout(CapturedSectionGeometry.Surface surface) {
        return surface.layer() == CapturedSectionGeometry.Layer.CUTOUT
                || surface.foliage()
                || surface.alphaCutOverride();
    }

    static boolean isTransmissive(CapturedSectionGeometry.Surface surface) {
        return surface.layer() == CapturedSectionGeometry.Layer.TRANSLUCENT
                && !surface.alphaCutOverride();
    }

    private static boolean translateFluidQuad(
            SectionMeshAccumulator.Quad quad,
            CapturedSectionGeometry.FluidFacts fluid,
            ClusterTranslationSettings settings) {
        if (settings.closeCoveredFluidGap() && fluid.fullCeiling()) {
            for (int vertex = 0; vertex < 4; vertex++) {
                if (quad.y[vertex] > fluid.localY() + 0.5F) {
                    quad.y[vertex] = fluid.localY() + 1.0F;
                }
            }
        }

        float edgeOneX = quad.x[1] - quad.x[0];
        float edgeOneY = quad.y[1] - quad.y[0];
        float edgeOneZ = quad.z[1] - quad.z[0];
        float edgeTwoX = quad.x[2] - quad.x[0];
        float edgeTwoY = quad.y[2] - quad.y[0];
        float edgeTwoZ = quad.z[2] - quad.z[0];
        float normalX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
        float normalY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
        float normalZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
        float squaredNormalLength =
                normalX * normalX + normalY * normalY + normalZ * normalZ;
        if (!(squaredNormalLength > 1.0e-20F)) {
            return false;
        }
        // FluidRenderer emits the outward quad first and optionally appends its exact reversed
        // raster back face. TwoSidedQuadReducer removes that duplicate before this method. Do not
        // infer sidedness from the quad center: a valid shallow or sloped top can lie below the
        // owning block's midpoint, which would invert water medium transitions and lava emission.
        Direction direction =
                Direction.getApproximateNearest(normalX, normalY, normalZ);
        if (settings.suppressFluidFaceAgainstFullCollision()
                && fluid.fullCollision(direction.ordinal())) {
            return false;
        }
        float inverseNormalLength =
                1.0F / (float) Math.sqrt(squaredNormalLength);
        quad.normalX = normalX * inverseNormalLength;
        quad.normalY = normalY * inverseNormalLength;
        quad.normalZ = normalZ * inverseNormalLength;
        return true;
    }

    static int averageColor(CapturedSectionGeometry.Surface surface) {
        int alpha = 0;
        int red = 0;
        int green = 0;
        int blue = 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            int color = surface.color(vertex);
            alpha += color >>> 24;
            red += color >>> 16 & 0xff;
            green += color >>> 8 & 0xff;
            blue += color & 0xff;
        }
        return (alpha + 2) / 4 << 24
                | (red + 2) / 4 << 16
                | (green + 2) / 4 << 8
                | (blue + 2) / 4;
    }
}
