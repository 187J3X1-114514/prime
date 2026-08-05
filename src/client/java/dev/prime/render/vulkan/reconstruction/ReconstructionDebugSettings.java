package dev.prime.render.vulkan.reconstruction;

import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.post.DlssRrDebugView;
import dev.prime.render.post.nrd.NrdDiagnostics;
import java.util.Objects;

/** Runtime-to-backend debug side channel excluded from semantic frame plans. */
public record ReconstructionDebugSettings(
        NrdDiagnostics.Mode nrd,
        FsrDebugView fsr,
        DlssRrDebugView dlssRr,
        boolean dlssRrFullscreen) {
    public ReconstructionDebugSettings {
        nrd = Objects.requireNonNull(nrd, "nrd");
        fsr = Objects.requireNonNull(fsr, "fsr");
        dlssRr = Objects.requireNonNull(dlssRr, "dlssRr");
    }
}
