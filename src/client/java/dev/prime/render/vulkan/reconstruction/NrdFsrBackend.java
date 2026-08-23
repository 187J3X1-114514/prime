package dev.prime.render.vulkan.reconstruction;

import dev.prime.render.post.PostProcessingMode;
import dev.prime.render.post.ReconstructionExtent;
import dev.prime.render.post.ReconstructionQualityMode;
import dev.prime.render.post.SubpixelJitter;
import dev.prime.render.post.TransparentGuideMode;
import dev.prime.render.vulkan.NrdFsrPostProcessor;

final class NrdFsrBackend implements ReconstructionBackend {
    @Override
    public PostProcessingMode mode() {
        return PostProcessingMode.NRD_FSR;
    }

    @Override
    public Capability capability() {
        return Capability.supported();
    }

    @Override
    public ReconstructionExtent renderExtent(
            ReconstructionQualityMode quality, int displayWidth, int displayHeight) {
        return quality.renderExtent(displayWidth, displayHeight);
    }

    @Override
    public PostProcessingMode fallbackMode() {
        return null;
    }

    @Override
    public TransparentGuideMode transparentGuideMode() {
        return TransparentGuideMode.REFLECTION_AND_TRANSMISSION;
    }

    @Override
    public SubpixelJitter jitter(ReconstructionQualityMode quality, int frameIndex) {
        return quality.jitter(frameIndex);
    }

    @Override
    public int jitterPhase(ReconstructionQualityMode quality, int frameIndex) {
        return quality.jitterPhase(frameIndex);
    }

    @Override
    public String executionLabel() {
        return "Prime 1spp path tracing, NRD, and FidelityFX FSR 3.1.4";
    }

    @Override
    public VulkanReconstructionProcessor create(CreateInput input) {
        ResolvedReconstruction selection = input.selection();
        return NrdFsrPostProcessor.create(
                input.context(),
                input.atmosphere(),
                input.stableRadiance(),
                input.output(),
                selection.extent().width(),
                selection.extent().height(),
                input.output().width(),
                input.output().height(),
                selection.quality());
    }
}
