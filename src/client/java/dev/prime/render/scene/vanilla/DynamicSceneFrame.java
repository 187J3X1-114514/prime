package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.prime.render.terrain.CpuClusterMesh;
import java.util.List;
import java.util.Objects;

/** Immutable dynamic geometry captured from one vanilla world-render submission. */
public record DynamicSceneFrame(
        int clusterX,
        int clusterY,
        int clusterZ,
        CpuClusterMesh mesh,
        List<SceneTexture> textures,
        int entityTriangles,
        int blockEntityTriangles,
        int particleTriangles) {

    public DynamicSceneFrame {
        mesh = Objects.requireNonNull(mesh, "mesh");
        textures = List.copyOf(textures);
        if (entityTriangles < 0 || blockEntityTriangles < 0 || particleTriangles < 0) {
            throw new IllegalArgumentException("Dynamic triangle counts must not be negative");
        }
        if (!mesh.lights().isEmpty()) {
            throw new IllegalArgumentException(
                    "Dynamic geometry must not contain light-tree emitters");
        }
        if ((long) entityTriangles + blockEntityTriangles + particleTriangles
                != mesh.triangleCount()) {
            throw new IllegalArgumentException(
                    "Dynamic element counts do not match the captured mesh");
        }
    }

    public boolean isEmpty() {
        return this.mesh.isEmpty();
    }

    /** Texture zero is the block atlas; captured textures start at index one. */
    public record SceneTexture(GpuTextureView view, GpuSampler sampler) {
        public SceneTexture {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(sampler, "sampler");
        }
    }
}
