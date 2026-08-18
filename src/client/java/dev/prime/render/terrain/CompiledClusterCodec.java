package dev.prime.render.terrain;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Objects;

/** Versioned canonical binary encoding of one {@link CompiledCluster}. */
public final class CompiledClusterCodec {
    private static final int MAGIC = 0x3143_4350;
    private static final int VERSION = 14;
    private static final int MAX_SEGMENTS = 4_096;
    private static final int MAX_VOXEL_MESHES = 4_096;
    private static final int MAX_VOXEL_INSTANCES = 4_194_304;
    private static final int MAX_ENCODED_BYTES = 1 << 30;

    private CompiledClusterCodec() {
    }

    public static byte[] encode(CompiledCluster cluster) {
        Objects.requireNonNull(cluster, "cluster");
        if (cluster.dynamic()) {
            throw new IllegalArgumentException(
                    "Dynamic clusters are frame-local and cannot be encoded");
        }
        CpuClusterMesh mesh = cluster.mesh();
        validatePrimitiveRecords(mesh);
        OpacityMicromapData opacity = mesh.opacityMicromap();
        opacity.requireValidTriangleIndices();
        int byteSize = Math.toIntExact(encodedByteSize(cluster));
        ByteBuffer output = ByteBuffer.allocate(byteSize).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(MAGIC);
        output.putInt(VERSION);
        output.putLong(cluster.key());
        output.putInt(cluster.clusterX());
        output.putInt(cluster.clusterY());
        output.putInt(cluster.clusterZ());

        output.putLong(mesh.opaqueTriangleCount());
        output.putLong(mesh.cutoutTriangleCount());
        output.putLong(mesh.transmissiveTriangleCount());
        output.putInt(mesh.segments().size());
        for (CpuClusterMesh.Segment segment : mesh.segments()) {
            output.putInt(segment.opaqueTriangleCount());
            output.putInt(segment.cutoutTriangleCount());
            output.putInt(segment.transmissiveTriangleCount());
            output.putInt(segment.opaqueMacroTriangleCount());
            output.putInt(segment.cutoutMacroTriangleCount());
            output.putInt(segment.transmissiveMacroTriangleCount());
            putFloats(output, segment.positions());
            putInts(output, segment.primitiveRecords());
            putInts(output, segment.surfaceRelationRecords());
        }

        putBytes(output, opacity.blocks());
        putInts(output, opacity.blockOffsets());
        putInts(output, opacity.blockFormats());
        putInts(output, opacity.blockSubdivisionLevels());
        putInts(output, opacity.triangleIndices());

        output.putInt(mesh.voxelMeshes().size());
        for (CpuVoxelMesh voxelMesh : mesh.voxelMeshes()) {
            output.putInt(voxelMesh.opaqueTriangleCount());
            output.putInt(voxelMesh.cutoutTriangleCount());
            output.putInt(voxelMesh.transmissiveTriangleCount());
            putFloats(output, voxelMesh.positions());
            putInts(output, voxelMesh.primitiveRecords());
            putOpacity(output, voxelMesh.opacityMicromap());
        }
        putInts(output, mesh.voxelInstances().meshIndices());
        putInts(output, mesh.voxelInstances().packedTints());
        putFloats(output, mesh.voxelInstances().translations());

        CompiledClusterLights.Summary lights = mesh.lights().summary();
        output.putInt(lights.emitterCount());
        output.putFloat(lights.minX());
        output.putFloat(lights.minY());
        output.putFloat(lights.minZ());
        output.putFloat(lights.maxX());
        output.putFloat(lights.maxY());
        output.putFloat(lights.maxZ());
        output.putFloat(lights.power());
        output.putInt(lights.packedDirection());
        putInts(output, mesh.lights().encodedWords());
        if (output.hasRemaining()) {
            throw new AssertionError("Compiled-cluster size calculation is incomplete");
        }
        return output.array();
    }

