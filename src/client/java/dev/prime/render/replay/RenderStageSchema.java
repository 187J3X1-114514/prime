package dev.prime.render.replay;

import java.util.List;
import org.lwjgl.vulkan.VK12;

/** Stable signal order and image-format contract used by canonical GPU stage captures. */
public enum RenderStageSchema {
    RAW_WAVEFRONT(List.of(
            signal("primary.view_z", VK12.VK_FORMAT_R32_SFLOAT),
            signal("primary.position", VK12.VK_FORMAT_R32G32B32A32_SFLOAT),
            signal("primary.diffuse", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("primary.specular", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("primary.normal_roughness", VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32),
            signal("primary.material", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("primary.specular_material", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("primary.diffuse_direction", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("primary.specular_direction", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("reflection.position", VK12.VK_FORMAT_R32G32B32A32_SFLOAT),
            signal("reflection.diffuse", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("reflection.specular", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("reflection.normal_roughness", VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32),
            signal("reflection.material", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("reflection.specular_material", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("reflection.diffuse_direction", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("reflection.specular_direction", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("display.position", VK12.VK_FORMAT_R32G32B32A32_SFLOAT))),
    PREPARED_NRD(List.of(
            signal("primary.motion", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("primary.normal_roughness", VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32),
            signal("primary.view_z", VK12.VK_FORMAT_R32_SFLOAT),
            signal("primary.diffuse_sh0", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("primary.specular_sh0", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("primary.diffuse_sh1", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("primary.specular_sh1", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("reflection.motion", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("reflection.normal_roughness", VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32),
            signal("reflection.view_z", VK12.VK_FORMAT_R32_SFLOAT),
            signal("reflection.diffuse_sh0", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("reflection.specular_sh0", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("reflection.diffuse_sh1", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("reflection.specular_sh1", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("sun.penumbra", VK12.VK_FORMAT_R16_SFLOAT),
            signal("fsr.depth", VK12.VK_FORMAT_R32_SFLOAT),
            signal("fsr.motion", VK12.VK_FORMAT_R16G16B16A16_SFLOAT))),
    POST_NRD(List.of(
            signal("composite.color", VK12.VK_FORMAT_R16G16B16A16_SFLOAT),
            signal("fsr.reactive", VK12.VK_FORMAT_R8_UNORM),
            signal("fsr.transparency_composition", VK12.VK_FORMAT_R8_UNORM)));

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

    public int format(int signalIndex) {
        return this.layout.get(signalIndex).format();
    }

    private static Signal signal(String name, int format) {
        return new Signal(name, format);
    }

    private record Signal(String name, int format) {
    }
}
