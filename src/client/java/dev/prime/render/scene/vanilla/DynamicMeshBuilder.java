package dev.prime.render.scene.vanilla;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.prime.render.terrain.CpuClusterMesh;
import dev.prime.render.terrain.CpuSectionLights;
import dev.prime.render.terrain.CpuSectionMesh;
import dev.prime.render.terrain.OpacityMicromapData;
import dev.prime.render.terrain.PrimitivePacking;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.LightCoordsUtil;

/**
 * Converts vertices already accepted by Minecraft's entity renderer into Prime triangle records.
 *
 * <p>All dynamic triangles are alpha tested and deliberately receive no {@link CpuSectionLights}.
 * Full-bright input is encoded only as hit-visible emission.
 */
final class DynamicMeshBuilder {
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;
    private final FloatWords positions = new FloatWords();
    private final IntWords primitives = new IntWords();
    private final int[] trianglesByElement =
            new int[VanillaSceneBoundary.Element.values().length];

    DynamicMeshBuilder(double offsetX, double offsetY, double offsetZ) {
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
    }

    VertexSink open(
            VanillaSceneBoundary.Element element,
            PrimitiveTopology topology,
            int textureIndex,
            int fallbackLight) {
        return new VertexSink(this, element, topology, textureIndex, fallbackLight);
    }

    DynamicSceneFrame build(
            int clusterX,
            int clusterY,
            int clusterZ,
            List<DynamicSceneFrame.SceneTexture> textures) {
        int triangleCount = this.positions.size / 9;
        CpuSectionMesh section = new CpuSectionMesh(
                this.positions.toArray(),
                this.primitives.toArray(),
                0,
                triangleCount,
                0,
                OpacityMicromapData.fullyUnknown(triangleCount),
                CpuSectionLights.EMPTY);
        return new DynamicSceneFrame(
                clusterX,
                clusterY,
                clusterZ,
                CpuClusterMesh.fromSegments(List.of(section)),
                textures,
                this.trianglesByElement[VanillaSceneBoundary.Element.ENTITY.ordinal()],
                this.trianglesByElement[VanillaSceneBoundary.Element.BLOCK_ENTITY.ordinal()],
                this.trianglesByElement[VanillaSceneBoundary.Element.PARTICLE.ordinal()]);
    }

    private void addTriangle(
            VanillaSceneBoundary.Element element,
            Vertex first,
            Vertex second,
            Vertex third,
            int textureIndex) {
        float firstX = (float) (first.x + this.offsetX);
        float firstY = (float) (first.y + this.offsetY);
        float firstZ = (float) (first.z + this.offsetZ);
        float secondX = (float) (second.x + this.offsetX);
        float secondY = (float) (second.y + this.offsetY);
        float secondZ = (float) (second.z + this.offsetZ);
        float thirdX = (float) (third.x + this.offsetX);
        float thirdY = (float) (third.y + this.offsetY);
        float thirdZ = (float) (third.z + this.offsetZ);
        if (!finite(
                firstX, firstY, firstZ,
                secondX, secondY, secondZ,
                thirdX, thirdY, thirdZ,
                first.u, first.v, second.u, second.v, third.u, third.v)) {
            return;
        }

        float edgeOneX = secondX - firstX;
        float edgeOneY = secondY - firstY;
        float edgeOneZ = secondZ - firstZ;
        float edgeTwoX = thirdX - firstX;
        float edgeTwoY = thirdY - firstY;
        float edgeTwoZ = thirdZ - firstZ;
        float crossX = edgeOneY * edgeTwoZ - edgeOneZ * edgeTwoY;
        float crossY = edgeOneZ * edgeTwoX - edgeOneX * edgeTwoZ;
        float crossZ = edgeOneX * edgeTwoY - edgeOneY * edgeTwoX;
        float areaSquared = crossX * crossX + crossY * crossY + crossZ * crossZ;
        if (!(areaSquared > 1.0e-20F) || !Float.isFinite(areaSquared)) {
            return;
        }

        this.positions.add(firstX, firstY, firstZ);
        this.positions.add(secondX, secondY, secondZ);
        this.positions.add(thirdX, thirdY, thirdZ);
        int uv0 = PrimitivePacking.packHalf2(first.u, first.v);
        int uv1 = PrimitivePacking.packHalf2(second.u, second.v);
        int uv2 = PrimitivePacking.packHalf2(third.u, third.v);
        float fallbackX = first.normalX + second.normalX + third.normalX;
        float fallbackY = first.normalY + second.normalY + third.normalY;
        float fallbackZ = first.normalZ + second.normalZ + third.normalZ;
        int normal = PrimitivePacking.packTriangleNormal(
                edgeOneX,
                edgeOneY,
                edgeOneZ,
                edgeTwoX,
                edgeTwoY,
                edgeTwoZ,
                fallbackX,
                fallbackY,
                fallbackZ);
        long tangent = PrimitivePacking.packTriangleTangent(
                edgeOneX,
                edgeOneY,
                edgeOneZ,
                edgeTwoX,
                edgeTwoY,
                edgeTwoZ,
                second.u - first.u,
                second.v - first.v,
                third.u - first.u,
                third.v - first.v,
                normal);
        int flags = PrimitivePacking.packFlags(true, false);
        int tint = PrimitivePacking.packTintFlags(
                PrimitivePacking.packTint(first.color), flags);
        boolean visibleEmission = fullBright(first.light)
                && fullBright(second.light)
                && fullBright(third.light);
        this.primitives.add(uv0, uv1, uv2, tint);
        this.primitives.add(
                normal,
                PrimitivePacking.packDynamicFlags(flags, textureIndex, visibleEmission),
                PrimitivePacking.packUvDensity(
                        edgeOneX,
                        edgeOneY,
                        edgeOneZ,
                        edgeTwoX,
                        edgeTwoY,
                        edgeTwoZ,
                        second.u - first.u,
                        second.v - first.v,
                        third.u - first.u,
                        third.v - first.v),
                (int) tangent);
        this.trianglesByElement[element.ordinal()]++;
    }

