package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import dev.prime.render.shader.ShaderAbi;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Render-thread boundary around Minecraft's dynamic world submissions.
 *
 * <p>Minecraft remains responsible for model selection, animation and transforms. Prime only
 * replays accepted mesh submissions into a CPU triangle sink while the corresponding world
 * element scope is active.
 */
public final class DynamicSceneCapture {
    private static final ThreadLocal<Session> ACTIVE = new ThreadLocal<>();
    private static final Direction[] DIRECTIONS = Direction.values();

    private DynamicSceneCapture() {
    }

    public static void begin(Vec3 cameraPosition) {
        if (ACTIVE.get() != null) {
            // A failed vanilla frame may bypass RETURN injections. The next frame owns a fresh
            // capture and must not inherit its partial model state.
            ACTIVE.remove();
        }
        ACTIVE.set(new Session(cameraPosition));
    }

    public static boolean active() {
        return ACTIVE.get() != null;
    }

    public static void beginElement(VanillaSceneBoundary.Element element) {
        Session session = ACTIVE.get();
        if (session != null) {
            session.beginElement(element);
        }
    }

    public static void endElement(VanillaSceneBoundary.Element element) {
        Session session = ACTIVE.get();
        if (session != null) {
            session.endElement(element);
        }
    }

    public static DynamicSceneFrame finish() {
        Session session = ACTIVE.get();
        if (session == null) {
            throw new IllegalStateException("Dynamic scene capture was not opened");
        }
        ACTIVE.remove();
        return session.finish();
    }

    public static <S> void captureModel(
            Model<? super S> model,
            S state,
            PoseStack poseStack,
            RenderType renderType,
            int lightCoords,
            int overlayCoords,
            int tintedColor,
            @Nullable TextureAtlasSprite sprite,
            int outlineColor,
            ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        Session session = activeSession();
        if (session == null || renderType.isOutline()) {
            return;
        }
        int textureIndex = session.textureIndex(renderType);
        if (textureIndex < 0) {
            return;
        }
        DynamicMeshBuilder.VertexSink sink = session.builder.open(
                session.element,
                renderType.primitiveTopology(),
                textureIndex,
                lightCoords);
        var consumer = sprite == null ? sink : sprite.wrap(sink);
        PoseStack capturePose = new PoseStack();
        capturePose.last().set(poseStack.last());
        model.setupAnim(state);
        model.renderToBuffer(
                capturePose, consumer, lightCoords, overlayCoords, tintedColor);
        sink.finish();
    }

    public static void captureBlockModel(
            PoseStack poseStack,
            RenderType renderType,
            List<BlockStateModelPart> modelParts,
            int[] tintLayers,
            int lightCoords,
            int overlayCoords,
            int outlineColor) {
        Session session = activeSession();
        if (session == null || renderType.isOutline()) {
            return;
        }
        int textureIndex = session.textureIndex(renderType);
        if (textureIndex < 0) {
            return;
        }
        DynamicMeshBuilder.VertexSink sink = session.builder.open(
                session.element,
                renderType.primitiveTopology(),
                textureIndex,
                lightCoords);
        QuadInstance instance = new QuadInstance();
        instance.setLightCoords(lightCoords);
        instance.setOverlayCoords(overlayCoords);
        for (BlockStateModelPart part : modelParts) {
            for (Direction direction : DIRECTIONS) {
                captureBlockQuads(
                        poseStack,
                        part.getQuads(direction),
                        tintLayers,
                        instance,
                        sink);
            }
            captureBlockQuads(
                    poseStack, part.getQuads(null), tintLayers, instance, sink);
        }
        sink.finish();
    }

    public static void captureItem(
            PoseStack poseStack,
            ItemDisplayContext displayContext,
            int lightCoords,
            int overlayCoords,
            int outlineColor,
            int[] tintLayers,
            List<BakedQuad> quads,
            ItemStackRenderState.FoilType foilType) {
        Session session = activeSession();
        if (session == null) {
            return;
        }
        QuadInstance instance = new QuadInstance();
        instance.setLightCoords(lightCoords);
        instance.setOverlayCoords(overlayCoords);
        for (BakedQuad quad : quads) {
            BakedQuad.MaterialInfo material = quad.materialInfo();
            RenderType renderType = material.itemRenderType();
            int textureIndex = session.textureIndex(renderType);
            if (textureIndex < 0) {
                continue;
            }
            int tint = material.isTinted()
                    && material.tintIndex() >= 0
                    && material.tintIndex() < tintLayers.length
                    ? tintLayers[material.tintIndex()]
                    : -1;
            instance.setColor(tint);
            instance.setLightCoords(
                    LightCoordsUtil.lightCoordsWithEmission(
                            lightCoords, material.lightEmission()));
            DynamicMeshBuilder.VertexSink sink = session.builder.open(
                    session.element,
                    renderType.primitiveTopology(),
                    textureIndex,
                    lightCoords);
            sink.putBakedQuad(poseStack.last(), quad, instance);
            sink.finish();
        }
    }

