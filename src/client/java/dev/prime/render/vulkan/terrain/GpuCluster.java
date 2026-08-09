package dev.prime.render.vulkan.terrain;

import dev.prime.render.ResourceCleanup;
import dev.prime.render.terrain.*;
import dev.prime.render.vulkan.PreparedBlas;
import dev.prime.render.vulkan.VulkanBuffer;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.lwjgl.vulkan.VkCommandBuffer;

record GpuCluster(
        long key,
        int clusterX,
        int clusterY,
        int clusterZ,
        PreparedBlas blas,
        List<PreparedBlas> voxelBlases,
        CpuVoxelInstances voxelInstances,
        long surfaceRelationAddress,
        VulkanBuffer lightBuffer,
        VulkanBuffer motionBuffer,
        CompiledClusterLights.Summary lights,
        boolean dynamic) {
    GpuCluster {
        voxelBlases = List.copyOf(voxelBlases);
        voxelInstances = Objects.requireNonNull(
                voxelInstances, "voxelInstances");
        lights = Objects.requireNonNull(lights, "lights");
        if (lightBuffer != null && motionBuffer != null
                || motionBuffer != null && !dynamic) {
            throw new IllegalArgumentException(
                    "Only a dynamic cluster may own previous-position storage");
        }
        if (voxelBlases.isEmpty() != (voxelInstances.count() == 0)) {
            throw new IllegalArgumentException(
                    "GPU voxel meshes and their instances must be present together");
        }
        for (int meshIndex : voxelInstances.meshIndices()) {
            if (meshIndex < 0 || meshIndex >= voxelBlases.size()) {
                throw new IllegalArgumentException(
                        "GPU voxel instance references an invalid BLAS");
            }
        }
    }

    GpuCluster(
            long key,
            int clusterX,
            int clusterY,
            int clusterZ,
            PreparedBlas blas,
            List<PreparedBlas> voxelBlases,
            CpuVoxelInstances voxelInstances,
            VulkanBuffer lightBuffer,
            VulkanBuffer motionBuffer,
            CompiledClusterLights.Summary lights,
            boolean dynamic) {
        this(
                key,
                clusterX,
                clusterY,
                clusterZ,
                blas,
                voxelBlases,
                voxelInstances,
                0L,
                lightBuffer,
                motionBuffer,
                lights,
                dynamic);
    }

    GpuCluster(
            long key,
            int clusterX,
            int clusterY,
            int clusterZ,
            PreparedBlas blas,
            VulkanBuffer lightBuffer,
            CompiledClusterLights.Summary lights) {
        this(
                key,
                clusterX,
                clusterY,
                clusterZ,
                blas,
                lightBuffer,
                lights,
                false);
    }

    GpuCluster(
            long key,
            int clusterX,
            int clusterY,
            int clusterZ,
            PreparedBlas blas,
            VulkanBuffer lightBuffer,
            CompiledClusterLights.Summary lights,
            boolean dynamic) {
        this(
                key,
                clusterX,
                clusterY,
                clusterZ,
                blas,
                List.of(),
                CpuVoxelInstances.EMPTY,
                0L,
                lightBuffer,
                null,
                lights,
                dynamic);
    }
    long lightAddress() {
        // Dynamic clusters have no emitters, so this section slot carries the previous-position
        // address consumed only after closest hit has identified a dynamic primitive.
        VulkanBuffer payload = this.lightBuffer != null
                ? this.lightBuffer
                : this.motionBuffer;
        return payload == null ? 0L : payload.deviceAddress();
    }

    PreparedBlas baseBlas() {
        if (this.blas != null) {
            return this.blas;
        }
        if (this.voxelBlases.isEmpty()) {
            throw new IllegalStateException(
                    "A resident GPU cluster must own at least one BLAS");
        }
        return this.voxelBlases.getFirst();
    }

    int tlasInstanceCount() {
        return Math.addExact(1, this.voxelInstances.count());
    }

    long uniqueBlasTriangleCount() {
        long count = this.blas == null ? 0L : triangleCount(this.blas);
        for (PreparedBlas voxelBlas : this.voxelBlases) {
            count = Math.addExact(count, triangleCount(voxelBlas));
        }
        return count;
    }

    long instancedTriangleCount() {
        long count = this.blas == null ? 0L : triangleCount(this.blas);
        for (int meshIndex : this.voxelInstances.meshIndices()) {
            count = Math.addExact(
                    count, triangleCount(this.voxelBlases.get(meshIndex)));
        }
        return count;
    }

    private static long triangleCount(PreparedBlas blas) {
        return Math.addExact(
                Math.addExact(
                        blas.opaqueTriangleCount(),
                        blas.cutoutTriangleCount()),
                blas.transmissiveTriangleCount());
    }

    void forEachBlas(Consumer<PreparedBlas> consumer) {
        if (this.blas != null) {
            consumer.accept(this.blas);
        }
        this.voxelBlases.forEach(consumer);
    }

    boolean hasOpacityMicromapBuild() {
        if (this.blas != null && this.blas.hasOpacityMicromapBuild()) {
            return true;
        }
        for (PreparedBlas voxelBlas : this.voxelBlases) {
            if (voxelBlas.hasOpacityMicromapBuild()) {
                return true;
            }
        }
        return false;
    }

    void recordOpacityMicromapBuild(VkCommandBuffer commandBuffer) {
        if (this.blas != null) {
            this.blas.recordOpacityMicromapBuild(commandBuffer);
        }
        for (PreparedBlas voxelBlas : this.voxelBlases) {
            voxelBlas.recordOpacityMicromapBuild(commandBuffer);
        }
    }

    void recordBuild(VkCommandBuffer commandBuffer) {
        if (this.blas != null) {
            this.blas.recordBuild(commandBuffer);
        }
        for (PreparedBlas voxelBlas : this.voxelBlases) {
            voxelBlas.recordBuild(commandBuffer);
        }
    }

    void submitted() {
        RuntimeException failure = null;
        if (this.blas != null) {
            failure = ResourceCleanup.run(this.blas::onBuildSubmitted, failure);
            failure = ResourceCleanup.run(this.blas::retireBuildResources, failure);
        }
        for (PreparedBlas voxelBlas : this.voxelBlases) {
            failure = ResourceCleanup.run(
                    voxelBlas::onBuildSubmitted, failure);
            failure = ResourceCleanup.run(
                    voxelBlas::retireBuildResources, failure);
        }
        ResourceCleanup.throwIfFailed(failure);
    }

    void destroy() {
        RuntimeException failure = null;
        if (this.blas != null) {
            failure = ResourceCleanup.run(
                    this.blas::destroyPersistentResources, failure);
        }
        for (PreparedBlas voxelBlas : this.voxelBlases) {
            failure = ResourceCleanup.run(
                    voxelBlas::destroyPersistentResources, failure);
        }
        failure = ResourceCleanup.destroy(this.lightBuffer, failure);
        failure = ResourceCleanup.destroy(this.motionBuffer, failure);
        ResourceCleanup.throwIfFailed(failure);
    }

    void destroyAllResources() {
        RuntimeException failure = null;
        if (this.blas != null) {
            failure = ResourceCleanup.run(
                    this.blas::destroyAllResources, failure);
        }
        for (PreparedBlas voxelBlas : this.voxelBlases) {
            failure = ResourceCleanup.run(
                    voxelBlas::destroyAllResources, failure);
        }
        failure = ResourceCleanup.destroy(this.lightBuffer, failure);
        failure = ResourceCleanup.destroy(this.motionBuffer, failure);
        ResourceCleanup.throwIfFailed(failure);
    }
}
