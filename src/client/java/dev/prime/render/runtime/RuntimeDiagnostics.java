package dev.prime.render.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.vulkan.VulkanBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** Client boundary for runtime logs and availability toasts. */
public final class RuntimeDiagnostics {
    private static final SystemToast.SystemToastId UNAVAILABLE_TOAST =
            new SystemToast.SystemToastId(8_000L);

    private boolean notificationShown;
    private boolean unavailabilityLogged;

    public void resetAvailabilityNotifications() {
        this.notificationShown = false;
        this.unavailabilityLogged = false;
    }

    public String finalizeUnavailableReason(String failureReason, RuntimeState state) {
        if (this.unavailabilityLogged || state != RuntimeState.UNAVAILABLE) {
            return failureReason;
        }
        String resolved = failureReason;
        if (VulkanBootstrap.snapshot().device() == null) {
            String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
            resolved = "Minecraft is using " + backend
                    + "; select the Vulkan graphics backend";
        }
        this.unavailabilityLogged = true;
        PrimeInfo.LOGGER.warn("Prime will use vanilla rendering: {}", resolved);
        return resolved;
    }

    public void showFailureOnce(
            Minecraft minecraft, RuntimeState state, String failureReason) {
        if (this.notificationShown
                || state != RuntimeState.UNAVAILABLE && state != RuntimeState.FAILED
                || minecraft.gui == null) {
            return;
        }
        this.notificationShown = true;
        SystemToast.add(
                minecraft.gui.toastManager(),
                UNAVAILABLE_TOAST,
                Component.literal("Prime ray tracing unavailable"),
                Component.literal(failureReason));
    }

}
