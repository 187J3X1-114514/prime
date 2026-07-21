package dev.prime.render.vulkan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** Publishes verified content-addressed native runtime files. */
public final class NativeRuntimeFiles {
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9._-]+");

    private NativeRuntimeFiles() {}

    public static Path directory(String namespace, byte[]... payloads) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Native runtime namespace is invalid");
        }
        Objects.requireNonNull(payloads, "payloads");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", exception);
        }
        for (byte[] payload : payloads) {
            Objects.requireNonNull(payload, "payload");
            for (int shift = Integer.SIZE - Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
                digest.update((byte) (payload.length >>> shift));
            }
            digest.update(payload);
        }
        return Path.of(
                        System.getProperty("java.io.tmpdir"),
                        namespace,
                        HexFormat.of().formatHex(digest.digest()))
                .toAbsolutePath();
    }

    public static void publish(Path target, byte[] expected) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(expected, "expected");
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Native runtime target must have a parent directory");
        }
        try {
            Files.createDirectories(parent);
            if (matches(target, expected)) {
                return;
            }
            Path temporary = Files.createTempFile(
                    parent, target.getFileName() + "-", ".tmp");
            IOException failure = null;
            try {
                Files.write(temporary, expected);
                try {
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                failure = exception;
            } finally {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
            if (!matches(target, expected)) {
                throw new IOException("published contents do not match the bundled runtime");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to publish native runtime " + target, exception);
        }
    }

    private static boolean matches(Path target, byte[] expected) throws IOException {
        if (!Files.isRegularFile(target) || Files.size(target) != expected.length) {
            return false;
        }
        try (InputStream input = Files.newInputStream(target)) {
            byte[] buffer = new byte[8192];
            int offset = 0;
            while (offset < expected.length) {
                int count = input.read(buffer, 0, Math.min(buffer.length, expected.length - offset));
                if (count < 0) {
                    return false;
                }
                for (int index = 0; index < count; index++) {
                    if (buffer[index] != expected[offset + index]) {
                        return false;
                    }
                }
                offset += count;
            }
            return input.read() < 0;
        }
    }
}
