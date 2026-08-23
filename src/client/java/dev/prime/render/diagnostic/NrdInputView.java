package dev.prime.render.diagnostic;

import dev.prime.render.StableIds;
import java.util.Optional;

/** Semantic projections of the exact primary and reflection resources submitted to NRD. */
public enum NrdInputView implements ImageDiagnosticView {
    OFF("off"),
    DENOISED_OUTPUT("denoised_output"),
    PRIMARY_MOTION("primary_motion"),
    PRIMARY_NORMAL("primary_normal"),
    PRIMARY_ROUGHNESS("primary_roughness"),
    PRIMARY_VIEW_Z("primary_view_z"),
    PRIMARY_DIFFUSE_RADIANCE("primary_diffuse_radiance"),
    PRIMARY_DIFFUSE_HIT_DISTANCE("primary_diffuse_hit_distance"),
    PRIMARY_SPECULAR_RADIANCE("primary_specular_radiance"),
    PRIMARY_SPECULAR_HIT_DISTANCE("primary_specular_hit_distance"),
    PRIMARY_DIFFUSE_SH1("primary_diffuse_sh1"),
    PRIMARY_SPECULAR_SH1("primary_specular_sh1"),
    REFLECTION_MOTION("reflection_motion"),
    REFLECTION_NORMAL("reflection_normal"),
    REFLECTION_ROUGHNESS("reflection_roughness"),
    REFLECTION_VIEW_Z("reflection_view_z"),
    REFLECTION_DIFFUSE_RADIANCE("reflection_diffuse_radiance"),
    REFLECTION_DIFFUSE_HIT_DISTANCE("reflection_diffuse_hit_distance"),
    REFLECTION_SPECULAR_RADIANCE("reflection_specular_radiance"),
    REFLECTION_SPECULAR_HIT_DISTANCE("reflection_specular_hit_distance"),
    REFLECTION_DIFFUSE_SH1("reflection_diffuse_sh1"),
    REFLECTION_SPECULAR_SH1("reflection_specular_sh1"),
    SUN_PENUMBRA("sun_penumbra"),
    GRID("grid");

    private final String id;

    NrdInputView(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return this.id;
    }

    public static Optional<NrdInputView> findById(String id) {
        return StableIds.find(values(), id, NrdInputView::id);
    }

    public static NrdInputView fromId(String id) {
        return findById(id).orElse(OFF);
    }
}
