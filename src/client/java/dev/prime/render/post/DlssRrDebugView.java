package dev.prime.render.post;

import dev.prime.render.StableIds;
import java.util.Optional;

/** Prime-owned visualizations of the exact resources submitted to DLSS Ray Reconstruction. */
public enum DlssRrDebugView {
    OFF("off", 0),
    OVERVIEW("overview", 1),
    INPUT_COLOR("input_color", 2),
    MOTION("motion", 3),
    SPECULAR_MOTION("specular_motion", 4),
    DEPTH("depth", 5),
    JITTER("jitter", 6),
    NORMALS("normals", 7),
    ROUGHNESS("roughness", 8),
    DIFFUSE_ALBEDO("diffuse_albedo", 9),
    SPECULAR_ALBEDO("specular_albedo", 10),
    SPECULAR_HIT_DISTANCE("specular_hit_distance", 11),
    RR_OUTPUT("rr_output", 12),
    WAVEFRONT_OVERVIEW("wavefront_overview", 13),
    HANDOFF_OVERVIEW("handoff_overview", 14),
    GUIDE_RESOLVE_OVERVIEW("guide_resolve_overview", 15);

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
        return StableIds.find(values(), id, DlssRrDebugView::id);
    }

    public static DlssRrDebugView fromId(String id) {
        return findById(id).orElse(OFF);
    }
}
