package dev.prime.render.terrain;

import java.util.List;
import java.util.Objects;

/** Immutable Minecraft-independent LabPBR atlas source and current animation samples. */
public record LabPbrAtlasFrame(
        long sourceGeneration,
        Snapshot snapshot,
        List<AnimationSample> animations) {
    public LabPbrAtlasFrame {
        if (sourceGeneration < 0L) {
            throw new IllegalArgumentException("LabPBR source generation must be nonnegative");
        }
        Objects.requireNonNull(snapshot, "snapshot");
        animations = List.copyOf(animations);
        for (Sprite sprite : snapshot.sprites()) {
            if (sprite.animationIndex() >= animations.size()) {
                throw new IllegalArgumentException(
                        "LabPBR sprite animation index exceeds the captured samples");
            }
        }
    }

    public record Snapshot(
            int width,
            int height,
            int mipLevels,
            LabPbrMaterialSet materials,
            List<Sprite> sprites) {
        public Snapshot {
            if (width <= 0 || height <= 0 || mipLevels <= 0) {
                throw new IllegalArgumentException("LabPBR atlas extent and mip count must be positive");
            }
            Objects.requireNonNull(materials, "materials");
            sprites = List.copyOf(sprites);
        }
    }

    public record Sprite(
            int x,
            int y,
            int contentWidth,
            int contentHeight,
            int padding,
            MaterialSource normal,
            MaterialSource specular,
            int animationIndex) {
        public Sprite {
            if (x < 0 || y < 0 || contentWidth <= 0 || contentHeight <= 0 || padding < 0) {
                throw new IllegalArgumentException("Invalid LabPBR sprite placement");
            }
            if (animationIndex < -1) {
                throw new IllegalArgumentException("Invalid LabPBR animation index");
            }
            if (normal == null && specular == null) {
                throw new IllegalArgumentException("LabPBR sprite must contain an auxiliary map");
            }
        }

        public int mipX(int mip) {
            return this.x >> mip;
        }

        public int mipY(int mip) {
            return this.y >> mip;
        }

        public int mipWidth(int mip) {
            return Math.max(1, (this.contentWidth + 2 * this.padding) >> mip);
        }

        public int mipHeight(int mip) {
            return Math.max(1, (this.contentHeight + 2 * this.padding) >> mip);
        }

        public boolean animated() {
            return this.animationIndex >= 0;
        }
    }

    public record AnimationSample(
            int currentFrame,
            int nextFrame,
            int progressThousandths) {
        public static final AnimationSample ZERO = new AnimationSample(0, 0, 0);

        public AnimationSample {
            if (currentFrame < 0 || nextFrame < 0
                    || progressThousandths < 0 || progressThousandths > 999) {
                throw new IllegalArgumentException("Invalid LabPBR animation sample");
            }
        }
    }

    public record MaterialSource(
            int[] pixels,
            int width,
            int height,
            int frameWidth,
            int frameHeight,
            int columns,
            int frameCount) {
        public MaterialSource {
            Objects.requireNonNull(pixels, "pixels");
            if (width <= 0 || height <= 0 || frameWidth <= 0 || frameHeight <= 0
                    || columns <= 0 || frameCount <= 0
                    || pixels.length != Math.multiplyExact(width, height)) {
                throw new IllegalArgumentException("Invalid LabPBR material source layout");
            }
            pixels = pixels.clone();
        }

        @Override
        public int[] pixels() {
            return this.pixels.clone();
        }

        public static MaterialSource create(
                int[] pixels,
                int width,
                int height,
                int baseFrameWidth,
                int baseFrameHeight,
                int baseImageWidth,
                int baseImageHeight) {
            int baseColumns = Math.max(1, baseImageWidth / baseFrameWidth);
            int baseRows = Math.max(1, baseImageHeight / baseFrameHeight);
            int frameWidth;
            int frameHeight;
            int columns;
            int frameCount;
            if (width == baseFrameWidth && height == baseFrameHeight) {
                frameWidth = width;
                frameHeight = height;
                columns = 1;
                frameCount = 1;
            } else if (width % baseColumns == 0 && height % baseRows == 0) {
                frameWidth = width / baseColumns;
                frameHeight = height / baseRows;
                columns = baseColumns;
                frameCount = baseColumns * baseRows;
            } else {
                frameWidth = width;
                frameHeight = height;
                columns = 1;
                frameCount = 1;
            }
            return new MaterialSource(
                    pixels, width, height, frameWidth, frameHeight, columns, frameCount);
        }

        public int filtered(
                AnimationSample sample,
                double baseX0,
                double baseY0,
                double baseX1,
                double baseY1,
                int baseFrameWidth,
                int baseFrameHeight) {
            return this.filtered(
                    sample,
                    baseX0,
                    baseY0,
                    baseX1,
                    baseY1,
                    baseFrameWidth,
                    baseFrameHeight,
                    false);
        }

        public int filtered(
                AnimationSample sample,
                double baseX0,
                double baseY0,
                double baseX1,
                double baseY1,
                int baseFrameWidth,
                int baseFrameHeight,
                boolean specular) {
            int current = this.filteredFrame(
                    sample.currentFrame,
                    baseX0,
                    baseY0,
                    baseX1,
                    baseY1,
                    baseFrameWidth,
                    baseFrameHeight,
                    specular);
            int progress = this.frameCount == 1 ? 0 : sample.progressThousandths;
            if (progress <= 0 || sample.currentFrame == sample.nextFrame) {
                return current;
            }
            int next = this.filteredFrame(
                    sample.nextFrame,
                    baseX0,
                    baseY0,
                    baseX1,
                    baseY1,
                    baseFrameWidth,
                    baseFrameHeight,
                    specular);
            return blendArgb(current, next, progress, specular);
        }

        private int filteredFrame(
                int requestedFrame,
                double baseX0,
                double baseY0,
                double baseX1,
                double baseY1,
                int baseFrameWidth,
                int baseFrameHeight,
                boolean specular) {
            int frame = this.frameCount == 1
                    ? 0
                    : Math.max(0, Math.min(requestedFrame, this.frameCount - 1));
            int frameX = frame % this.columns * this.frameWidth;
            int frameY = frame / this.columns * this.frameHeight;
            int sourceX0 = clamp(
                    (int) Math.floor(baseX0 * this.frameWidth / baseFrameWidth),
                    0,
                    this.frameWidth - 1);
            int sourceY0 = clamp(
                    (int) Math.floor(baseY0 * this.frameHeight / baseFrameHeight),
                    0,
                    this.frameHeight - 1);
            int sourceX1 = clamp(
                    (int) Math.ceil(baseX1 * this.frameWidth / baseFrameWidth),
                    sourceX0 + 1,
                    this.frameWidth);
            int sourceY1 = clamp(
                    (int) Math.ceil(baseY1 * this.frameHeight / baseFrameHeight),
                    sourceY0 + 1,
                    this.frameHeight);
            long alpha = 0L;
            long red = 0L;
            long green = 0L;
            long blue = 0L;
            int count = 0;
            long emission = 0L;
            int sentinelCount = 0;
            for (int y = sourceY0; y < sourceY1; y++) {
                for (int x = sourceX0; x < sourceX1; x++) {
                    int pixel = this.pixels[(frameY + y) * this.width + frameX + x];
                    int encodedAlpha = pixel >>> 24;
                    alpha += encodedAlpha;
                    if (specular) {
                        if (encodedAlpha == 255) {
                            sentinelCount++;
                        } else {
                            emission += encodedAlpha;
                        }
                    }
                    red += pixel >>> 16 & 0xff;
                    green += pixel >>> 8 & 0xff;
                    blue += pixel & 0xff;
                    count++;
                }
            }
            int filteredAlpha = specular
                    ? (sentinelCount == count
                            ? 255
                            : (int) ((emission + count / 2L) / count))
                    : (int) ((alpha + count / 2L) / count);
            return filteredAlpha << 24
                    | (int) ((red + count / 2L) / count) << 16
                    | (int) ((green + count / 2L) / count) << 8
                    | (int) ((blue + count / 2L) / count);
        }

        private static int blendArgb(
                int current, int next, int progress, boolean specular) {
            int inverse = 1000 - progress;
            int currentAlpha = current >>> 24;
            int nextAlpha = next >>> 24;
            int alpha;
            if (specular) {
                if (currentAlpha == 255 && nextAlpha == 255) {
                    alpha = 255;
                } else {
                    int currentEmission = currentAlpha == 255 ? 0 : currentAlpha;
                    int nextEmission = nextAlpha == 255 ? 0 : nextAlpha;
                    alpha = (currentEmission * inverse + nextEmission * progress + 500) / 1000;
                }
            } else {
                alpha = (currentAlpha * inverse + nextAlpha * progress + 500) / 1000;
            }
            int red = ((current >>> 16 & 0xff) * inverse
                    + (next >>> 16 & 0xff) * progress + 500) / 1000;
            int green = ((current >>> 8 & 0xff) * inverse
                    + (next >>> 8 & 0xff) * progress + 500) / 1000;
            int blue = ((current & 0xff) * inverse + (next & 0xff) * progress + 500) / 1000;
            return alpha << 24 | red << 16 | green << 8 | blue;
        }

        private static int clamp(int value, int minimum, int maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }
    }
}
