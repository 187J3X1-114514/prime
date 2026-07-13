package dev.prime.render.terrain;

import dev.prime.render.vulkan.PreparedBlas;

record GpuSection(long key, int sectionX, int sectionY, int sectionZ, PreparedBlas blas) {
    void destroy() {
        this.blas.destroyPersistentResources();
    }
}
