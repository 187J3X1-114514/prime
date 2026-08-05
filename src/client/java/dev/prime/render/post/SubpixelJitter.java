package dev.prime.render.post;

/** Centered source-pixel sample displacement shared by every reconstruction backend. */
public record SubpixelJitter(float x, float y) {
    public SubpixelJitter {
        if (!Float.isFinite(x)
                || !Float.isFinite(y)
                || Math.abs(x) > 0.5F
                || Math.abs(y) > 0.5F) {
            throw new IllegalArgumentException(
                    "Subpixel jitter must be finite and inside one source pixel");
        }
    }

    /** FSR consumes projection displacement, the inverse of Prime's sample displacement. */
    public SubpixelJitter forFsrDispatch() {
        return new SubpixelJitter(-this.x, -this.y);
    }
}
