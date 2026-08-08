package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.prime.render.terrain.CpuClusterMesh;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable dynamic geometry captured from one vanilla world-render submission. */
public record DynamicSceneFrame(
        int clusterX,
        int clusterY,
        int clusterZ,
        CpuClusterMesh mesh,
        List<SceneTexture> textures,
        List<MotionSegment> motionSegments,
        int entityTriangles,
        int blockEntityTriangles,
        int particleTriangles,
        int featureTriangles,
        Set<CompatibilityIssue> compatibilityIssues) {

    public DynamicSceneFrame {
        mesh = Objects.requireNonNull(mesh, "mesh");
        textures = List.copyOf(textures);
        motionSegments = List.copyOf(motionSegments);
        compatibilityIssues = Set.copyOf(compatibilityIssues);
        if (entityTriangles < 0
                || blockEntityTriangles < 0
                || particleTriangles < 0
                || featureTriangles < 0) {
            throw new IllegalArgumentException("Dynamic triangle counts must not be negative");
        }
        if (!mesh.lights().isEmpty()) {
            throw new IllegalArgumentException(
                    "Dynamic geometry must not contain light-tree emitters");
        }
        if ((long) entityTriangles
                        + blockEntityTriangles
                        + particleTriangles
                        + featureTriangles
                != mesh.triangleCount()) {
            throw new IllegalArgumentException(
                    "Dynamic element counts do not match the captured mesh");
        }
        int previousEnd = 0;
        for (MotionSegment segment : motionSegments) {
            Objects.requireNonNull(segment, "motion segment");
            if (segment.firstTriangle() < previousEnd
                    || (long) segment.firstTriangle() + segment.triangleCount()
                            > mesh.triangleCount()) {
                throw new IllegalArgumentException(
                        "Dynamic motion segments must be ordered, disjoint, and inside the mesh");
            }
            previousEnd = Math.addExact(
                    segment.firstTriangle(), segment.triangleCount());
        }
    }

    public boolean isEmpty() {
        return this.mesh.isEmpty();
    }

    public enum CompatibilityIssue {
        BLENDED_MATERIAL_APPROXIMATED(
                "blended dynamic materials are approximated as alpha-tested surfaces"),
        TEXTURELESS_MATERIAL_APPROXIMATED(
                "a textureless render type is approximated with its submitted vertex color"),
        MISSING_ALBEDO_TEXTURE(
                "a render type with textures has no Sampler0 albedo binding and was omitted"),
        SCENE_TEXTURE_LIMIT(
                "the dynamic scene texture descriptor limit was reached and geometry was omitted"),
        UNSUPPORTED_TOPOLOGY(
                "a non-triangle render topology was omitted"),
        CUSTOM_SUBMIT_NODE(
                "a Fabric custom submit node has no general mesh replay contract and was omitted");

        private final String description;

        CompatibilityIssue(String description) {
            this.description = description;
        }

        public String description() {
            return this.description;
        }
    }

    /** Texture zero is the block atlas; captured textures start at index one. */
    public record SceneTexture(GpuTextureView view, GpuSampler sampler) {
        public SceneTexture {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(sampler, "sampler");
        }
    }

    /** Stable captured-object span whose animated vertices keep one semantic triangle order. */
    public record MotionSegment(
            VanillaSceneBoundary.Element element,
            long key,
            int firstTriangle,
            int triangleCount) {
        public MotionSegment {
            Objects.requireNonNull(element, "element");
            if (element != VanillaSceneBoundary.Element.ENTITY
                    && element != VanillaSceneBoundary.Element.BLOCK_ENTITY) {
                throw new IllegalArgumentException(
                        "Only entities and block entities have stable motion identities");
            }
            if (firstTriangle < 0 || triangleCount < 0) {
                throw new IllegalArgumentException(
                        "Motion segment triangle range must not be negative");
            }
        }
    }
}
