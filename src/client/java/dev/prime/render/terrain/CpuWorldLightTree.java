package dev.prime.render.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Rebuildable top level over the immutable Section light trees. */
final class CpuWorldLightTree {
    private CpuWorldLightTree() {
    }

    static Result build(List<GpuSection> sections, int originX, int originY, int originZ) {
        List<CpuLightTree.Leaf> leaves = new ArrayList<>();
        for (int index = 0; index < sections.size(); index++) {
            GpuSection section = sections.get(index);
            if (section.lights().isEmpty()) {
                continue;
            }
            float translateX = (section.sectionX() << 4) - originX;
            float translateY = (section.sectionY() << 4) - originY;
            float translateZ = (section.sectionZ() << 4) - originZ;
            CpuLightTree.Bounds bounds = section.lights().bounds().translated(
                    translateX, translateY, translateZ);
            leaves.add(new CpuLightTree.Leaf(
                    bounds,
                    (bounds.minX() + bounds.maxX()) * 0.5F,
                    (bounds.minY() + bounds.maxY()) * 0.5F,
                    (bounds.minZ() + bounds.maxZ()) * 0.5F,
                    section.lights().power(),
                    index));
        }
        if (leaves.isEmpty()) {
            int[] leafNodes = new int[sections.size()];
            Arrays.fill(leafNodes, CpuLightTree.NO_INDEX);
            return new Result(new int[0], leafNodes);
        }
        CpuLightTree.Result tree = CpuLightTree.build(
                leaves, sections.size(), CpuLightTree.WORLD_SOFTENING_SCALE);
        int[] leafNodes = new int[sections.size()];
        for (int index = 0; index < leafNodes.length; index++) {
            leafNodes[index] = tree.leafNode(index);
        }
        return new Result(tree.packNodes(), leafNodes);
    }

    record Result(int[] nodeWords, int[] leafNodes) {
        boolean isEmpty() {
            return this.nodeWords.length == 0;
        }

        int leafNode(int sectionIndex) {
            return this.leafNodes[sectionIndex];
        }
    }
}
