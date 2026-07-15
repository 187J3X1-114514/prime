package dev.prime;

import dev.prime.config.PrimeConfig;
import dev.prime.render.RayTracingRuntime;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PrimeClient implements ClientModInitializer {
    public static final String MOD_ID = "prime";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Identifier RELOAD_LISTENER_ID = Identifier.fromNamespaceAndPath(MOD_ID, "ray_tracing_resources");
    private boolean diagnosticKeyDown;

    @Override
    public void onInitializeClient() {
        PrimeConfig.load();
        LOGGER.info("Initializing Prime ray tracing framework");
        RayTracingRuntime.instance().initialize();
        ResourceLoader resourceLoader = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        resourceLoader.registerReloadListener(RELOAD_LISTENER_ID, new SimpleReloadListener<Boolean>() {
            @Override
            protected Boolean prepare(PreparableReloadListener.SharedState state) {
                return Boolean.TRUE;
            }

            @Override
            protected void apply(Boolean prepared, PreparableReloadListener.SharedState state) {
                RayTracingRuntime.instance().reloadShaders();
            }
        });
        resourceLoader.addListenerOrdering(ResourceReloaderKeys.Client.MODELS, RELOAD_LISTENER_ID);
        resourceLoader.addListenerOrdering(ResourceReloaderKeys.Client.SHADERS, RELOAD_LISTENER_ID);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean keyDown = client.getWindow() != null
                    && GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_F9)
                    == GLFW.GLFW_PRESS;
            if (keyDown && !this.diagnosticKeyDown && client.gui.screen() == null) {
                NrdDiagnostics.Mode mode = NrdDiagnostics.cycle();
                if (client.player != null) {
                    client.player.sendSystemMessage(Component.literal(
                            "Prime NRD diagnostics: " + mode.label()));
                }
            }
            this.diagnosticKeyDown = keyDown;
        });
    }
}
