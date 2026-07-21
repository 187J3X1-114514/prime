package dev.prime.render.post;

import java.util.Arrays;
import java.util.Optional;

/** Prime-owned visualizations of the exact resources submitted to DLSS Ray Reconstruction. */
public enum DlssRrDebugView {
    OFF("off"),
    OVERVIEW("overview"),
    INPUT_COLOR("input_color"),
    MOTION("motion"),
    DEPTH("depth"),
    JITTER("jitter"),
    NORMALS("normals"),
    ROUGHNESS("roughness"),
    DIFFUSE_ALBEDO("diffuse_albedo"),
    SPECULAR_ALBEDO("specular_albedo"),
    SPECULAR_HIT_DISTANCE("specular_hit_distance"),
    RR_OUTPUT("rr_output");

    private final String id;

    DlssRrDebugView(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
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
