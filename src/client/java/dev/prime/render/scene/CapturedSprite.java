package dev.prime.render.scene;

import java.util.Arrays;
import java.util.Objects;

/** Immutable sprite identity, atlas layout and animation facts captured at the vanilla boundary. */
public final class CapturedSprite {
    private final SpriteId id;
    private final float u0;
    private final float v0;
    private final float u1;
    private final float v1;
    private final int frameWidth;
    private final int frameHeight;
    private final boolean animated;
    private final int[] uniqueFrames;
    private final SpritePixelView pixelView;

    public CapturedSprite(
            SpriteId id,
            float u0,
            float v0,
            float u1,
            float v1,
            int frameWidth,
            int frameHeight,
            boolean animated,
            int[] uniqueFrames,
            SpritePixelView pixelView) {
        this.id = Objects.requireNonNull(id, "id");
        if (!Float.isFinite(u0)
                || !Float.isFinite(v0)
                || !Float.isFinite(u1)
                || !Float.isFinite(v1)) {
            throw new IllegalArgumentException("Captured sprite atlas coordinates must be finite");
        }
        if (frameWidth <= 0 || frameHeight <= 0) {
            throw new IllegalArgumentException("Captured sprite frame dimensions must be positive");
        }
        Objects.requireNonNull(uniqueFrames, "uniqueFrames");
        if (uniqueFrames.length == 0) {
            throw new IllegalArgumentException("Captured sprite must contain at least one frame");
        }
        for (int index = 0; index < uniqueFrames.length; index++) {
            int frame = uniqueFrames[index];
            if (frame < 0) {
                throw new IllegalArgumentException(
                        "Captured sprite frame sequence must be nonnegative and unique");
            }
            for (int previous = 0; previous < index; previous++) {
                if (uniqueFrames[previous] == frame) {
                    throw new IllegalArgumentException(
                            "Captured sprite frame sequence must be nonnegative and unique");
                }
            }
        }
        this.u0 = u0;
        this.v0 = v0;
        this.u1 = u1;
        this.v1 = v1;
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.animated = animated;
        this.uniqueFrames = uniqueFrames.clone();
        this.pixelView = pixelView;
    }

    public SpriteId id() {
        return this.id;
    }

    public float u0() {
        return this.u0;
    }

    public float v0() {
        return this.v0;
    }

    public float u1() {
        return this.u1;
    }

    public float v1() {
        return this.v1;
    }

    public int frameWidth() {
        return this.frameWidth;
    }

    public int frameHeight() {
        return this.frameHeight;
    }

    public boolean animated() {
        return this.animated;
    }

    public int uniqueFrameCount() {
        return this.uniqueFrames.length;
    }

    public int uniqueFrame(int index) {
        return this.uniqueFrames[index];
    }

    /** Returns null when the resource boundary could not safely expose base pixels. */
    public SpritePixelView pixelView() {
        return this.pixelView;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CapturedSprite sprite)) {
            return false;
        }
        return this.id.equals(sprite.id)
                && Float.floatToIntBits(this.u0) == Float.floatToIntBits(sprite.u0)
                && Float.floatToIntBits(this.v0) == Float.floatToIntBits(sprite.v0)
                && Float.floatToIntBits(this.u1) == Float.floatToIntBits(sprite.u1)
                && Float.floatToIntBits(this.v1) == Float.floatToIntBits(sprite.v1)
                && this.frameWidth == sprite.frameWidth
                && this.frameHeight == sprite.frameHeight
                && this.animated == sprite.animated
                && Arrays.equals(this.uniqueFrames, sprite.uniqueFrames);
    }

    @Override
    public int hashCode() {
        int result = this.id.hashCode();
        result = 31 * result + Float.floatToIntBits(this.u0);
        result = 31 * result + Float.floatToIntBits(this.v0);
        result = 31 * result + Float.floatToIntBits(this.u1);
        result = 31 * result + Float.floatToIntBits(this.v1);
        result = 31 * result + this.frameWidth;
        result = 31 * result + this.frameHeight;
        result = 31 * result + Boolean.hashCode(this.animated);
        return 31 * result + Arrays.hashCode(this.uniqueFrames);
    }
}
