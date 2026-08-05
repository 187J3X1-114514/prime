package dev.prime.render.vulkan;

/** Local ownership latch for one command buffer offered to Minecraft's open host submission. */
public final class MinecraftHostSubmission {
    private boolean accepted;

    /** Called only after {@code VulkanCommandEncoder.execute()} returns normally. */
    public void acceptedByMinecraftHostSubmission() {
        if (this.accepted) {
            throw new IllegalStateException(
                    "Command ownership was already accepted by Minecraft host submission");
        }
        this.accepted = true;
    }

    public boolean wasAcceptedByMinecraftHostSubmission() {
        return this.accepted;
    }
}
