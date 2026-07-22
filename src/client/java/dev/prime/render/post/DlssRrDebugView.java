package dev.prime.render.post;

import java.util.Arrays;
import java.util.Optional;

/** Prime-owned visualizations of the exact resources submitted to DLSS Ray Reconstruction. */
public enum DlssRrDebugView {
    OFF("off", 0),
    OVERVIEW("overview", 1),
    INPUT_COLOR("input_color", 2),
    COLOR_BEFORE_TRANSPARENCY("color_before_transparency", 3),
    TRANSPARENCY_DIFFERENCE("transparency_difference", 4),
    MOTION("motion", 5),
    DEPTH("depth", 6),
    JITTER("jitter", 7),
    NORMALS("normals", 8),
    ROUGHNESS("roughness", 9),
    DIFFUSE_ALBEDO("diffuse_albedo", 10),
    SPECULAR_ALBEDO("specular_albedo", 11),
    SPECULAR_HIT_DISTANCE("specular_hit_distance", 12),
    RR_OUTPUT("rr_output", 13);

    private final String id;
    private final int shaderId;

    DlssRrDebugView(String id, int shaderId) {
        this.id = id;
        this.shaderId = shaderId;
    }

    public String id() {
        return this.id;
    }

    public int shaderId() {
        return this.shaderId;
    }

    public DlssRrDebugView next() {
        DlssRrDebugView[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static Optional<DlssRrDebugView> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst();
    }

    public static DlssRrDebugView fromId(String id) {
        return findById(id).orElse(OFF);
    }
}
