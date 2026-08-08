package dev.prime.render.scene;

/** Device-free scene identity observed by render plans. */
public interface SceneRevisionView {
    int originX();
    int originY();
    int originZ();
    long revision();
    long resetRevision();
    long temporalRevision();
}
