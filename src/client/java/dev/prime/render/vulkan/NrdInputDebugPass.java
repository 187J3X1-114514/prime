package dev.prime.render.vulkan;

import com.mojang.blaze3d.vulkan.Destroyable;
import dev.prime.render.ResourceCleanup;
import dev.prime.render.diagnostic.NrdInputView;
import dev.prime.render.vulkan.nrd.PreparedNrdFrame;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Diagnostic-only visualizer over the exact prepared inputs submitted to NRD. */
final class NrdInputDebugPass implements Destroyable {
    private static final int OUTPUT = 0;
    private static final int PRIMARY_MOTION = 1;
    private static final int PRIMARY_NORMAL_ROUGHNESS = 2;
    private static final int PRIMARY_VIEW_Z = 3;
    private static final int PRIMARY_DIFFUSE = 4;
    private static final int PRIMARY_SPECULAR = 5;
    private static final int PRIMARY_DIFFUSE_SH1 = 6;
    private static final int PRIMARY_SPECULAR_SH1 = 7;
    private static final int REFLECTION_MOTION = 8;
    private static final int REFLECTION_NORMAL_ROUGHNESS = 9;
    private static final int REFLECTION_VIEW_Z = 10;
    private static final int REFLECTION_DIFFUSE = 11;
    private static final int REFLECTION_SPECULAR = 12;
    private static final int REFLECTION_DIFFUSE_SH1 = 13;
    private static final int REFLECTION_SPECULAR_SH1 = 14;
    private static final int SUN_PENUMBRA = 15;

    private final ImageDiagnosticPass sdr;
    private final ImageDiagnosticPass hdr;

    private NrdInputDebugPass(ImageDiagnosticPass sdr, ImageDiagnosticPass hdr) {
        this.sdr = sdr;
        this.hdr = hdr;
    }

    static NrdInputDebugPass create(
            VulkanContext context,
            VulkanImage sceneColor,
            PreparedNrdFrame prepared,
            VulkanImage displayOutput,
            VulkanImage hdrOutput) {
        PreparedNrdFrame.Branch primary = prepared.primary();
        PreparedNrdFrame.Branch reflection = prepared.reflection();
        VulkanImage[] sources = {
            sceneColor,
            primary.motion(),
            primary.normalRoughness(),
            primary.viewZ(),
            primary.noisyDiffuse(),
            primary.noisySpecular(),
            primary.noisyDiffuseSh1(),
            primary.noisySpecularSh1(),
            reflection.motion(),
            reflection.normalRoughness(),
            reflection.viewZ(),
            reflection.noisyDiffuse(),
            reflection.noisySpecular(),
            reflection.noisyDiffuseSh1(),
            reflection.noisySpecularSh1(),
            prepared.sunPenumbra()
        };
        ImageDiagnosticPass sdr = null;
        ImageDiagnosticPass hdr = null;
        try {
            sdr = ImageDiagnosticPass.createSdr(context, displayOutput, sources);
            hdr = ImageDiagnosticPass.createHdr(context, hdrOutput, sources);
            return new NrdInputDebugPass(sdr, hdr);
        } catch (RuntimeException exception) {
            ResourceCleanup.destroy(hdr, exception);
            ResourceCleanup.destroy(sdr, exception);
            throw exception;
        }
    }

    void record(VkCommandBuffer commandBuffer, NrdInputView view) {
        if (view == NrdInputView.OFF) return;
        if (view == NrdInputView.GRID) {
            List<ImageDiagnosticPass.View> views = List.of(
                    descriptor(NrdInputView.DENOISED_OUTPUT),
                    descriptor(NrdInputView.PRIMARY_MOTION),
                    descriptor(NrdInputView.PRIMARY_NORMAL),
                    descriptor(NrdInputView.PRIMARY_ROUGHNESS),
                    descriptor(NrdInputView.PRIMARY_VIEW_Z),
                    descriptor(NrdInputView.PRIMARY_DIFFUSE_RADIANCE),
                    descriptor(NrdInputView.PRIMARY_DIFFUSE_HIT_DISTANCE),
                    descriptor(NrdInputView.PRIMARY_SPECULAR_RADIANCE),
                    descriptor(NrdInputView.PRIMARY_SPECULAR_HIT_DISTANCE),
                    descriptor(NrdInputView.PRIMARY_DIFFUSE_SH1),
                    descriptor(NrdInputView.PRIMARY_SPECULAR_SH1),
                    descriptor(NrdInputView.REFLECTION_MOTION),
                    descriptor(NrdInputView.REFLECTION_NORMAL),
                    descriptor(NrdInputView.REFLECTION_ROUGHNESS),
                    descriptor(NrdInputView.REFLECTION_VIEW_Z),
                    descriptor(NrdInputView.REFLECTION_DIFFUSE_RADIANCE),
                    descriptor(NrdInputView.REFLECTION_DIFFUSE_HIT_DISTANCE),
                    descriptor(NrdInputView.REFLECTION_SPECULAR_RADIANCE),
                    descriptor(NrdInputView.REFLECTION_SPECULAR_HIT_DISTANCE),
                    descriptor(NrdInputView.REFLECTION_DIFFUSE_SH1),
                    descriptor(NrdInputView.REFLECTION_SPECULAR_SH1),
                    descriptor(NrdInputView.SUN_PENUMBRA));
            this.sdr.recordGrid(commandBuffer, 5, views);
            this.hdr.recordGrid(commandBuffer, 5, views);
            return;
        }
        ImageDiagnosticPass.View descriptor = descriptor(view);
        this.sdr.recordFull(commandBuffer, descriptor);
        this.hdr.recordFull(commandBuffer, descriptor);
    }

