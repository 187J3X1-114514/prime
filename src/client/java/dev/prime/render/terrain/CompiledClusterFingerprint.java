package dev.prime.render.terrain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical content fingerprint for the CPU scene-compiler boundary.
 *
 * <p>The encoding is little-endian, uses raw float bits, excludes Java identity and GPU addresses,
 * and is evaluated only when a test or explicit capture requests it.
 */
public final class CompiledClusterFingerprint {
    private CompiledClusterFingerprint() {
    }

    public static byte[] sha256(CompiledCluster cluster) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(CompiledClusterCodec.encode(cluster));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java runtime must provide SHA-256", exception);
        }
    }

    public static String sha256Hex(CompiledCluster cluster) {
        return HexFormat.of().formatHex(sha256(cluster));
    }
}
