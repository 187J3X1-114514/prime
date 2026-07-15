package dev.prime.config;

import dev.prime.PrimeClient;
import dev.prime.render.fsr.FsrQualityMode;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.fsr.FsrSettings;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Small, version-tolerant owner for Prime's user-facing settings. */
public final class PrimeConfig {
    private static final String QUALITY_KEY = "fsr.quality";
    private static final String DEBUG_VIEW_KEY = "fsr.debug_view";
    private static boolean dirty;

    private PrimeConfig() {
    }

    public static void load() {
        Path path = configPath();
        FsrQualityMode mode = FsrSettings.DEFAULT_QUALITY_MODE;
        FsrDebugView debugView = FsrDebugView.OFF;
        boolean rewriteNeeded = false;
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                Properties properties = new Properties();
                properties.load(reader);
                String id = properties.getProperty(QUALITY_KEY);
                if (id != null) {
                    FsrQualityMode parsed = FsrQualityMode.findById(id).orElse(null);
                    if (parsed == null) {
                        PrimeClient.LOGGER.warn(
                                "Unknown Prime FSR quality mode '{}'; using {}",
                                id,
                                FsrSettings.DEFAULT_QUALITY_MODE.id());
                        rewriteNeeded = true;
                    } else {
                        mode = parsed;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String debugId = properties.getProperty(DEBUG_VIEW_KEY);
                if (debugId != null) {
                    FsrDebugView parsedDebug = FsrDebugView.findById(debugId).orElse(null);
                    if (parsedDebug == null) {
                        PrimeClient.LOGGER.warn(
                                "Unknown Prime FSR debug view '{}'; disabling it",
                                debugId);
                        rewriteNeeded = true;
                    } else {
                        debugView = parsedDebug;
                    }
                } else {
                    rewriteNeeded = true;
                }
            } catch (IOException | IllegalArgumentException exception) {
                PrimeClient.LOGGER.warn(
                        "Could not read {}; using the default Prime settings",
                        path,
                        exception);
                rewriteNeeded = true;
            }
        }
        FsrSettings.setQualityMode(mode);
        FsrSettings.setDebugView(debugView);
        dirty = rewriteNeeded;
        PrimeClient.LOGGER.info(
                "Prime FSR quality mode: {} ({}x)",
                mode.id(),
                mode.upscaleRatio());
    }

    public static void setFsrQualityMode(FsrQualityMode mode) {
        if (mode != FsrSettings.qualityMode()) {
            FsrSettings.setQualityMode(mode);
            dirty = true;
        }
    }

    public static void setFsrDebugView(FsrDebugView mode) {
        if (mode != FsrSettings.debugView()) {
            FsrSettings.setDebugView(mode);
            dirty = true;
        }
    }

    public static void save() {
        Path path = configPath();
        if (!dirty && Files.isRegularFile(path)) {
            return;
        }

        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            String contents = QUALITY_KEY + "=" + FsrSettings.qualityMode().id() + "\n"
                    + DEBUG_VIEW_KEY + "=" + FsrSettings.debugView().id() + "\n";
            Files.writeString(
                    temporary,
                    contents,
                    StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException exception) {
            PrimeClient.LOGGER.error("Could not save Prime settings to {}", path, exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("prime.properties");
    }
}