    private static ImageDiagnosticPass.View descriptor(NrdInputView view) {
        return switch (view) {
            case DENOISED_OUTPUT -> view(OUTPUT, ImageDiagnosticPass.RADIANCE);
            case PRIMARY_MOTION -> view(PRIMARY_MOTION, ImageDiagnosticPass.MOTION);
            case PRIMARY_NORMAL -> view(PRIMARY_NORMAL_ROUGHNESS, ImageDiagnosticPass.PACKED_NORMAL);
            case PRIMARY_ROUGHNESS -> view(PRIMARY_NORMAL_ROUGHNESS, ImageDiagnosticPass.PACKED_ROUGHNESS);
            case PRIMARY_VIEW_Z -> view(PRIMARY_VIEW_Z, ImageDiagnosticPass.DEPTH);
            case PRIMARY_DIFFUSE_RADIANCE -> view(PRIMARY_DIFFUSE, ImageDiagnosticPass.RADIANCE);
            case PRIMARY_DIFFUSE_HIT_DISTANCE -> view(PRIMARY_DIFFUSE, ImageDiagnosticPass.HIT_A);
            case PRIMARY_SPECULAR_RADIANCE -> view(PRIMARY_SPECULAR, ImageDiagnosticPass.RADIANCE);
            case PRIMARY_SPECULAR_HIT_DISTANCE -> view(PRIMARY_SPECULAR, ImageDiagnosticPass.HIT_A);
            case PRIMARY_DIFFUSE_SH1 -> view(PRIMARY_DIFFUSE_SH1, ImageDiagnosticPass.SH1);
            case PRIMARY_SPECULAR_SH1 -> view(PRIMARY_SPECULAR_SH1, ImageDiagnosticPass.SH1);
            case REFLECTION_MOTION -> view(REFLECTION_MOTION, ImageDiagnosticPass.MOTION);
            case REFLECTION_NORMAL -> view(REFLECTION_NORMAL_ROUGHNESS, ImageDiagnosticPass.PACKED_NORMAL);
            case REFLECTION_ROUGHNESS -> view(REFLECTION_NORMAL_ROUGHNESS, ImageDiagnosticPass.PACKED_ROUGHNESS);
            case REFLECTION_VIEW_Z -> view(REFLECTION_VIEW_Z, ImageDiagnosticPass.DEPTH);
            case REFLECTION_DIFFUSE_RADIANCE -> view(REFLECTION_DIFFUSE, ImageDiagnosticPass.RADIANCE);
            case REFLECTION_DIFFUSE_HIT_DISTANCE -> view(REFLECTION_DIFFUSE, ImageDiagnosticPass.HIT_A);
            case REFLECTION_SPECULAR_RADIANCE -> view(REFLECTION_SPECULAR, ImageDiagnosticPass.RADIANCE);
            case REFLECTION_SPECULAR_HIT_DISTANCE -> view(REFLECTION_SPECULAR, ImageDiagnosticPass.HIT_A);
            case REFLECTION_DIFFUSE_SH1 -> view(REFLECTION_DIFFUSE_SH1, ImageDiagnosticPass.SH1);
            case REFLECTION_SPECULAR_SH1 -> view(REFLECTION_SPECULAR_SH1, ImageDiagnosticPass.SH1);
            case SUN_PENUMBRA -> view(SUN_PENUMBRA, ImageDiagnosticPass.HIT_R);
            case OFF, GRID -> throw new IllegalArgumentException("NRD view has no single image");
        };
    }

    private static ImageDiagnosticPass.View view(int source, int presentation) {
        return new ImageDiagnosticPass.View(source, presentation);
    }

    @Override
    public void destroy() {
        RuntimeException failure = ResourceCleanup.destroy(this.hdr, null);
        failure = ResourceCleanup.destroy(this.sdr, failure);
        ResourceCleanup.throwIfFailed(failure);
    }
}
