package dev.prime.render.terrain;

import dev.prime.render.shader.ShaderAbi;
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
            return new Result(new int[0], new int[0], new int[0], leafNodes);
        }
        CpuLightTree.Result tree = CpuLightTree.build(
                leaves, sections.size(), CpuLightTree.WORLD_SOFTENING_SCALE);
        int[] leafNodes = new int[sections.size()];
        for (int index = 0; index < leafNodes.length; index++) {
            leafNodes[index] = tree.leafNode(index);
        }
        return new Result(
                tree.packNodeBounds(),
                tree.packNodeForward(),
                tree.packNodeReverse(),
                leafNodes);
    }

    record Result(int[] nodeWords, int[] forwardWords, int[] reverseWords, int[] leafNodes) {
        Result {
            int nodeWordsPerRecord = ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES;
            int forwardWordsPerRecord = ShaderAbi.LIGHT_NODE_FORWARD_SIZE / Integer.BYTES;
            int reverseWordsPerRecord = ShaderAbi.LIGHT_NODE_REVERSE_SIZE / Integer.BYTES;
            if (nodeWords.length % nodeWordsPerRecord != 0
                    || forwardWords.length % forwardWordsPerRecord != 0
                    || reverseWords.length % reverseWordsPerRecord != 0
                    || nodeWords.length / nodeWordsPerRecord
                            != forwardWords.length / forwardWordsPerRecord
                    || nodeWords.length / nodeWordsPerRecord
                            != reverseWords.length / reverseWordsPerRecord) {
                throw new IllegalArgumentException("World light node streams disagree");
            }
        }

        boolean isEmpty() {
            return this.nodeWords.length == 0;
        }

        int[] pack() {
            int[] result = Arrays.copyOf(
                    this.nodeWords,
                    this.nodeWords.length + this.forwardWords.length + this.reverseWords.length);
            System.arraycopy(
                    this.forwardWords,
                    0,
                    result,
                    this.nodeWords.length,
                    this.forwardWords.length);
            System.arraycopy(
                    this.reverseWords,
                    0,
                    result,
                    this.nodeWords.length + this.forwardWords.length,
                    this.reverseWords.length);
            return result;
        }

        long forwardByteOffset() {
            return (long) this.nodeWords.length * Integer.BYTES;
        }

        long reverseByteOffset() {
            return (long) (this.nodeWords.length + this.forwardWords.length) * Integer.BYTES;
        }

        int nodeCount() {
            return this.nodeWords.length / (ShaderAbi.LIGHT_NODE_SIZE / Integer.BYTES);
        }

        int leafNode(int sectionIndex) {
            return this.leafNodes[sectionIndex];
        }
    }
}
