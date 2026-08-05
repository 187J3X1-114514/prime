package dev.prime;

import dev.prime.config.PrimeConfig;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.render.RayTracingRuntime;
import dev.prime.render.scene.vanilla.ItemFrameModelFallback;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;

public final class PrimeClient implements ClientModInitializer {
    private static final Identifier RELOAD_LISTENER_ID = Identifier.fromNamespaceAndPath(
            PrimeInfo.MOD_ID, "ray_tracing_resources");
    @Override
    public void onInitializeClient() {
        PrimeConfig.load();
        PrimeInfo.LOGGER.info("Initializing Prime ray tracing framework");
        ItemFrameModelFallback.register();
        RayTracingRuntime.instance().initialize(PrimeConfig.rendererSettings());
        ResourceLoader resourceLoader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        resourceLoader.registerReloadListener(RELOAD_LISTENER_ID, new PreparableReloadListener() {
            private boolean initialReload = true;

            @Override
            public CompletableFuture<Void> reload(
                    SharedState state,
                    Executor preparationExecutor,
                    PreparationBarrier preparationBarrier,
                    Executor applyExecutor) {
                RayTracingRuntime runtime = RayTracingRuntime.instance();
                AtomicReference<RayTracingRuntime.ResourceReload> reload =
                        new AtomicReference<>();
                CompletableFuture<RayTracingRuntime.ResourceReload> retired =
                        CompletableFuture.supplyAsync(() -> {
                            RayTracingRuntime.ResourceReload ticket =
                                    runtime.beginResourceReload();
                            reload.set(ticket);
                            return ticket;
                        }, preparationExecutor);
                CompletableFuture<Void> applied = retired
                        .thenCompose(ticket -> ticket.ready().thenApply(ignored -> ticket))
                        .thenCompose(preparationBarrier::wait)
                        .thenAcceptAsync(ticket -> {
                            boolean reloadShaders = !this.initialReload;
                            runtime.finishResourceReload(ticket, reloadShaders);
                            this.initialReload = false;
                        }, applyExecutor);
                return applied.whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        return;
                    }
                    RayTracingRuntime.ResourceReload ticket = reload.get();
                    if (ticket == null) {
                        return;
                    }
                    try {
                        runtime.abortResourceReload(ticket);
                    } catch (RuntimeException abortFailure) {
                        failure.addSuppressed(abortFailure);
                    }
                });
            }
        });
        resourceLoader.addListenerOrdering(ResourceReloaderKeys.Client.MODELS, RELOAD_LISTENER_ID);
        resourceLoader.addListenerOrdering(ResourceReloaderKeys.Client.SHADERS, RELOAD_LISTENER_ID);

    }
}
