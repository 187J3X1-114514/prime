package dev.prime.render.runtime;

import dev.prime.render.RendererSettings;
import java.util.Objects;

/** Client-thread-owned latch for the settings snapshot shared by one application frame. */
public final class RendererFrameSettings {
    private RendererSettings current;

    public void beginFrame(RendererSettings settings) {
        this.current = Objects.requireNonNull(settings, "settings");
    }

    public RendererSettings forCamera() {
        return this.current;
    }

    public RendererSettings forRender() {
        return this.current;
    }

    public void clear() {
        this.current = null;
    }
}
