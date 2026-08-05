package dev.prime.render;

import java.util.Objects;

/** Client-thread-owned latch for the settings snapshot shared by one application frame. */
final class RendererFrameSettings {
    private RendererSettings current;

    void beginFrame(RendererSettings settings) {
        this.current = Objects.requireNonNull(settings, "settings");
    }

    RendererSettings forCamera() {
        return this.current;
    }

    RendererSettings forRender() {
        return this.current;
    }

    void clear() {
        this.current = null;
    }
}
