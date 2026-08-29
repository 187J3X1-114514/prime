package dev.prime;

import dev.prime.binding.streamline.Layouts;
import dev.prime.binding.streamline.Preferences;
import dev.prime.binding.streamline.Streamline;
import dev.prime.config.PrimeConfig;
import dev.prime.infrastructure.PrimeInfo;
import dev.prime.client.PrimeRuntime;
import dev.prime.render.runtime.RendererLifecycle;
import dev.prime.render.scene.vanilla.ItemFrameModelFallback;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import dev.prime.render.vulkan.NativeLibraries;
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

    private static Streamline streamlineInstance;

    public static Path getStreamlineInterposerPath(){
        return NativeLibraries.extractBundled(
                "prime-streamline",
                "/prime/natives/windows-x86_64/sl.interposer.dll",
                "sl.interposer.dll",
                "Streamline interposer library"
        );
    }

    public static Path getStreamlineCommonPath(){
        return NativeLibraries.extractBundled(
                "prime-streamline",
                "/prime/natives/windows-x86_64/sl.common.dll",
                "sl.common.dll",
                "Streamline common library"
        );
    }

    public static void initializeStreamline(){
        /*
        Path interposerPath = getStreamlineInterposerPath();
        Path commonPath = getStreamlineCommonPath();
        streamlineInstance = Streamline.open(interposerPath);

        try (Arena arena = Arena.ofConfined()) {
            var preferences = Preferences.allocate(arena);
            streamlineInstance.init(preferences);
        }
        */
    }

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
