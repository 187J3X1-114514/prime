package dev.prime.render.scene.vanilla;

/** Mixin-owned stable entity identity carried by Minecraft's extracted render state. */
public interface PrimeEntityRenderState {
    int prime$entityId();

    void prime$entityId(int entityId);
}
