package dev.prime.render;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Edge-triggered session controls for the native reference accumulator. */
public final class ScreenshotModeControls {
    private static boolean previousEscape;

    private ScreenshotModeControls() {}

    /** Consumes Ctrl+Alt+F2 before vanilla's ordinary F2 screenshot action. */
    public static boolean handleGlobalKeyPress(
            Minecraft minecraft, InputConstants.Key key, boolean controlDown) {
        long window = minecraft.getWindow().handle();
        boolean alt = pressed(window, GLFW.GLFW_KEY_LEFT_ALT)
                || pressed(window, GLFW.GLFW_KEY_RIGHT_ALT);
        if (!controlDown
                || !alt
                || !minecraft.options.keyScreenshot.matches(key)
                || minecraft.level == null) {
            return false;
        }
        ScreenshotMode.request(!ScreenshotMode.requested());
        return true;
    }

    public static void tick(Minecraft minecraft) {
        long window = minecraft.getWindow().handle();
        boolean escape = pressed(window, GLFW.GLFW_KEY_ESCAPE);

        if (escape && !previousEscape && (ScreenshotMode.requested() || ScreenshotMode.active())) {
            // Escape is exit-only. Vanilla still receives it and may open the pause screen.
            ScreenshotMode.request(false);
        }
        previousEscape = escape;
    }

    private static boolean pressed(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }
}
