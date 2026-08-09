package dev.prime.render.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CompiledClusterCodecTest {
    @Test
    void dynamicClustersCannotEnterThePersistentCacheFormat() {
        CompiledCluster dynamic = CompiledCluster.dynamic(
                0, 0, 0, CpuClusterMesh.empty(), new float[0]);

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.encode(dynamic));
    }

    @Test
    void roundTripPreservesSharedMacroPrimitiveLayout() {
        int[] primitive = {
            PrimitivePacking.packUv(0.0F, 0.0F),
            PrimitivePacking.packUv(1.0F, 0.0F),
            PrimitivePacking.packUv(0.0F, 1.0F),
            PrimitivePacking.packTintControl(PrimitivePacking.packTint(-1), 0),
            PrimitivePacking.packOctahedralNormal(0.0F, 0.0F, 1.0F),
            PrimitivePacking.packControlEmitter(0, PrimitivePacking.NO_EMITTER_INDEX),
            Float.floatToRawIntBits(-1.0F),
            PrimitivePacking.packOctahedralNormal(1.0F, 0.0F, 0.0F)
        };
        CpuSectionMesh section = new CpuSectionMesh(
                new float[] {
                    0, 0, 0, 1, 0, 0, 1, 1, 0,
                    0, 0, 0, 1, 1, 0, 0, 1, 0
                },
                primitive,
                2,
                0,
                0,
                2,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CpuSectionLights.EMPTY);
        CompiledCluster source = new CompiledCluster(
                0L, 0, 0, 0, CpuClusterMesh.fromSegments(List.of(section)));

        byte[] encoded = CompiledClusterCodec.encode(source);
        CpuClusterMesh decoded = CompiledClusterCodec.decode(encoded).mesh();

        assertArrayEquals(encoded, CompiledClusterCodec.encode(
                new CompiledCluster(0L, 0, 0, 0, decoded)));
        assertEquals(2L, decoded.opaqueMacroTriangleCount());
        assertEquals(1L, decoded.primitiveCount());
        assertEquals(CpuSectionMesh.PRIMITIVE_WORDS,
                decoded.segments().getFirst().primitiveRecords().length);
    }

    @Test
    void canonicalRoundTripPreservesTheCompleteUploadInput() {
        int flags = PrimitivePacking.encodeLegacySemantics(
                true, false, false, false, false, false);
        CpuSectionMesh section = new CpuSectionMesh(
                new float[] {
                    -0.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.0F
                },
                new int[] {
                    PrimitivePacking.packUv(0.0F, 0.0F),
                    PrimitivePacking.packUv(1.0F, 0.0F),
                    PrimitivePacking.packUv(0.0F, 1.0F),
                    PrimitivePacking.packTintControl(
                            PrimitivePacking.packTint(-1), flags),
                    PrimitivePacking.packOctahedralNormal(0.0F, 0.0F, 1.0F),
                    PrimitivePacking.packControlEmitter(
                            flags, PrimitivePacking.NO_EMITTER_INDEX),
                    Float.floatToRawIntBits(1.0F),
                    PrimitivePacking.packOctahedralNormal(1.0F, 0.0F, 0.0F)
                },
                0,
                1,
                0,
                OpacityMicromapData.fullyUnknown(1),
                CpuSectionLights.EMPTY);
        CompiledCluster source = new CompiledCluster(
                0L,
                0,
                0,
                0,
                CpuClusterMesh.fromSegments(List.of(section)));

        byte[] encoded = CompiledClusterCodec.encode(source);
        CompiledCluster decoded = CompiledClusterCodec.decode(encoded);

        assertArrayEquals(encoded, CompiledClusterCodec.encode(decoded));
        assertEquals(
                CompiledClusterFingerprint.sha256Hex(source),
                CompiledClusterFingerprint.sha256Hex(decoded));
        assertEquals(1L, decoded.mesh().cutoutTriangleCount());
        assertEquals(1, decoded.mesh().opacityMicromap().triangleCount());
    }

    @Test
    void versionThreePrimitivePackingMigratesWithoutInventingFrontFaceSelection() {
        int flags = PrimitivePacking.CONTROL_ALPHA_CUTOUT
                | PrimitivePacking.CONTROL_OPTICAL_TEXTURE;
        int[] primitive = {
            PrimitivePacking.packUv(0.0F, 0.0F),
            PrimitivePacking.packUv(1.0F, 0.0F),
            PrimitivePacking.packUv(0.0F, 1.0F),
            PrimitivePacking.packTintControl(
                    PrimitivePacking.packTint(-1), flags),
            PrimitivePacking.packOctahedralNormal(0.0F, 0.0F, 1.0F),
            PrimitivePacking.packDynamicControl(flags, 17, true),
            Float.floatToRawIntBits(1.0F),
            PrimitivePacking.packOctahedralNormal(1.0F, 0.0F, 0.0F)
        };
        CpuSectionMesh section = new CpuSectionMesh(
                new float[] {
                    0.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.0F
                },
                primitive,
                0,
                1,
                0,
                OpacityMicromapData.fullyUnknown(1),
                CpuSectionLights.EMPTY);
        byte[] encoded = CompiledClusterCodec.encode(new CompiledCluster(
                0L,
                0,
                0,
                0,
                CpuClusterMesh.fromSegments(List.of(section))));
        PayloadOffsets offsets = firstPayloadOffsets(encoded);
        byte[] legacyBytes = withoutCurrentSegmentMacroCounts(encoded);
        ByteBuffer legacy = littleEndian(legacyBytes);
        legacy.putInt(4, 3);
        int legacyPrimitive = offsets.primitives() - 3 * Integer.BYTES;
        legacy.putInt(legacyPrimitive, PrimitivePacking.packHalf2(0.0F, 0.0F));
        legacy.putInt(
                legacyPrimitive + Integer.BYTES,
                PrimitivePacking.packHalf2(1.0F, 0.0F));
        legacy.putInt(
                legacyPrimitive + 2 * Integer.BYTES,
                PrimitivePacking.packHalf2(0.0F, 1.0F));
        legacy.putInt(
                legacyPrimitive + 3 * Integer.BYTES,
                PrimitivePacking.packTint(-1) & 0x00ff_ffff
                        | (1 | 1 << 7) << 24);
        legacy.putInt(
                legacyPrimitive + 5 * Integer.BYTES,
                17 << 1
                        | 1 << 30
                        | PrimitivePacking.DYNAMIC_TEXTURE_FLAG);

        CompiledCluster decoded = CompiledClusterCodec.decode(legacyBytes);
        int[] decodedPrimitive =
                decoded.mesh().segments().getFirst().primitiveRecords();
        int decodedFlags = PrimitivePacking.unpackControl(
                decodedPrimitive[3], decodedPrimitive[5]);

        assertEquals(flags, decodedFlags);
        assertEquals(
                0, decodedFlags & PrimitivePacking.CONTROL_FRONT_FACE_ONLY);
        assertEquals(
                17,
                PrimitivePacking.unpackDynamicTextureIndex(
                        decodedPrimitive[5]));
        assertEquals(PrimitivePacking.packUv(0.0F, 0.0F), decodedPrimitive[0]);
        assertEquals(PrimitivePacking.packUv(1.0F, 0.0F), decodedPrimitive[1]);
        assertEquals(PrimitivePacking.packUv(0.0F, 1.0F), decodedPrimitive[2]);
    }

    @Test
    void versionNineMigratesPrimitiveVoxelAndNestedRelationRecipes() {
        int cutout = PrimitivePacking.CONTROL_ALPHA_CUTOUT
                | PrimitivePacking.CONTROL_OPTICAL_TEXTURE;
        int transmissive = PrimitivePacking.CONTROL_DIELECTRIC_SOLID;
        int adjacentWater = PrimitivePacking.CONTROL_DIELECTRIC_SOLID
                | PrimitivePacking.CONTROL_WATER_MEDIUM
                | PrimitivePacking.CONTROL_OPTICAL_TEXTURE;
        int[] primary = concat(
                primitive(cutout),
                primitive(cutout),
                primitive(transmissive));
        int[] relations = SurfaceRelationTable.encode(List.of(
                concat(
                        new int[] {
                            CpuSectionMesh.SURFACE_RELATION_OVERLAY
                                    | PrimitivePacking.materialRecipeControl(cutout) << 8
                        },
                        primitive(0)),
                concat(
                        new int[] {CpuSectionMesh.SURFACE_RELATION_BILATERAL},
                        primitive(cutout)),
                new int[] {
                    CpuSectionMesh.SURFACE_RELATION_BOUNDARY
                            | CpuSectionMesh.SURFACE_RELATION_MICRO_GAP_ELIGIBLE
                            | PrimitivePacking.materialRecipeControl(adjacentWater) << 8,
                    PrimitivePacking.packUv(0.5F, 0.5F),
                    PrimitivePacking.packTint(-1)
                }));
        CpuSectionMesh section = new CpuSectionMesh(
                new float[27],
                primary,
                relations,
                0,
                2,
                1,
                0,
                0,
                0,
                OpacityMicromapData.fullyUnknown(2),
                CpuSectionLights.EMPTY);
        CpuVoxelMesh voxel = new CpuVoxelMesh(
                new float[9],
                primitive(PrimitivePacking.CONTROL_OPTICAL_TEXTURE),
                1,
                0,
                0,
                OpacityMicromapData.EMPTY);
        CpuClusterMesh mesh = CpuClusterMesh.fromSegments(
                List.of(section),
                List.of(voxel),
                new CpuVoxelInstances(
                        new int[] {0}, new int[] {0x00ff_ffff}, new float[3]));
        byte[] versionNine = downgradeToVersionNine(CompiledClusterCodec.encode(
                new CompiledCluster(0L, 0, 0, 0, mesh)));

        CpuClusterMesh decoded = CompiledClusterCodec.decode(versionNine).mesh();

        assertArrayEquals(
                primary, decoded.segments().getFirst().primitiveRecords());
        assertArrayEquals(
                relations, decoded.segments().getFirst().surfaceRelationRecords());
        assertArrayEquals(
                voxel.primitiveRecords(), decoded.voxelMeshes().getFirst().primitiveRecords());
    }

    @Test
    void versionNineEmitterOutsideTheV10FieldIsRejectedForRebuild() {
        CpuSectionMesh section = new CpuSectionMesh(
                new float[9],
                primitive(0),
                1,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CpuSectionLights.EMPTY);
        byte[] encoded = CompiledClusterCodec.encode(new CompiledCluster(
                0L, 0, 0, 0, CpuClusterMesh.fromSegments(List.of(section))));
        ByteBuffer legacy = littleEndian(encoded);
        legacy.putInt(4, 9);
        int primitive = firstPayloadOffsets(encoded).primitives();
        legacy.putInt(
                primitive + 5 * Integer.BYTES,
                (PrimitivePacking.MAX_EMITTER_INDEX + 2) << 3);

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(encoded));
    }

    @Test
    void malformedOrTruncatedPayloadsFailBeforePublication() {
        CpuSectionMesh section = new CpuSectionMesh(
                new float[9],
                new int[CpuSectionMesh.PRIMITIVE_WORDS],
                1,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CpuSectionLights.EMPTY);
        byte[] valid = CompiledClusterCodec.encode(new CompiledCluster(
                0L,
                0,
                0,
                0,
                CpuClusterMesh.fromSegments(List.of(section))));
        byte[] wrongMagic = valid.clone();
        wrongMagic[0] ^= 1;

        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(wrongMagic));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(
                        Arrays.copyOf(valid, valid.length - 1)));
    }

    @Test
    void decodedUploadInputRejectsNonFiniteAndOutOfContractPrimitiveData() {
        CpuSectionMesh section = new CpuSectionMesh(
                new float[] {
                    0.0F, 0.0F, 0.0F,
                    1.0F, 0.0F, 0.0F,
                    0.0F, 1.0F, 0.0F
                },
                new int[] {
                    PrimitivePacking.packUv(0.0F, 0.0F),
                    PrimitivePacking.packUv(1.0F, 0.0F),
                    PrimitivePacking.packUv(0.0F, 1.0F),
                    PrimitivePacking.packTintControl(
                            PrimitivePacking.packTint(-1), 0),
                    PrimitivePacking.packOctahedralNormal(0.0F, 0.0F, 1.0F),
                    PrimitivePacking.packControlEmitter(
                            0, PrimitivePacking.NO_EMITTER_INDEX),
                    Float.floatToRawIntBits(1.0F),
                    PrimitivePacking.packOctahedralNormal(1.0F, 0.0F, 0.0F)
                },
                1,
                0,
                0,
                OpacityMicromapData.EMPTY,
                CpuSectionLights.EMPTY);
        byte[] valid = CompiledClusterCodec.encode(new CompiledCluster(
                0L,
                0,
                0,
                0,
                CpuClusterMesh.fromSegments(List.of(section))));
        PayloadOffsets offsets = firstPayloadOffsets(valid);

        byte[] nonFinitePosition = valid.clone();
        littleEndian(nonFinitePosition).putInt(
                offsets.positions(), Float.floatToRawIntBits(Float.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(nonFinitePosition));

        byte[] nonFiniteUv = valid.clone();
        ByteBuffer legacyUv = littleEndian(nonFiniteUv);
        legacyUv.putInt(4, 6);
        legacyUv.putInt(offsets.primitives(), 0x0000_7c00);
        legacyUv.putInt(
                offsets.primitives() + Integer.BYTES,
                PrimitivePacking.packHalf2(1.0F, 0.0F));
        legacyUv.putInt(
                offsets.primitives() + 2 * Integer.BYTES,
                PrimitivePacking.packHalf2(0.0F, 1.0F));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(nonFiniteUv));

        byte[] invalidEmitter = valid.clone();
        littleEndian(invalidEmitter).putInt(
                offsets.primitives() + 5 * Integer.BYTES,
                PrimitivePacking.packControlEmitter(0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(invalidEmitter));

        byte[] wrongCategory = valid.clone();
        int transmissive = PrimitivePacking.encodeLegacySemantics(
                false, false, true, false, false, false);
        littleEndian(wrongCategory).putInt(
                offsets.primitives() + 3 * Integer.BYTES,
                PrimitivePacking.packTintControl(
                        PrimitivePacking.packTint(-1), transmissive));
        littleEndian(wrongCategory).putInt(
                offsets.primitives() + 5 * Integer.BYTES,
                PrimitivePacking.packControlEmitter(
                        transmissive, PrimitivePacking.NO_EMITTER_INDEX));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(wrongCategory));

        byte[] nonFiniteUvDensity = valid.clone();
        littleEndian(nonFiniteUvDensity).putInt(
                offsets.primitives() + 6 * Integer.BYTES,
                Float.floatToRawIntBits(Float.POSITIVE_INFINITY));
        assertThrows(
                IllegalArgumentException.class,
                () -> CompiledClusterCodec.decode(nonFiniteUvDensity));
    }

    private static PayloadOffsets firstPayloadOffsets(byte[] encoded) {
        ByteBuffer input = littleEndian(encoded);
        input.position(56);
        input.position(input.position() + 6 * Integer.BYTES);
        int positionCount = input.getInt();
        int positions = input.position();
        input.position(Math.addExact(
                positions, Math.multiplyExact(positionCount, Float.BYTES)));
        int primitiveCount = input.getInt();
        if (positionCount != 9 || primitiveCount != CpuSectionMesh.PRIMITIVE_WORDS) {
            throw new AssertionError("Unexpected single-triangle fixture layout");
        }
        return new PayloadOffsets(positions, input.position());
    }

    private static int[] primitive(int control) {
        return new int[] {
            PrimitivePacking.packUv(0.0F, 0.0F),
            PrimitivePacking.packUv(1.0F, 0.0F),
            PrimitivePacking.packUv(0.0F, 1.0F),
            PrimitivePacking.packTintControl(PrimitivePacking.packTint(-1), control),
            PrimitivePacking.packOctahedralNormal(0.0F, 0.0F, 1.0F),
            PrimitivePacking.packControlEmitter(
                    control, PrimitivePacking.NO_EMITTER_INDEX),
            Float.floatToRawIntBits(1.0F),
            PrimitivePacking.packOctahedralNormal(1.0F, 0.0F, 0.0F)
        };
    }

    private static int[] concat(int[]... arrays) {
        int length = 0;
        for (int[] array : arrays) {
            length = Math.addExact(length, array.length);
        }
        int[] result = new int[length];
        int offset = 0;
        for (int[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private static byte[] downgradeToVersionNine(byte[] encoded) {
        byte[] result = encoded.clone();
        ByteBuffer data = littleEndian(result);
        data.putInt(4, 9);
        data.position(52);
        int segmentCount = data.getInt();
        for (int segment = 0; segment < segmentCount; segment++) {
            data.position(data.position() + 6 * Integer.BYTES);
            skipArray(data, Float.BYTES);
            int primitiveWords = data.getInt();
            int primitiveOffset = data.position();
            downgradePrimitives(data, primitiveOffset, primitiveWords);
            data.position(primitiveOffset + primitiveWords * Integer.BYTES);
            int relationWords = data.getInt();
            int relationOffset = data.position();
            downgradeRelations(
                    data, relationOffset, relationWords, primitiveWords / CpuSectionMesh.PRIMITIVE_WORDS);
            data.position(relationOffset + relationWords * Integer.BYTES);
        }
        skipArray(data, Byte.BYTES);
        for (int array = 0; array < 4; array++) {
            skipArray(data, Integer.BYTES);
        }
        int voxelCount = data.getInt();
        for (int voxel = 0; voxel < voxelCount; voxel++) {
            data.position(data.position() + 3 * Integer.BYTES);
            skipArray(data, Float.BYTES);
            int primitiveWords = data.getInt();
            int primitiveOffset = data.position();
            downgradePrimitives(data, primitiveOffset, primitiveWords);
            data.position(primitiveOffset + primitiveWords * Integer.BYTES);
            skipArray(data, Byte.BYTES);
            for (int array = 0; array < 4; array++) {
                skipArray(data, Integer.BYTES);
            }
        }
        return result;
    }

    private static void downgradeRelations(
            ByteBuffer data, int offset, int words, int primitiveCount) {
        if (words == 0) {
            return;
        }
        boolean[] visited = new boolean[words];
        for (int primitive = 0; primitive < primitiveCount; primitive++) {
            int relation = data.getInt(offset + primitive * Integer.BYTES);
            if (relation == 0 || visited[relation]) {
                continue;
            }
            visited[relation] = true;
            int controlOffset = offset + relation * Integer.BYTES;
            int control = data.getInt(controlOffset);
            int kind = control & CpuSectionMesh.SURFACE_RELATION_KIND_MASK;
            if (kind == CpuSectionMesh.SURFACE_RELATION_BOUNDARY) {
                int recipe = control >>> 8;
                int legacy = kind
                        | ((recipe & PrimitivePacking.CONTROL_WATER_MEDIUM) != 0
                                ? 1 << 4
                                : 0)
                        | ((recipe & PrimitivePacking.CONTROL_OPTICAL_TEXTURE) != 0
                                ? 1 << 5
                                : 0);
                data.putInt(controlOffset, legacy);
            } else if (kind == CpuSectionMesh.SURFACE_RELATION_OVERLAY) {
                data.putInt(
                        controlOffset,
                        kind
                                | (control & CpuSectionMesh.SURFACE_RELATION_POSITIVE_ONLY)
                                | legacyFlags(control >>> 8) << 8);
                downgradePrimitives(
                        data,
                        controlOffset + Integer.BYTES,
                        CpuSectionMesh.PRIMITIVE_WORDS);
            } else if (kind == CpuSectionMesh.SURFACE_RELATION_BILATERAL) {
                downgradePrimitives(
                        data,
                        controlOffset + Integer.BYTES,
                        CpuSectionMesh.PRIMITIVE_WORDS);
            } else {
                throw new AssertionError("Unexpected relation kind");
            }
        }
    }

    private static void downgradePrimitives(ByteBuffer data, int offset, int words) {
        for (int word = 0; word < words; word += CpuSectionMesh.PRIMITIVE_WORDS) {
            int record = offset + word * Integer.BYTES;
            int tint = data.getInt(record + 3 * Integer.BYTES);
            int payload = data.getInt(record + 5 * Integer.BYTES);
            int control = PrimitivePacking.unpackControl(tint, payload);
            int legacy = legacyFlags(control);
            data.putInt(
                    record + 3 * Integer.BYTES,
                    tint & 0x00ff_ffff | (legacy & 0xff) << 24);
            int emitter = PrimitivePacking.unpackEmitterIndex(payload);
            int encodedEmitter = emitter == PrimitivePacking.NO_EMITTER_INDEX
                    ? 0
                    : emitter + 1;
            data.putInt(
                    record + 5 * Integer.BYTES,
                    legacy >>> 8 | encodedEmitter << 3);
        }
    }

    private static int legacyFlags(int control) {
        int scattering = control & PrimitivePacking.CONTROL_SCATTERING_MASK;
        return ((control & PrimitivePacking.CONTROL_ALPHA_CUTOUT) != 0 ? 1 : 0)
                | ((control & PrimitivePacking.CONTROL_ANIMATED) != 0 ? 1 << 1 : 0)
                | (PrimitivePacking.isTransmissive(control) ? 1 << 2 : 0)
                | (PrimitivePacking.isThinWalled(control) ? 1 << 3 : 0)
                | ((control & PrimitivePacking.CONTROL_WATER_MEDIUM) != 0 ? 1 << 4 : 0)
                | (scattering == PrimitivePacking.CONTROL_FOLIAGE_THIN ? 1 << 5 : 0)
                | ((control & PrimitivePacking.CONTROL_NORMAL_TEXTURE) != 0 ? 1 << 6 : 0)
                | ((control & PrimitivePacking.CONTROL_OPTICAL_TEXTURE) != 0 ? 1 << 7 : 0)
                | ((control & PrimitivePacking.CONTROL_TANGENT_NEGATIVE) != 0 ? 1 << 8 : 0)
                | ((control & PrimitivePacking.CONTROL_FRONT_FACE_ONLY) != 0 ? 1 << 9 : 0)
                | ((control & PrimitivePacking.CONTROL_RASTER_COMPOSITE) != 0 ? 1 << 10 : 0);
    }

    private static void skipArray(ByteBuffer data, int elementBytes) {
        int length = data.getInt();
        data.position(data.position() + Math.multiplyExact(length, elementBytes));
    }

    private static byte[] withoutCurrentSegmentMacroCounts(byte[] encoded) {
        PayloadOffsets offsets = firstPayloadOffsets(encoded);
        int macroCounts = 3 * Integer.BYTES;
        int macroOffset = 56 + 3 * Integer.BYTES;
        byte[] withoutMacros = new byte[encoded.length - macroCounts];
        System.arraycopy(encoded, 0, withoutMacros, 0, macroOffset);
        System.arraycopy(
                encoded,
                macroOffset + macroCounts,
                withoutMacros,
                macroOffset,
                encoded.length - macroOffset - macroCounts);
        int boundaryLengthOffset = offsets.primitives()
                + CpuSectionMesh.PRIMITIVE_WORDS * Integer.BYTES
                - macroCounts;
        byte[] withoutBoundary = new byte[withoutMacros.length - Integer.BYTES];
        System.arraycopy(withoutMacros, 0, withoutBoundary, 0, boundaryLengthOffset);
        System.arraycopy(
                withoutMacros,
                boundaryLengthOffset + Integer.BYTES,
                withoutBoundary,
                boundaryLengthOffset,
                withoutMacros.length - boundaryLengthOffset - Integer.BYTES);
        // This fixture has empty lights, so the v6 direction summary is immediately before the
        // final zero-length light-word array.
        int directionOffset = withoutBoundary.length - 2 * Integer.BYTES;
        byte[] result = new byte[withoutBoundary.length - Integer.BYTES];
        System.arraycopy(withoutBoundary, 0, result, 0, directionOffset);
        System.arraycopy(
                withoutBoundary,
                directionOffset + Integer.BYTES,
                result,
                directionOffset,
                Integer.BYTES);
        return result;
    }

    private static ByteBuffer littleEndian(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    private record PayloadOffsets(int positions, int primitives) {
    }
}