    private static boolean fullBright(int light) {
        return LightCoordsUtil.block(light) >= 15;
    }

    private static boolean finite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    static final class VertexSink implements VertexConsumer {
        private final DynamicMeshBuilder owner;
        private final VanillaSceneBoundary.Element element;
        private final PrimitiveTopology topology;
        private final int textureIndex;
        private final int fallbackLight;
        private final ArrayList<Vertex> vertices = new ArrayList<>();
        private Vertex current;
        private boolean finished;

        private VertexSink(
                DynamicMeshBuilder owner,
                VanillaSceneBoundary.Element element,
                PrimitiveTopology topology,
                int textureIndex,
                int fallbackLight) {
            this.owner = owner;
            this.element = element;
            this.topology = topology;
            this.textureIndex = textureIndex;
            this.fallbackLight = fallbackLight;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.commitCurrent();
            this.current = new Vertex(x, y, z, this.fallbackLight);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            this.requireCurrent().color =
                    alpha << 24 | red << 16 | green << 8 | blue;
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            this.requireCurrent().color = color;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            Vertex vertex = this.requireCurrent();
            vertex.u = u;
            vertex.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.requireCurrent().light = u & 0xffff | v << 16;
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            Vertex vertex = this.requireCurrent();
            vertex.normalX = x;
            vertex.normalY = y;
            vertex.normalZ = z;
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        void finish() {
            if (this.finished) {
                throw new IllegalStateException("Dynamic vertex sink was already finished");
            }
            this.finished = true;
            this.commitCurrent();
            int count = this.vertices.size();
            if (this.topology == PrimitiveTopology.QUADS) {
                for (int index = 0; index + 3 < count; index += 4) {
                    this.emit(index, index + 1, index + 2);
                    this.emit(index, index + 2, index + 3);
                }
            } else if (this.topology == PrimitiveTopology.TRIANGLES) {
                for (int index = 0; index + 2 < count; index += 3) {
                    this.emit(index, index + 1, index + 2);
                }
            } else if (this.topology == PrimitiveTopology.TRIANGLE_STRIP) {
                for (int index = 0; index + 2 < count; index++) {
                    if ((index & 1) == 0) {
                        this.emit(index, index + 1, index + 2);
                    } else {
                        this.emit(index + 1, index, index + 2);
                    }
                }
            } else if (this.topology == PrimitiveTopology.TRIANGLE_FAN) {
                for (int index = 1; index + 1 < count; index++) {
                    this.emit(0, index, index + 1);
                }
            }
        }

        private void emit(int first, int second, int third) {
            this.owner.addTriangle(
                    this.element,
                    this.vertices.get(first),
                    this.vertices.get(second),
                    this.vertices.get(third),
                    this.textureIndex);
        }

        private Vertex requireCurrent() {
            if (this.current == null) {
                throw new IllegalStateException("Vertex attribute was written before a position");
            }
            return this.current;
        }

        private void commitCurrent() {
            if (this.current != null) {
                this.vertices.add(this.current);
                this.current = null;
            }
        }
    }

    private static final class Vertex {
        private final float x;
        private final float y;
        private final float z;
        private float u;
        private float v;
        private int color = -1;
        private float normalX;
        private float normalY = 1.0F;
        private float normalZ;
        private int light;

        private Vertex(float x, float y, float z, int light) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.light = light;
        }
    }

    private static final class FloatWords {
        private float[] values = new float[1024];
        private int size;

        private void add(float first, float second, float third) {
            this.ensure(3);
            this.values[this.size++] = first;
            this.values[this.size++] = second;
            this.values[this.size++] = third;
        }

        private void ensure(int count) {
            int required = Math.addExact(this.size, count);
            if (required > this.values.length) {
                this.values = java.util.Arrays.copyOf(
                        this.values, Math.max(required, this.values.length * 2));
            }
        }

        private float[] toArray() {
            return java.util.Arrays.copyOf(this.values, this.size);
        }
    }

    private static final class IntWords {
        private int[] values = new int[1024];
        private int size;

        private void add(int first, int second, int third, int fourth) {
            this.ensure(4);
            this.values[this.size++] = first;
            this.values[this.size++] = second;
            this.values[this.size++] = third;
            this.values[this.size++] = fourth;
        }

        private void ensure(int count) {
            int required = Math.addExact(this.size, count);
            if (required > this.values.length) {
                this.values = java.util.Arrays.copyOf(
                        this.values, Math.max(required, this.values.length * 2));
            }
        }

        private int[] toArray() {
            return java.util.Arrays.copyOf(this.values, this.size);
        }
    }
}
