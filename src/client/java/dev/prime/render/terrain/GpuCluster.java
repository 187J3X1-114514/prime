package dev.prime.render.terrain;

import dev.prime.render.vulkan.PreparedBlas;
import dev.prime.render.vulkan.VulkanBuffer;

record GpuCluster(
        long key,
        int clusterX,
        int clusterY,
        int clusterZ,
        PreparedBlas blas,
        VulkanBuffer lightBuffer,
        CpuSectionLights.Summary lights) {
    long lightAddress() {
        return this.lightBuffer == null ? 0L : this.lightBuffer.deviceAddress();
    }

    void destroy() {
        this.blas.destroyPersistentResources();
        if (this.lightBuffer != null) {
            this.lightBuffer.destroy();
        }
    }

    void destroyAllResources() {
        this.blas.destroyAllResources();
        if (this.lightBuffer != null) {
            this.lightBuffer.destroy();
        }
    }
}