    public static CompiledCluster decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Compiled-cluster replay exceeds the size limit");
        }
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        try {
            if (input.getInt() != MAGIC) {
                throw new IllegalArgumentException(
                        "Unsupported compiled-cluster replay header");
            }
            int version = input.getInt();
            if (version < 1 || version > VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported compiled-cluster replay header");
            }
            long key = input.getLong();
            int clusterX = input.getInt();
            int clusterY = input.getInt();
            int clusterZ = input.getInt();
            long opaque = nonnegative(input.getLong(), "opaque triangle count");
            long cutout = nonnegative(input.getLong(), "cutout triangle count");
            long transmissive = nonnegative(
                    input.getLong(), "transmissive triangle count");
            int segmentCount = boundedCount(
                    input.getInt(), MAX_SEGMENTS, "cluster segment count");
            ArrayList<CpuClusterMesh.Segment> segments =
                    new ArrayList<>(segmentCount);
            for (int index = 0; index < segmentCount; index++) {
                int segmentOpaque = nonnegative(
                        input.getInt(), "segment opaque triangle count");
                int segmentCutout = nonnegative(
                        input.getInt(), "segment cutout triangle count");
                int segmentTransmissive = nonnegative(
                        input.getInt(), "segment transmissive triangle count");
                int segmentOpaqueMacro = version >= 5
                        ? nonnegative(input.getInt(), "segment opaque macro triangle count")
                        : 0;
                int segmentCutoutMacro = version >= 5
                        ? nonnegative(input.getInt(), "segment cutout macro triangle count")
                        : 0;
                int segmentTransmissiveMacro = version >= 5
                        ? nonnegative(
                                input.getInt(), "segment transmissive macro triangle count")
                        : 0;
                requireMacroCount(segmentOpaque, segmentOpaqueMacro);
                requireMacroCount(segmentCutout, segmentCutoutMacro);
                requireMacroCount(segmentTransmissive, segmentTransmissiveMacro);
                int triangles = Math.addExact(
                        Math.addExact(segmentOpaque, segmentCutout),
                        segmentTransmissive);
                int primitiveCount = Math.addExact(
                        Math.addExact(
                                CpuSectionMesh.primitiveCount(
                                        segmentOpaque, segmentOpaqueMacro),
                                CpuSectionMesh.primitiveCount(
                                        segmentCutout, segmentCutoutMacro)),
                        CpuSectionMesh.primitiveCount(
                                segmentTransmissive, segmentTransmissiveMacro));
                float[] positions = getFloats(
                        input,
                        Math.multiplyExact(triangles, 9),
                        "segment positions");
                int[] primitives = getInts(
                        input,
                        Math.multiplyExact(
                                primitiveCount, CpuSectionMesh.PRIMITIVE_WORDS),
                        "segment primitive records");
                if (version < 4) {
                    upgradePrimitivePacking(primitives);
                }
                if (version < 7) {
                    upgradeUvPacking(primitives);
                }
                int[] encodedRelations = version >= 8
                        ? getInts(
                                input,
                                version >= 9
                                        ? "segment surface-relation records"
                                        : "segment medium-boundary records")
                        : new int[0];
                int[] surfaceRelations = version >= 9
                        ? encodedRelations
                        : upgradeMediumBoundaries(
                                encodedRelations,
                                segmentOpaque,
                                segmentCutout,
                                segmentTransmissive,
                                segmentOpaqueMacro,
                                segmentCutoutMacro,
                                segmentTransmissiveMacro);
                if (version < 10) {
                    upgradeMaterialEncoding(primitives);
                    upgradeSurfaceRelations(surfaceRelations, primitiveCount);
                }
                segments.add(new CpuClusterMesh.Segment(
                        positions,
                        primitives,
                        surfaceRelations,
                        segmentOpaque,
                        segmentCutout,
                        segmentTransmissive,
                        segmentOpaqueMacro,
                        segmentCutoutMacro,
                        segmentTransmissiveMacro));
            }

            byte[] opacityBlocks = getBytes(input, "opacity blocks");
            int[] opacityOffsets = getInts(input, "opacity block offsets");
            int[] opacityFormats = getInts(input, "opacity block formats");
            int[] opacitySubdivisions = getInts(
                    input, "opacity subdivision levels");
            int[] opacityTriangles = getInts(
                    input, "opacity triangle indices");
            OpacityMicromapData opacity = version < 7
                    ? OpacityMicromapData.fullyUnknown(Math.toIntExact(cutout))
                    : OpacityMicromapData.fromEncoded(
                            opacityBlocks,
                            opacityOffsets,
                            opacityFormats,
                            opacitySubdivisions,
                            opacityTriangles);

            ArrayList<CpuVoxelMesh> voxelMeshes = new ArrayList<>();
            CpuVoxelInstances voxelInstances = CpuVoxelInstances.EMPTY;
            if (version >= 2) {
                int voxelMeshCount = boundedCount(
                        input.getInt(), MAX_VOXEL_MESHES, "voxel mesh count");
                voxelMeshes.ensureCapacity(voxelMeshCount);
                for (int index = 0; index < voxelMeshCount; index++) {
                    int meshOpaque = nonnegative(
                            input.getInt(), "voxel mesh opaque triangle count");
                    int meshCutout = nonnegative(
                            input.getInt(), "voxel mesh cutout triangle count");
                    int meshTransmissive = nonnegative(
                            input.getInt(), "voxel mesh transmissive triangle count");
                    int triangles = Math.addExact(
                            Math.addExact(meshOpaque, meshCutout),
                            meshTransmissive);
                    float[] meshPositions = getFloats(
                            input,
                            Math.multiplyExact(triangles, 9),
                            "voxel mesh positions");
                    int[] meshPrimitives = getInts(
                            input,
                            Math.multiplyExact(
                                    triangles, CpuSectionMesh.PRIMITIVE_WORDS),
                            "voxel mesh primitive records");
                    if (version < 4) {
                        upgradePrimitivePacking(meshPrimitives);
                    }
                    if (version < 7) {
                        upgradeUvPacking(meshPrimitives);
                    }
                    if (version < 10) {
                        upgradeMaterialEncoding(meshPrimitives);
                    }
                    OpacityMicromapData meshOpacity = getOpacity(input);
                    if (version < 7) {
                        meshOpacity = OpacityMicromapData.fullyUnknown(meshCutout);
                    }
                    voxelMeshes.add(new CpuVoxelMesh(
                            meshPositions,
                            meshPrimitives,
                            meshOpaque,
                            meshCutout,
                            meshTransmissive,
                            meshOpacity));
                }
                int[] meshIndices = getInts(input, "voxel instance mesh indices");
                if (meshIndices.length > MAX_VOXEL_INSTANCES) {
                    throw new IllegalArgumentException(
                            "Compiled-cluster voxel instance count is invalid");
                }
                int[] packedTints = getInts(input, "voxel instance tints");
                float[] translations = getFloats(
                        input,
                        Math.multiplyExact(meshIndices.length, 3),
                        "voxel instance translations");
                voxelInstances = new CpuVoxelInstances(
                        meshIndices, packedTints, translations);
            }

            int lightEmitterCount = nonnegative(input.getInt(), "light emitter count");
            float lightMinX = input.getFloat();
            float lightMinY = input.getFloat();
            float lightMinZ = input.getFloat();
            float lightMaxX = input.getFloat();
            float lightMaxY = input.getFloat();
            float lightMaxZ = input.getFloat();
            float lightPower = input.getFloat();
            int packedLightDirection = version >= 6 ? input.getInt() : LightDirection.FULL;
            CompiledClusterLights.Summary lightSummary = new CompiledClusterLights.Summary(
                    lightEmitterCount,
                    lightMinX,
                    lightMinY,
                    lightMinZ,
                    lightMaxX,
                    lightMaxY,
                    lightMaxZ,
                    lightPower,
                    packedLightDirection);
            int[] encodedLights = getInts(input, "compiled light words");
            if (version < 6 && lightEmitterCount != 0) {
                encodedLights = CompiledClusterLights.addFullDirectionStream(encodedLights);
            }
            if (version < 7 && lightEmitterCount != 0) {
                encodedLights = CompiledClusterLights.upgradeUvPacking(
                        encodedLights, lightEmitterCount);
            }
            if (version < 13 && lightEmitterCount != 0) {
                encodedLights = CompiledClusterLights.upgradeTreeLayout(
                        encodedLights, lightEmitterCount, packedLightDirection);
            }
            if (version < 14 && lightEmitterCount != 0) {
                encodedLights = CompiledClusterLights.addEmitterAliasTable(
                        encodedLights, lightEmitterCount);
            }
            CompiledClusterLights lights = CompiledClusterLights.fromEncoded(
                    encodedLights, lightSummary);
            if (input.hasRemaining()) {
                throw new IllegalArgumentException(
                        "Compiled-cluster replay contains trailing data");
            }
            CpuClusterMesh mesh = CpuClusterMesh.fromEncoded(
                    segments,
                    opaque,
                    cutout,
                    transmissive,
                    opacity,
                    lights,
                    voxelMeshes,
                    voxelInstances);
            validatePrimitiveRecords(mesh);
            return new CompiledCluster(
                    key, clusterX, clusterY, clusterZ, mesh);
        } catch (BufferUnderflowException | ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Compiled-cluster replay is truncated or inconsistent", exception);
        }
    }

    private static long encodedByteSize(CompiledCluster cluster) {
        long result = 56L;
        for (CpuClusterMesh.Segment segment : cluster.mesh().segments()) {
            result = Math.addExact(result, 24L);
            result = arrayBytes(result, segment.positions().length, Float.BYTES);
            result = arrayBytes(
                    result, segment.primitiveRecords().length, Integer.BYTES);
            result = arrayBytes(
                    result, segment.surfaceRelationRecords().length, Integer.BYTES);
        }
        OpacityMicromapData opacity = cluster.mesh().opacityMicromap();
        result = arrayBytes(result, opacity.blocks().length, Byte.BYTES);
        result = arrayBytes(result, opacity.blockOffsets().length, Integer.BYTES);
        result = arrayBytes(result, opacity.blockFormats().length, Integer.BYTES);
        result = arrayBytes(
                result, opacity.blockSubdivisionLevels().length, Integer.BYTES);
        result = arrayBytes(
                result, opacity.triangleIndices().length, Integer.BYTES);
        result = Math.addExact(result, Integer.BYTES);
        for (CpuVoxelMesh voxelMesh : cluster.mesh().voxelMeshes()) {
            result = Math.addExact(result, 3L * Integer.BYTES);
            result = arrayBytes(
                    result, voxelMesh.positions().length, Float.BYTES);
            result = arrayBytes(
                    result,
                    voxelMesh.primitiveRecords().length,
                    Integer.BYTES);
            result = opacityEncodedByteSize(
                    result, voxelMesh.opacityMicromap());
        }
        CpuVoxelInstances instances = cluster.mesh().voxelInstances();
        result = arrayBytes(result, instances.meshIndices().length, Integer.BYTES);
        result = arrayBytes(result, instances.packedTints().length, Integer.BYTES);
        result = arrayBytes(result, instances.translations().length, Float.BYTES);
        result = Math.addExact(result, 36L);
        result = arrayBytes(
                result,
                cluster.mesh().lights().encodedWords().length,
                Integer.BYTES);
        if (result > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException(
                    "Compiled cluster is too large for the replay format");
        }
        return result;
    }

    private static void upgradePrimitivePacking(int[] records) {
        for (int record = 0;
                record < records.length;
                record += CpuSectionMesh.PRIMITIVE_WORDS) {
            int oldPacked = records[record + 5];
            int flags = records[record + 3] >>> 24
                    | (oldPacked & 1) << 8;
            if ((oldPacked & PrimitivePacking.DYNAMIC_TEXTURE_FLAG) != 0) {
                int textureIndex = oldPacked >>> 1 & 0x1fff_ffff;
                boolean visibleEmission =
                        (oldPacked & 1 << 30) != 0;
                records[record + 5] = flags >>> 8
                        | textureIndex << 3
                        | (visibleEmission ? 1 << 30 : 0)
                        | PrimitivePacking.DYNAMIC_TEXTURE_FLAG;
            } else {
                int encodedEmitter = oldPacked >>> 1;
                records[record + 5] = flags >>> 8 | encodedEmitter << 3;
            }
        }
    }

    private static void upgradeMaterialEncoding(int[] records) {
        for (int record = 0;
                record < records.length;
                record += CpuSectionMesh.PRIMITIVE_WORDS) {
            int packedTint = records[record + 3];
            int packedPayload = records[record + 5];
            int legacyFlags = packedTint >>> 24 | (packedPayload & 7) << 8;
            int control = upgradeLegacyFlags(legacyFlags);
            records[record + 3] = PrimitivePacking.packTintControl(packedTint, control);
            if ((packedPayload & PrimitivePacking.DYNAMIC_TEXTURE_FLAG) != 0) {
                int textureIndex = packedPayload >>> 3 & 0x03ff_ffff;
                if (textureIndex > PrimitivePacking.DYNAMIC_TEXTURE_INDEX_MASK) {
                    throw new IllegalArgumentException(
                            "Legacy dynamic texture exceeds the v10 ABI field");
                }
                records[record + 5] = PrimitivePacking.packDynamicControl(
                        control,
                        textureIndex,
                        (packedPayload & 1 << 30) != 0,
                        (packedPayload & 1 << 29) != 0);
            } else if ((legacyFlags & 1 << 10) != 0) {
                records[record + 5] = PrimitivePacking.packRasterCompositeControl(
                        control, packedPayload >>> 3 & 0x00ff_ffff);
            } else {
                int encodedEmitter = packedPayload >>> 3 & 0x07ff_ffff;
                int emitterIndex = encodedEmitter == 0
                        ? PrimitivePacking.NO_EMITTER_INDEX
                        : encodedEmitter - 1;
                records[record + 5] = PrimitivePacking.packControlEmitter(
                        control, emitterIndex);
            }
        }
    }

    private static int upgradeLegacyFlags(int flags) {
        boolean cutout = (flags & 1) != 0;
        boolean animated = (flags & 1 << 1) != 0;
        boolean transmissive = (flags & 1 << 2) != 0;
        boolean thin = (flags & 1 << 3) != 0;
        boolean water = (flags & 1 << 4) != 0;
        boolean foliage = (flags & 1 << 5) != 0;
        int control = PrimitivePacking.encodeLegacySemantics(
                cutout, animated, transmissive, thin, water, foliage);
        control = PrimitivePacking.withMaterialDetails(
                control,
                (flags & 1 << 6) != 0,
                (flags & 1 << 7) != 0,
                (flags & 1 << 8) != 0);
        if ((flags & 1 << 9) != 0) {
            control |= PrimitivePacking.CONTROL_FRONT_FACE_ONLY;
        }
        if ((flags & 1 << 10) != 0) {
            control |= PrimitivePacking.CONTROL_RASTER_COMPOSITE;
        }
        PrimitivePacking.requireValidControl(control);
        return control;
    }

    private static void upgradeSurfaceRelations(int[] table, int primitiveCount) {
        if (table.length == 0) {
            return;
        }
        boolean[] upgraded = new boolean[table.length];
        for (int primitive = 0; primitive < primitiveCount; primitive++) {
            int offset = table[primitive];
            if (offset == 0 || upgraded[offset]) {
                continue;
            }
            upgraded[offset] = true;
            int legacyControl = table[offset];
            int kind = legacyControl & CpuSectionMesh.SURFACE_RELATION_KIND_MASK;
            if (kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY) {
                boolean water = (legacyControl & 1 << 4) != 0;
                boolean optical = (legacyControl & 1 << 5) != 0;
                int recipe = PrimitivePacking.encodeLegacySemantics(
                        false, false, true, false, water, false);
                recipe = PrimitivePacking.withMaterialDetails(
                        recipe, false, optical, false);
                table[offset] = kind
                        | CpuSectionMesh.SURFACE_RELATION_MICRO_GAP_ELIGIBLE
                        | PrimitivePacking.materialRecipeControl(recipe) << 8;
            } else if (kind == CpuSectionMesh.SURFACE_RELATION_OVERLAY) {
                int recipe = upgradeLegacyFlags(legacyControl >>> 8);
                table[offset] = kind
                        | (legacyControl & 1 << 4)
                        | PrimitivePacking.materialRecipeControl(recipe) << 8;
                upgradeMaterialEncodingAt(table, offset + 1);
            } else if (kind == CpuSectionMesh.SURFACE_RELATION_BILATERAL) {
                upgradeMaterialEncodingAt(table, offset + 1);
            } else {
                throw new IllegalArgumentException(
                        "Legacy surface relation has an unknown kind");
            }
        }
    }

    private static void upgradeMaterialEncodingAt(int[] table, int offset) {
        int[] material = new int[CpuSectionMesh.PRIMITIVE_WORDS];
        System.arraycopy(table, offset, material, 0, material.length);
        upgradeMaterialEncoding(material);
        System.arraycopy(material, 0, table, offset, material.length);
    }

    private static void upgradeUvPacking(int[] records) {
        for (int record = 0;
                record < records.length;
                record += CpuSectionMesh.PRIMITIVE_WORDS) {
            int flags = records[record + 3] >>> 24
                    | (records[record + 5] & 7) << 8;
            boolean rasterComposite = (flags & 1 << 10) != 0;
            boolean constantUv = !rasterComposite
                    && records[record + 6] == PrimitivePacking.CONSTANT_UV_DENSITY;
            if (constantUv) {
                continue;
            }
            for (int vertex = 0; vertex < 3; vertex++) {
                records[record + vertex] = PrimitivePacking.upgradeHalfUv(
                        records[record + vertex]);
            }
        }
    }

    private static long opacityEncodedByteSize(
            long current, OpacityMicromapData opacity) {
        long result = arrayBytes(current, opacity.blocks().length, Byte.BYTES);
        result = arrayBytes(result, opacity.blockOffsets().length, Integer.BYTES);
        result = arrayBytes(result, opacity.blockFormats().length, Integer.BYTES);
        result = arrayBytes(
                result, opacity.blockSubdivisionLevels().length, Integer.BYTES);
        return arrayBytes(
                result, opacity.triangleIndices().length, Integer.BYTES);
    }

    private static long arrayBytes(long current, int length, int elementBytes) {
        return Math.addExact(
                Math.addExact(current, Integer.BYTES),
                Math.multiplyExact((long) length, elementBytes));
    }

    private static void putBytes(ByteBuffer output, byte[] values) {
        output.putInt(values.length);
        output.put(values);
    }

    private static void putFloats(ByteBuffer output, float[] values) {
        output.putInt(values.length);
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Compiled-cluster positions must be finite");
            }
            output.putInt(Float.floatToRawIntBits(value));
        }
    }

    private static void putOpacity(
            ByteBuffer output, OpacityMicromapData opacity) {
        opacity.requireValidTriangleIndices();
        putBytes(output, opacity.blocks());
        putInts(output, opacity.blockOffsets());
        putInts(output, opacity.blockFormats());
        putInts(output, opacity.blockSubdivisionLevels());
        putInts(output, opacity.triangleIndices());
    }

    private static void putInts(ByteBuffer output, int[] values) {
        output.putInt(values.length);
        for (int value : values) {
            output.putInt(value);
        }
    }

    private static byte[] getBytes(ByteBuffer input, String label) {
        int count = readableCount(input, Byte.BYTES, label);
        byte[] result = new byte[count];
        input.get(result);
        return result;
    }

    private static float[] getFloats(
            ByteBuffer input, int expectedCount, String label) {
        requireExpectedCount(input, expectedCount, Float.BYTES, label);
        float[] result = new float[expectedCount];
        for (int index = 0; index < expectedCount; index++) {
            float value = Float.intBitsToFloat(input.getInt());
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Compiled-cluster " + label + " must be finite");
            }
            result[index] = value;
        }
        return result;
    }

    private static OpacityMicromapData getOpacity(ByteBuffer input) {
        return OpacityMicromapData.fromEncoded(
                getBytes(input, "voxel opacity blocks"),
                getInts(input, "voxel opacity block offsets"),
                getInts(input, "voxel opacity block formats"),
                getInts(input, "voxel opacity subdivision levels"),
                getInts(input, "voxel opacity triangle indices"));
    }

    private static void validatePrimitiveRecords(CpuClusterMesh mesh) {
        int emitterCount = mesh.lights().emitterCount();
        for (CpuClusterMesh.Segment segment : mesh.segments()) {
            validatePrimitiveRecords(
                    segment.primitiveRecords(),
                    segment.opaqueTriangleCount(),
                    segment.cutoutTriangleCount(),
                    segment.transmissiveTriangleCount(),
                    segment.opaqueMacroTriangleCount(),
                    segment.cutoutMacroTriangleCount(),
                    segment.transmissiveMacroTriangleCount(),
                    emitterCount);
            SurfaceRelationTable.validate(
                    segment.surfaceRelationRecords(),
                    segment.opaquePrimitiveCount()
                            + segment.cutoutPrimitiveCount()
                            + segment.transmissivePrimitiveCount());
        }
        for (CpuVoxelMesh voxelMesh : mesh.voxelMeshes()) {
            validatePrimitiveRecords(
                    voxelMesh.primitiveRecords(),
                    voxelMesh.opaqueTriangleCount(),
                    voxelMesh.cutoutTriangleCount(),
                    voxelMesh.transmissiveTriangleCount(),
                    0,
                    0,
                    0,
                    0);
        }
    }

    private static int[] upgradeMediumBoundaries(
            int[] records,
            int opaqueTriangles,
            int cutoutTriangles,
            int transmissiveTriangles,
            int opaqueMacroTriangles,
            int cutoutMacroTriangles,
            int transmissiveMacroTriangles) {
        if (records.length == 0) {
            return records;
        }
        int opaquePrimitives = CpuSectionMesh.primitiveCount(
                opaqueTriangles, opaqueMacroTriangles);
        int cutoutPrimitives = CpuSectionMesh.primitiveCount(
                cutoutTriangles, cutoutMacroTriangles);
        int transmissivePrimitives = CpuSectionMesh.primitiveCount(
                transmissiveTriangles, transmissiveMacroTriangles);
        if (records.length != transmissivePrimitives * 3) {
            throw new IllegalArgumentException(
                    "Legacy medium-boundary table has an invalid length");
        }
        ArrayList<int[]> relations = new ArrayList<>(
                opaquePrimitives + cutoutPrimitives + transmissivePrimitives);
        for (int primitive = 0;
                primitive < opaquePrimitives + cutoutPrimitives;
                primitive++) {
            relations.add(null);
        }
        for (int primitive = 0; primitive < transmissivePrimitives; primitive++) {
            int offset = primitive * 3;
            int oldControl = records[offset + 2];
            if (oldControl == 0) {
                relations.add(null);
                continue;
            }
            int control = CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                    | ((oldControl & 2) != 0
                            ? 1 << 4
                            : 0)
                    | ((oldControl & 4) != 0
                            ? 1 << 5
                            : 0);
            relations.add(new int[] {
                control, records[offset], records[offset + 1]
            });
        }
        return SurfaceRelationTable.encode(relations);
    }

    private static void validatePrimitiveRecords(
            int[] records,
            int opaqueCount,
            int cutoutCount,
            int transmissiveCount,
            int opaqueMacroCount,
            int cutoutMacroCount,
            int transmissiveMacroCount,
            int emitterCount) {
        int opaqueEnd = CpuSectionMesh.primitiveCount(opaqueCount, opaqueMacroCount);
        int cutoutEnd = Math.addExact(
                opaqueEnd,
                CpuSectionMesh.primitiveCount(cutoutCount, cutoutMacroCount));
        int primitiveCount = Math.addExact(
                cutoutEnd,
                CpuSectionMesh.primitiveCount(
                        transmissiveCount, transmissiveMacroCount));
        for (int primitiveIndex = 0; primitiveIndex < primitiveCount; primitiveIndex++) {
            int record = Math.multiplyExact(
                    primitiveIndex, CpuSectionMesh.PRIMITIVE_WORDS);
            int flags = PrimitivePacking.unpackControl(
                    records[record + 3], records[record + 5]);
            PrimitivePacking.requireValidControl(flags);
            boolean rasterComposite =
                    (flags & PrimitivePacking.CONTROL_RASTER_COMPOSITE) != 0;
            boolean constantUv = !rasterComposite
                    && records[record + 6]
                            == PrimitivePacking.CONSTANT_UV_DENSITY;
            int constantMode = 0;
            if (constantUv) {
                constantMode = records[record + 2];
                if ((constantMode & ~PrimitivePacking.CONSTANT_UV_MODE_MASK) != 0
                        || ((constantMode & PrimitivePacking.CONSTANT_UV_OWN_TINT) != 0
                                && (constantMode
                                                & PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL)
                                        == 0)) {
                    throw new IllegalArgumentException(
                            "Compiled-cluster constant UV has invalid reserved data");
                }
                if ((constantMode & PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL) == 0) {
                    requireNormalizedFloatUv(records[record]);
                    requireNormalizedFloatUv(records[record + 1]);
                }
            }
            boolean cutout = PrimitivePacking.isCutout(flags);
            boolean transmissive = PrimitivePacking.isTransmissive(flags);
            if ((constantMode & PrimitivePacking.CONSTANT_UV_BAKED_MATERIAL) != 0
                    && (cutout || transmissive)) {
                throw new IllegalArgumentException(
                        "Compiled-cluster baked material must be opaque");
            }
            boolean categoryMismatch = primitiveIndex < opaqueEnd
                    ? cutout || transmissive
                    : primitiveIndex < cutoutEnd
                            ? !cutout || transmissive
                            : !transmissive;
            if (categoryMismatch) {
                throw new IllegalArgumentException(
                        "Compiled-cluster primitive flags disagree with geometry categories");
            }
            int emitterIndex = PrimitivePacking.unpackEmitterIndex(
                    records[record + 5]);
            if (emitterIndex >= emitterCount) {
                throw new IllegalArgumentException(
                        "Compiled-cluster primitive references an invalid emitter");
            }
            if (!rasterComposite) {
                float uvDensity =
                        Float.intBitsToFloat(records[record + 6]);
                if (!Float.isFinite(uvDensity)) {
                    throw new IllegalArgumentException(
                            "Compiled-cluster UV density must be finite");
                }
            }
        }
    }

    private static void requireNormalizedFloatUv(int packed) {
        float coordinate = Float.intBitsToFloat(packed);
        if (!(coordinate >= 0.0F && coordinate <= 1.0F)
                || !Float.isFinite(coordinate)) {
            throw new IllegalArgumentException(
                    "Compiled-cluster constant UV must be finite and normalized");
        }
    }

    private static int[] getInts(
            ByteBuffer input, int expectedCount, String label) {
        requireExpectedCount(input, expectedCount, Integer.BYTES, label);
        int[] result = new int[expectedCount];
        for (int index = 0; index < expectedCount; index++) {
            result[index] = input.getInt();
        }
        return result;
    }

    private static int[] getInts(ByteBuffer input, String label) {
        int count = readableCount(input, Integer.BYTES, label);
        int[] result = new int[count];
        for (int index = 0; index < count; index++) {
            result[index] = input.getInt();
        }
        return result;
    }

    private static void requireExpectedCount(
            ByteBuffer input,
            int expectedCount,
            int elementBytes,
            String label) {
        int encodedCount = input.getInt();
        if (encodedCount != expectedCount
                || (long) expectedCount * elementBytes > input.remaining()) {
            throw new IllegalArgumentException(
                    "Compiled-cluster " + label + " length is inconsistent");
        }
    }

    private static int readableCount(
            ByteBuffer input, int elementBytes, String label) {
        int count = input.getInt();
        if (count < 0 || (long) count * elementBytes > input.remaining()) {
            throw new IllegalArgumentException(
                    "Compiled-cluster " + label + " length is invalid");
        }
        return count;
    }

    private static int boundedCount(int value, int maximum, String label) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException(
                    "Compiled-cluster " + label + " is invalid");
        }
        return value;
    }

    private static int nonnegative(int value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "Compiled-cluster " + label + " is negative");
        }
        return value;
    }

    private static void requireMacroCount(int triangleCount, int macroTriangleCount) {
        if (macroTriangleCount > triangleCount || (macroTriangleCount & 1) != 0) {
            throw new IllegalArgumentException(
                    "Compiled-cluster macro triangle count is invalid");
        }
    }

    private static long nonnegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(
                    "Compiled-cluster " + label + " is negative");
        }
        return value;
    }
}
