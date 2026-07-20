package dev.prime.render.post;

import dev.prime.config.PrimeConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Edge-triggered release-build shortcuts for Prime's own RR diagnostics. */
public final class DlssRrDebugControls {
    private static boolean previousCycle;
    private static boolean previousLayout;

    private DlssRrDebugControls() {}

    public static void tick(Minecraft minecraft) {
        long window = minecraft.getWindow().handle();
        boolean control = pressed(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                || pressed(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean alt = pressed(window, GLFW.GLFW_KEY_LEFT_ALT)
                || pressed(window, GLFW.GLFW_KEY_RIGHT_ALT);
        boolean cycle = control && alt && pressed(window, GLFW.GLFW_KEY_F12);
        boolean layout = control && alt && pressed(window, GLFW.GLFW_KEY_F11);
        if (cycle && !previousCycle) {
            PrimeConfig.setDlssRrDebugView(PostProcessingSettings.rrDebugView().next());
            PrimeConfig.save();
        }
        if (layout && !previousLayout) {
            PrimeConfig.setDlssRrDebugFullscreen(!PostProcessingSettings.rrDebugFullscreen());
            PrimeConfig.save();
        }
        previousCycle = cycle;
        previousLayout = layout;
    }

    private static boolean pressed(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }
}
