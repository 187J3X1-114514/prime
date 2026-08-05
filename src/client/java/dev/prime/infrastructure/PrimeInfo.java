package dev.prime.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Process-wide identity with no dependency on Prime's client composition root. */
public final class PrimeInfo {
    public static final String MOD_ID = "prime";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private PrimeInfo() {
    }
}
