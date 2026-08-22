package dev.prime.render.terrain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical content fingerprint used by scene-compiler behavior tests. */
final class CompiledClusterFingerprint {
    private CompiledClusterFingerprint() {
    }

    static byte[] sha256(CompiledCluster cluster) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(CompiledClusterCodec.encode(cluster));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("Every Java runtime must provide SHA-256", exception);
        }
    }

    static String sha256Hex(CompiledCluster cluster) {
        return HexFormat.of().formatHex(sha256(cluster));
    }
}