    public static void captureCustomGeometry(
            PoseStack poseStack,
            RenderType renderType,
            SubmitNodeCollector.CustomGeometryRenderer renderer) {
        Session session = activeSession();
        if (session == null || renderType.isOutline()) {
            return;
        }
        int textureIndex = session.textureIndex(renderType);
        if (textureIndex < 0) {
            return;
        }
        DynamicMeshBuilder.VertexSink sink = session.builder.open(
                session.element,
                renderType.primitiveTopology(),
                textureIndex,
                0);
        renderer.render(poseStack.last().copy(), sink);
        sink.finish();
    }

    public static void captureParticles(QuadParticleRenderState particles) {
        Session session = activeSession();
        if (session == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        for (SingleQuadParticle.Layer layer : particles.layers()) {
            var texture = minecraft.getTextureManager()
                    .getTexture(layer.textureAtlasLocation());
            int textureIndex = session.textureIndex(
                    texture.getTextureView(), texture.getSampler());
            if (textureIndex < 0) {
                continue;
            }
            DynamicMeshBuilder.VertexSink sink = session.builder.open(
                    session.element,
                    PrimitiveTopology.QUADS,
                    textureIndex,
                    0);
            particles.buildLayer(layer, sink);
            sink.finish();
        }
    }

    private static void captureBlockQuads(
            PoseStack poseStack,
            List<BakedQuad> quads,
            int[] tintLayers,
            QuadInstance instance,
            DynamicMeshBuilder.VertexSink sink) {
        for (BakedQuad quad : quads) {
            int tintIndex = quad.materialInfo().tintIndex();
            instance.setColor(
                    tintIndex >= 0 && tintIndex < tintLayers.length
                            ? ARGB.multiply(-1, tintLayers[tintIndex])
                            : -1);
            sink.putBakedQuad(poseStack.last(), quad, instance);
        }
    }

    private static @Nullable Session activeSession() {
        Session session = ACTIVE.get();
        return session == null || session.element == null ? null : session;
    }

    private static final class Session {
        private final int clusterX;
        private final int clusterY;
        private final int clusterZ;
        private final DynamicMeshBuilder builder;
        private final ArrayList<DynamicSceneFrame.SceneTexture> textures =
                new ArrayList<>();
        private VanillaSceneBoundary.Element element;

        private Session(Vec3 cameraPosition) {
            int sectionX = (int) Math.floor(cameraPosition.x) >> 4;
            int sectionY = (int) Math.floor(cameraPosition.y) >> 4;
            int sectionZ = (int) Math.floor(cameraPosition.z) >> 4;
            this.clusterX = sectionX & ~3;
            this.clusterY = sectionY & ~3;
            this.clusterZ = sectionZ & ~3;
            int originX = this.clusterX << 4;
            int originY = this.clusterY << 4;
            int originZ = this.clusterZ << 4;
            this.builder = new DynamicMeshBuilder(
                    cameraPosition.x - originX,
                    cameraPosition.y - originY,
                    cameraPosition.z - originZ);
        }

        private void beginElement(VanillaSceneBoundary.Element element) {
            if (this.element != null) {
                throw new IllegalStateException("Nested dynamic scene element capture");
            }
            if (element != VanillaSceneBoundary.Element.ENTITY
                    && element != VanillaSceneBoundary.Element.BLOCK_ENTITY
                    && element != VanillaSceneBoundary.Element.PARTICLE) {
                throw new IllegalArgumentException(
                        "Unsupported dynamic scene element " + element);
            }
            this.element = element;
        }

        private void endElement(VanillaSceneBoundary.Element element) {
            if (this.element != element) {
                throw new IllegalStateException(
                        "Dynamic scene element capture closed out of order");
            }
            this.element = null;
        }

        private int textureIndex(RenderType renderType) {
            PreparedRenderType prepared = renderType.prepare();
            PreparedRenderType.Texture albedo = null;
            for (PreparedRenderType.Texture texture : prepared.textures()) {
                if ("Sampler0".equals(texture.name())) {
                    albedo = texture;
                    break;
                }
            }
            return albedo == null
                    ? -1
                    : this.textureIndex(albedo.textureView(), albedo.sampler());
        }

        private int textureIndex(GpuTextureView view, GpuSampler sampler) {
            for (int index = 0; index < this.textures.size(); index++) {
                DynamicSceneFrame.SceneTexture texture = this.textures.get(index);
                if (texture.view() == view && texture.sampler() == sampler) {
                    return index + 1;
                }
            }
            if (this.textures.size() + 1 >= ShaderAbi.SCENE_TEXTURE_COUNT) {
                return -1;
            }
            this.textures.add(new DynamicSceneFrame.SceneTexture(view, sampler));
            return this.textures.size();
        }

        private DynamicSceneFrame finish() {
            if (this.element != null) {
                throw new IllegalStateException(
                        "Dynamic scene capture ended inside an element");
            }
            return this.builder.build(
                    this.clusterX,
                    this.clusterY,
                    this.clusterZ,
                    this.textures);
        }
    }
}
