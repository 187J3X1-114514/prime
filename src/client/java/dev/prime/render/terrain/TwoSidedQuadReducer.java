package dev.prime.render.terrain;

import dev.prime.render.scene.CapturedSectionGeometry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collapses raster front/back quad pairs into one physical ray-tracing sheet.
 *
 * <p>Minecraft's cross models and fluid renderer author coincident reverse faces because
 * rasterization culls back faces. Equal material mappings collapse to one physical two-sided
 * sheet. Distinct front/back mappings, such as a sunflower disc, retain both authored materials
 * but accept each primitive only from its front side. In both cases authored winding remains the
 * normal authority.
 */
final class TwoSidedQuadReducer {
    private TwoSidedQuadReducer() {
    }

    static List<CapturedSectionGeometry.Quad> reduce(
            List<CapturedSectionGeometry.Quad> quads) {
        List<ResolvedQuad> resolved = resolve(quads);
        if (resolved.size() == quads.size()) {
            return quads;
        }
        ArrayList<CapturedSectionGeometry.Quad> result =
                new ArrayList<>(resolved.size());
        for (ResolvedQuad quad : resolved) {
            result.add(quad.quad());
        }
        return List.copyOf(result);
    }

    static List<ResolvedQuad> resolve(
            List<CapturedSectionGeometry.Quad> quads) {
        Objects.requireNonNull(quads, "quads");
        boolean[] removed = new boolean[quads.size()];
        Map<PositionSet, ArrayList<Integer>> pending = new HashMap<>();
        for (int index = 0; index < quads.size(); index++) {
            CapturedSectionGeometry.Quad quad =
                    Objects.requireNonNull(quads.get(index), "quad");
            if (!eligible(quad)) {
                continue;
            }
            PositionSet key = PositionSet.of(quad);
            ArrayList<Integer> candidates =
                    pending.computeIfAbsent(key, ignored -> new ArrayList<>());
            int match = -1;
            for (int candidate = candidates.size() - 1;
                    candidate >= 0;
                    candidate--) {
                if (formsRasterPair(quads.get(candidates.get(candidate)), quad)) {
                    match = candidate;
                    break;
                }
            }
            if (match < 0) {
                candidates.add(index);
                continue;
            }
            candidates.remove(match);
            removed[index] = true;
        }

        boolean[] frontFaceOnly = new boolean[quads.size()];
        pending.clear();
        for (int index = 0; index < quads.size(); index++) {
            if (removed[index]) {
                continue;
            }
            CapturedSectionGeometry.Quad quad = quads.get(index);
            if (!directionalEligible(quad)) {
                continue;
            }
            PositionSet key = PositionSet.of(quad);
            ArrayList<Integer> candidates =
                    pending.computeIfAbsent(key, ignored -> new ArrayList<>());
            int match = -1;
            for (int candidate = candidates.size() - 1;
                    candidate >= 0;
                    candidate--) {
                if (formsDirectionalPair(
                        quads.get(candidates.get(candidate)), quad)) {
                    match = candidate;
                    break;
                }
            }
            if (match < 0) {
                candidates.add(index);
                continue;
            }
            int pairedIndex = candidates.remove(match);
            frontFaceOnly[pairedIndex] = true;
            frontFaceOnly[index] = true;
        }

        ArrayList<ResolvedQuad> result =
                new ArrayList<>(quads.size());
        for (int index = 0; index < quads.size(); index++) {
            if (!removed[index]) {
                result.add(new ResolvedQuad(
                        quads.get(index), frontFaceOnly[index]));
            }
        }
        return List.copyOf(result);
    }

    private static boolean eligible(CapturedSectionGeometry.Quad quad) {
        CapturedSectionGeometry.Surface surface = quad.surface();
        return surface.fluid() != null || ClusterSceneTranslator.isCutout(surface);
    }

    private static boolean directionalEligible(
            CapturedSectionGeometry.Quad quad) {
        CapturedSectionGeometry.Surface surface = quad.surface();
        return surface.fluid() == null
                && surface.lightEmission() == 0
                && ClusterSceneTranslator.isCutout(surface);
    }

    private static boolean formsRasterPair(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        int reverseOffset = reverseOffset(first, second);
        if (reverseOffset < 0
                || !opposedNormals(first, second)
                || !sameSurface(first.surface(), second.surface())
                || !sameUvCorners(first, second)) {
            return false;
        }
        for (int vertex = 0; vertex < 4; vertex++) {
            int secondVertex = reverseIndex(reverseOffset, vertex);
            if (first.surface().color(vertex)
                    != second.surface().color(secondVertex)) {
                return false;
            }
        }
        return true;
    }

    private static boolean formsDirectionalPair(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        return reverseOffset(first, second) >= 0
                && opposedNormals(first, second)
                && sameDirectionalSemantics(first.surface(), second.surface());
    }

