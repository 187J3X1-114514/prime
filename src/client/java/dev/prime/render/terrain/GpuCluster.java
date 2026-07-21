package dev.prime.render.terrain;

import dev.prime.render.ResourceCleanup;
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
        RuntimeException failure = ResourceCleanup.run(
                this.blas::destroyPersistentResources, null);
        failure = ResourceCleanup.destroy(this.lightBuffer, failure);
        ResourceCleanup.throwIfFailed(failure);
    }

    void destroyAllResources() {
        RuntimeException failure = ResourceCleanup.run(
                this.blas::destroyAllResources, null);
        failure = ResourceCleanup.destroy(this.lightBuffer, failure);
        ResourceCleanup.throwIfFailed(failure);
    }
}
