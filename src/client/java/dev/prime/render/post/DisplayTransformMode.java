package dev.prime.render.post;

import dev.prime.render.StableIds;
import java.util.Optional;

/** Selects the display rendering transform applied after reconstruction. */
public enum DisplayTransformMode {
    OKLAB("oklab", 0),
    AGX_HSV("agx_hsv", 1);

    public static final DisplayTransformMode DEFAULT = OKLAB;

    private final String id;
    private final int shaderId;

    DisplayTransformMode(String id, int shaderId) {
        this.id = id;
        this.shaderId = shaderId;
    }

    public String id() {
        return this.id;
    }

    public int shaderId() {
        return this.shaderId;
    }

    public static Optional<DisplayTransformMode> findById(String id) {
        return StableIds.find(values(), id, DisplayTransformMode::id);
    }

    public static DisplayTransformMode fromId(String id) {
        return findById(id).orElse(DEFAULT);
    }
}