    private static int reverseOffset(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        for (int offset = 0; offset < 4; offset++) {
            boolean matches = true;
            for (int vertex = 0; vertex < 4; vertex++) {
                if (!samePosition(
                        first,
                        vertex,
                        second,
                        reverseIndex(offset, vertex))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return offset;
            }
        }
        return -1;
    }

    private static int reverseIndex(int offset, int vertex) {
        return offset - vertex & 3;
    }

    private static boolean opposedNormals(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        float firstLengthSquared =
                first.normalX() * first.normalX()
                        + first.normalY() * first.normalY()
                        + first.normalZ() * first.normalZ();
        float secondLengthSquared =
                second.normalX() * second.normalX()
                        + second.normalY() * second.normalY()
                        + second.normalZ() * second.normalZ();
        float dot = first.normalX() * second.normalX()
                + first.normalY() * second.normalY()
                + first.normalZ() * second.normalZ();
        return firstLengthSquared > 1.0e-20F
                && secondLengthSquared > 1.0e-20F
                && dot < 0.0F
                && dot * dot >= 0.999F * firstLengthSquared * secondLengthSquared;
    }

    private static boolean sameSurface(
            CapturedSectionGeometry.Surface first,
            CapturedSectionGeometry.Surface second) {
        return first.layer() == second.layer()
                && first.alphaCutOverride() == second.alphaCutOverride()
                && first.collisionEmpty() == second.collisionEmpty()
                && first.animated() == second.animated()
                && first.water() == second.water()
                && first.foliage() == second.foliage()
                && first.mergeable() == second.mergeable()
                && first.rasterOverlay() == second.rasterOverlay()
                && first.lightEmission() == second.lightEmission()
                && first.sprite() == second.sprite()
                && Objects.equals(first.fluid(), second.fluid());
    }

    private static boolean sameDirectionalSemantics(
            CapturedSectionGeometry.Surface first,
            CapturedSectionGeometry.Surface second) {
        return first.layer() == second.layer()
                && first.alphaCutOverride() == second.alphaCutOverride()
                && first.collisionEmpty() == second.collisionEmpty()
                && first.animated() == second.animated()
                && first.water() == second.water()
                && first.foliage() == second.foliage()
                && first.mergeable() == second.mergeable()
                && first.rasterOverlay() == second.rasterOverlay()
                && first.lightEmission() == 0
                && second.lightEmission() == 0
                && first.fluid() == null
                && second.fluid() == null;
    }

    private static boolean sameUvCorners(
            CapturedSectionGeometry.Quad first,
            CapturedSectionGeometry.Quad second) {
        boolean[] matched = new boolean[4];
        for (int firstVertex = 0; firstVertex < 4; firstVertex++) {
            int match = -1;
            for (int secondVertex = 0; secondVertex < 4; secondVertex++) {
                if (!matched[secondVertex]
                        && sameFloat(first.u(firstVertex), second.u(secondVertex))
                        && sameFloat(first.v(firstVertex), second.v(secondVertex))) {
                    match = secondVertex;
                    break;
                }
            }
            if (match < 0) {
                return false;
            }
            matched[match] = true;
        }
        return true;
    }

    private static boolean samePosition(
            CapturedSectionGeometry.Quad first,
            int firstVertex,
            CapturedSectionGeometry.Quad second,
            int secondVertex) {
        return sameFloat(first.x(firstVertex), second.x(secondVertex))
                && sameFloat(first.y(firstVertex), second.y(secondVertex))
                && sameFloat(first.z(firstVertex), second.z(secondVertex));
    }

    private static boolean sameFloat(float first, float second) {
        return first == second;
    }

    record ResolvedQuad(
            CapturedSectionGeometry.Quad quad, boolean frontFaceOnly) {
        ResolvedQuad {
            Objects.requireNonNull(quad, "quad");
        }
    }

    private record Position(int x, int y, int z) implements Comparable<Position> {
        static Position of(CapturedSectionGeometry.Quad quad, int vertex) {
            return new Position(
                    bits(quad.x(vertex)),
                    bits(quad.y(vertex)),
                    bits(quad.z(vertex)));
        }

        private static int bits(float value) {
            return Float.floatToIntBits(value == 0.0F ? 0.0F : value);
        }

        @Override
        public int compareTo(Position other) {
            int result = Integer.compare(this.x, other.x);
            if (result == 0) {
                result = Integer.compare(this.y, other.y);
            }
            return result == 0 ? Integer.compare(this.z, other.z) : result;
        }
    }

    private record PositionSet(List<Position> positions) {
        static PositionSet of(CapturedSectionGeometry.Quad quad) {
            Position[] positions = new Position[4];
            for (int vertex = 0; vertex < 4; vertex++) {
                positions[vertex] = Position.of(quad, vertex);
            }
            Arrays.sort(positions);
            return new PositionSet(List.of(positions));
        }
    }
}
