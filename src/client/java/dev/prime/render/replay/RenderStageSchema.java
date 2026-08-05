package dev.prime.render.replay;

import java.util.List;

/** Stable signal order and image-format contract used by canonical GPU stage captures. */
public enum RenderStageSchema {
    RAW_WAVEFRONT(List.of(
            signal("primary.view_z", RenderPixelFormat.R32_FLOAT),
            signal("primary.position", RenderPixelFormat.RGBA32_FLOAT),
            signal("primary.diffuse", RenderPixelFormat.RGBA16_FLOAT),
            signal("primary.specular", RenderPixelFormat.RGBA16_FLOAT),
            signal("primary.normal_roughness", RenderPixelFormat.RGB10_A2_UNORM),
            signal("primary.material", RenderPixelFormat.RGBA16_FLOAT),
            signal("primary.specular_material", RenderPixelFormat.RGBA16_FLOAT),
            signal("primary.diffuse_direction", RenderPixelFormat.RGBA16_FLOAT),
            signal("primary.specular_direction", RenderPixelFormat.RGBA16_FLOAT),
            signal("reflection.position", RenderPixelFormat.RGBA32_FLOAT),
            signal("reflection.diffuse", RenderPixelFormat.RGBA16_FLOAT),
            signal("reflection.specular", RenderPixelFormat.RGBA16_FLOAT),
            signal("reflection.normal_roughness", RenderPixelFormat.RGB10_A2_UNORM),
            signal("reflection.material", RenderPixelFormat.RGBA16_FLOAT),
            signal("reflection.specular_material", RenderPixelFormat.RGBA16_FLOAT),
            signal("reflection.diffuse_direction", RenderPixelFormat.RGBA16_FLOAT),
            signal("reflection.specular_direction", RenderPixelFormat.RGBA16_FLOAT),
            signal("display.position", RenderPixelFormat.RGBA32_FLOAT))),
    PREPARED_NRD(List.of(
            signal("primary.motion", RenderPixelFormat.RGBA16_FLOAT),
            signal("primary.normal_roughness", RenderPixelFormat.RGB10_A2_UNORM),
            signal("primary.view_z", RenderPixelFormat.R32_FLOAT),
            signal("primary.diffuse_sh0", RenderPixelFormat.RGBA16_FLOAT),
            signal("primary.specular_sh0", RenderPixelFormat.RGBA16_FLOAT),
            signal("primary.diffuse_sh1", RenderPixelFormat.RGBA16_FLOAT),
            signal("primary.specular_sh1", RenderPixelFormat.RGBA16_FLOAT),
            signal("reflection.motion", RenderPixelFormat.RGBA16_FLOAT),
            signal("reflection.normal_roughness", RenderPixelFormat.RGB10_A2_UNORM),
            signal("reflection.view_z", RenderPixelFormat.R32_FLOAT),
            signal("reflection.diffuse_sh0", RenderPixelFormat.RGBA16_FLOAT),
            signal("reflection.specular_sh0", RenderPixelFormat.RGBA16_FLOAT),
            signal("reflection.diffuse_sh1", RenderPixelFormat.RGBA16_FLOAT),
            signal("reflection.specular_sh1", RenderPixelFormat.RGBA16_FLOAT),
            signal("sun.penumbra", RenderPixelFormat.R16_FLOAT),
            signal("fsr.depth", RenderPixelFormat.R32_FLOAT),
            signal("fsr.motion", RenderPixelFormat.RGBA16_FLOAT))),
    POST_NRD(List.of(
            signal("composite.color", RenderPixelFormat.RGBA16_FLOAT),
            signal("fsr.reactive", RenderPixelFormat.R8_UNORM),
            signal("fsr.transparency_composition", RenderPixelFormat.R8_UNORM)));

    private final List<Signal> layout;
    private final List<String> signals;

    RenderStageSchema(List<Signal> layout) {
        this.layout = List.copyOf(layout);
        this.signals = this.layout.stream().map(Signal::name).toList();
    }

    public List<String> signals() {
        return this.signals;
    }

    public int signalCount() {
        return this.layout.size();
    }

    public int signalIndex(String name) {
        int index = this.signals.indexOf(name);
        if (index < 0) {
            throw new IllegalArgumentException(
                    "Unknown " + name() + " signal " + name);
        }
        return index;
    }

    public RenderPixelFormat format(int signalIndex) {
        return this.layout.get(signalIndex).format();
    }

    private static Signal signal(String name, RenderPixelFormat format) {
        return new Signal(name, format);
    }

    private record Signal(String name, RenderPixelFormat format) {
    }
}
