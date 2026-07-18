package dev.prime;

import dev.prime.config.PrimeConfig;
import dev.prime.render.RayTracingRuntime;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PrimeClient implements ClientModInitializer {
    public static final String MOD_ID = "prime";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Identifier RELOAD_LISTENER_ID = Identifier.fromNamespaceAndPath(MOD_ID, "ray_tracing_resources");
    @Override
    public void onInitializeClient() {
        PrimeConfig.load();
        LOGGER.info("Initializing Prime ray tracing framework");
        RayTracingRuntime.instance().initialize();
        ResourceLoader resourceLoader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        resourceLoader.registerReloadListener(RELOAD_LISTENER_ID, new SimpleReloadListener<Boolean>() {
            @Override
            protected Boolean prepare(PreparableReloadListener.SharedState state) {
                // Mark the source generation before Minecraft swaps the atlas view. The render
                // thread can then consume one coherent version instead of rebuilding once for
                // the new view and a second time for a late boolean invalidation.
                RayTracingRuntime.instance().beginResourceReload();
                return Boolean.TRUE;
            }

            @Override
            protected void apply(Boolean prepared, PreparableReloadListener.SharedState state) {
                RayTracingRuntime.instance().finishResourceReload();
            }
        });
        resourceLoader.addListenerOrdering(ResourceReloaderKeys.Client.MODELS, RELOAD_LISTENER_ID);
        resourceLoader.addListenerOrdering(ResourceReloaderKeys.Client.SHADERS, RELOAD_LISTENER_ID);

    }
}
