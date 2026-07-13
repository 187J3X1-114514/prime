package dev.prime.render.terrain;

import dev.prime.PrimeClient;
import java.util.List;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class TintSnapshot {
    private static final int BLOCK_COUNT = 16 * 16 * 16;
    private final int[][] colors = new int[BLOCK_COUNT][];

    private TintSnapshot() {
    }

    public static TintSnapshot capture(
            RenderSectionRegion region,
            BlockColors blockColors,
            int sectionX,
            int sectionY,
            int sectionZ) {
        TintSnapshot snapshot = new TintSnapshot();
        MutableBlockPos position = new MutableBlockPos();
        int originX = sectionX << 4;
        int originY = sectionY << 4;
        int originZ = sectionZ << 4;
        for (int localY = 0; localY < 16; localY++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    position.set(originX + localX, originY + localY, originZ + localZ);
                    BlockState state = region.getBlockState(position);
                    List<BlockTintSource> sources = blockColors.getTintSources(state);
                    if (sources.isEmpty()) {
                        continue;
                    }
                    int[] values = new int[sources.size()];
                    for (int layer = 0; layer < sources.size(); layer++) {
                        try {
                            values[layer] = sources.get(layer).colorInWorld(state, region, position);
                        } catch (RuntimeException exception) {
                            PrimeClient.LOGGER.debug("Unable to snapshot block tint at {}", position, exception);
                            values[layer] = -1;
                        }
                    }
                    snapshot.colors[index(localX, localY, localZ)] = values;
                }
            }
        }
        return snapshot;
    }

    public int color(int localX, int localY, int localZ, int tintIndex) {
        int[] values = this.colors[index(localX, localY, localZ)];
        if (values == null || tintIndex < 0 || tintIndex >= values.length) {
            return -1;
        }
        return values[tintIndex];
    }

    private static int index(int x, int y, int z) {
        return y << 8 | z << 4 | x;
    }
}
