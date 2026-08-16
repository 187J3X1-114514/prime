package dev.prime.render;

/** Raw first-surface local-light signals exposed by the realtime integrator. */
public enum PrimaryLightDiagnosticView {
    OFF("off", 0),
    SAMPLE("sample", 1),
    SQUARED_SAMPLE("squared_sample", 2);

    private final String id;
    private final int abiValue;

    PrimaryLightDiagnosticView(String id, int abiValue) {
        this.id = id;
        this.abiValue = abiValue;
    }

    public String id() {
        return this.id;
    }

    public int abiValue() {
        return this.abiValue;
    }

    public static PrimaryLightDiagnosticView fromId(String id) {
        for (PrimaryLightDiagnosticView value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return OFF;
    }
}
