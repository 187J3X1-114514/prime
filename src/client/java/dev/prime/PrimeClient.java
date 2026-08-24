package dev.prime;

import dev.prime.config.PrimeConfig;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.client.PrimeRuntime;
import dev.prime.render.runtime.RendererLifecycle;
import dev.prime.render.scene.vanilla.ItemFrameModelFallback;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.client.Minecraft;
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
        PrimeRuntime.instance().initialize(PrimeConfig.rendererSettings());
        ResourceLoader resourceLoader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        resourceLoader.registerReloadListener(RELOAD_LISTENER_ID, new PreparableReloadListener() {
            private boolean initialReload = true;

            @Override
            public CompletableFuture<Void> reload(
                    SharedState state,
                    Executor preparationExecutor,
                    PreparationBarrier preparationBarrier,
                    Executor applyExecutor) {
                PrimeRuntime runtime = PrimeRuntime.instance();
                AtomicReference<RendererLifecycle.ResourceReload> reload =
                        new AtomicReference<>();
                CompletableFuture<RendererLifecycle.ResourceReload> retired =
                        CompletableFuture.supplyAsync(() -> {
                            RendererLifecycle.ResourceReload ticket =
                                    runtime.beginResourceReload();
                            reload.set(ticket);
                            return ticket;
                        }, preparationExecutor);
                CompletableFuture<Void> applied = retired
                        .thenCompose(ticket -> ticket.ready().thenApply(ignored -> ticket))
                        .thenCompose(preparationBarrier::wait)
                        .thenComposeAsync(ticket -> CompletableFuture.runAsync(() -> {
                            boolean reloadShaders = !this.initialReload;
                            runtime.finishResourceReload(ticket, reloadShaders);
                            this.initialReload = false;
                        }, Minecraft.getInstance()), applyExecutor);
                return applied.whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        return;
                    }
                    RendererLifecycle.ResourceReload ticket = reload.get();
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
