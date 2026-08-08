package dev.prime.render.fsr;

import dev.prime.render.StableIds;
import java.util.Optional;

/** Developer-facing FSR visualization modes exposed through Minecraft's video settings. */
public enum FsrDebugView {
    OFF("off"),
    OVERVIEW("overview");

    private final String id;

    FsrDebugView(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static Optional<FsrDebugView> findById(String id) {
        return StableIds.find(values(), id, FsrDebugView::id);
    }

    public static FsrDebugView fromId(String id) {
        return findById(id).orElse(OFF);
    }
}
