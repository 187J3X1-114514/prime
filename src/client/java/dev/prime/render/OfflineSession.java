package dev.prime.render;

import dev.prime.render.terrain.TerrainScene;
import dev.prime.render.vulkan.TraceBackend;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Frozen scene and transport identity for one offline accumulation. */
final class OfflineSession {
    private final ClientLevel world;
    private final TerrainScene.ResidentSceneView scene;
    private final AstronomyState astronomy;
    private final OfflineRenderSettings settings;
    private final boolean cameraInWater;
    private final long atlasView;
    private final long atlasSampler;
    private final long textureRevision;
    private final List<TraceBackend.SceneTexture> sceneTextures;
    private FrameCamera camera;
    private long sampleCount;

    OfflineSession(
            ClientLevel world,
            TerrainScene.ResidentSceneView scene,
            FrameCamera camera,
            AstronomyState astronomy,
            OfflineRenderSettings settings,
            boolean cameraInWater,
            long atlasView,
            long atlasSampler,
            long textureRevision,
            List<TraceBackend.SceneTexture> sceneTextures) {
        this.world = Objects.requireNonNull(world, "world");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.astronomy = Objects.requireNonNull(astronomy, "astronomy");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (atlasView == 0L || atlasSampler == 0L || textureRevision < 0L) {
            throw new IllegalArgumentException("Offline atlas snapshot is incomplete");
        }
        this.cameraInWater = cameraInWater;
        this.atlasView = atlasView;
        this.atlasSampler = atlasSampler;
        this.textureRevision = textureRevision;
        this.sceneTextures = List.copyOf(sceneTextures);
    }

    ClientLevel world() { return this.world; }
    TerrainScene.ResidentSceneView scene() { return this.scene; }
    FrameCamera camera() { return this.camera; }
    AstronomyState astronomy() { return this.astronomy; }
    OfflineRenderSettings settings() { return this.settings; }
    boolean cameraInWater() { return this.cameraInWater; }
    long textureRevision() { return this.textureRevision; }
    List<TraceBackend.SceneTexture> sceneTextures() { return this.sceneTextures; }
    long sampleCount() { return this.sampleCount; }

    boolean matchesWorld(ClientLevel current) {
        return current == this.world;
    }

    boolean matchesAtlas(long view, long sampler, long revision) {
        return view == this.atlasView
                && sampler == this.atlasSampler
                && revision == this.textureRevision;
    }

    void resetAccumulation() {
        this.sampleCount = 0L;
    }

    void commitSample() {
        this.sampleCount = Math.incrementExact(this.sampleCount);
    }

    boolean updateProjection(Matrix4fc baseProjection) {
        float previousAspect = Math.abs(
                this.camera.projection().m11() / this.camera.projection().m00());
        float nextAspect = Math.abs(baseProjection.m11() / baseProjection.m00());
        if (!Float.isFinite(previousAspect)
                || !Float.isFinite(nextAspect)
                || Math.abs(previousAspect - nextAspect) <= 1.0e-5F) {
            return false;
        }
        Matrix4f projection = new Matrix4f(baseProjection);
        Matrix4f inverse = new Matrix4f(projection)
                .mul(this.camera.viewRotation())
                .invert();
        if (!inverse.isFinite()) {
            return false;
        }
        FrameCamera fixed = this.camera;
        this.camera = new FrameCamera(
                projection,
                new Matrix4f(fixed.viewRotation()),
                inverse,
                fixed.x(),
                fixed.y(),
                fixed.z(),
                fixed.renderX(),
                fixed.renderY(),
                fixed.renderZ());
        this.sampleCount = 0L;
        return true;
    }
}
