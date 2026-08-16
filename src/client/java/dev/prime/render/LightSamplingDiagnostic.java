package dev.prime.render;

/** Non-persistent local-light tree variants used to isolate realtime variance. */
public enum LightSamplingDiagnostic {
    BASELINE("baseline", 0),
    NO_SOFTENING("no_softening", 1),
    RECEIVER_LEAVES("receiver_leaves", 2),
    FOUR_CANDIDATE_RIS("four_candidate_ris", 3);

    private final String id;
    private final int abiValue;

    LightSamplingDiagnostic(String id, int abiValue) {
        this.id = id;
        this.abiValue = abiValue;
    }

    public String id() {
        return this.id;
    }

    public int abiValue() {
        return this.abiValue;
    }

    public static LightSamplingDiagnostic fromId(String id) {
        for (LightSamplingDiagnostic value : values()) {
            if (value.id.equals(id)) {
                return value;
            }
        }
        return BASELINE;
    }
}
