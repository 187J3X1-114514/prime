package dev.prime.config;

import dev.prime.PrimeClient;
import dev.prime.render.DisplaySettings;
import dev.prime.render.LightingSettings;
import dev.prime.render.fsr.FsrQualityMode;
import dev.prime.render.fsr.FsrDebugView;
import dev.prime.render.fsr.FsrSettings;
import dev.prime.render.vulkan.nrd.NrdDiagnostics;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
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
    private static final String FSR_DEBUG_VIEW_KEY = "fsr.debug_view";
    private static final String NRD_DEBUG_VIEW_KEY = "nrd.debug_view";
    private static final String SUN_EV_KEY = "lighting.sun_ev";
    private static final String BLOCK_LIGHT_EV_KEY = "lighting.block_light_ev";
    private static final String OKLAB_OVEREXPOSURE_KEY = "display.oklab_overexposure";
    private static boolean dirty;

    private PrimeConfig() {
    }

    public static void load() {
        Path path = configPath();
        FsrQualityMode mode = FsrSettings.DEFAULT_QUALITY_MODE;
        FsrDebugView fsrDebugView = FsrDebugView.OFF;
        NrdDiagnostics.Mode nrdDebugView = NrdDiagnostics.Mode.OFF;
        int sunQuarterSteps = LightingSettings.DEFAULT_SUN_QUARTER_STEPS;
        int blockLightQuarterSteps = LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS;
        int oklabOverexposureSteps = DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS;
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
                String fsrDebugId = properties.getProperty(FSR_DEBUG_VIEW_KEY);
                if (fsrDebugId != null) {
                    FsrDebugView parsedDebug = FsrDebugView.findById(fsrDebugId).orElse(null);
                    if (parsedDebug == null) {
                        PrimeClient.LOGGER.warn(
                                "Unknown Prime FSR debug view '{}'; disabling it",
                                fsrDebugId);
                        rewriteNeeded = true;
                    } else {
                        fsrDebugView = parsedDebug;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String nrdDebugId = properties.getProperty(NRD_DEBUG_VIEW_KEY);
                if (nrdDebugId != null) {
                    NrdDiagnostics.Mode parsedDebug =
                            NrdDiagnostics.Mode.findById(nrdDebugId).orElse(null);
                    if (parsedDebug == null) {
                        PrimeClient.LOGGER.warn(
                                "Unknown Prime NRD debug view '{}'; disabling it",
                                nrdDebugId);
                        rewriteNeeded = true;
                    } else {
                        nrdDebugView = parsedDebug;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String sunEv = properties.getProperty(SUN_EV_KEY);
                if (sunEv != null) {
                    try {
                        sunQuarterSteps = parseEvQuarterSteps(sunEv);
                    } catch (IllegalArgumentException exception) {
                        PrimeClient.LOGGER.warn(
                                "Invalid Prime sun exposure '{}'; using 0 EV",
                                sunEv);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String blockLightEv = properties.getProperty(BLOCK_LIGHT_EV_KEY);
                if (blockLightEv != null) {
                    try {
                        blockLightQuarterSteps = parseEvQuarterSteps(blockLightEv);
                    } catch (IllegalArgumentException exception) {
                        PrimeClient.LOGGER.warn(
                                "Invalid Prime block-light exposure '{}'; using 0 EV",
                                blockLightEv);
                        rewriteNeeded = true;
                    }
                } else {
                    rewriteNeeded = true;
                }
                String oklabOverexposure = properties.getProperty(OKLAB_OVEREXPOSURE_KEY);
                if (oklabOverexposure != null) {
                    try {
                        oklabOverexposureSteps = parseOverexposureSteps(oklabOverexposure);
                    } catch (IllegalArgumentException exception) {
                        PrimeClient.LOGGER.warn(
                                "Invalid Prime Oklab DRT overexposure '{}'; using the default",
                                oklabOverexposure);
                        rewriteNeeded = true;
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
        FsrSettings.setDebugView(fsrDebugView);
        NrdDiagnostics.setMode(nrdDebugView);
        LightingSettings.setSunQuarterSteps(sunQuarterSteps);
        LightingSettings.setBlockLightQuarterSteps(blockLightQuarterSteps);
        DisplaySettings.setOverexposureSteps(oklabOverexposureSteps);
        dirty = rewriteNeeded;
        PrimeClient.LOGGER.info(
                "Prime settings: FSR {} ({}x), sun {} EV, block lights {} EV, Oklab DRT overexposure {}",
                mode.id(),
                mode.upscaleRatio(),
                formatEv(sunQuarterSteps),
                formatEv(blockLightQuarterSteps),
                formatOverexposure(oklabOverexposureSteps));
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

    public static void setNrdDebugView(NrdDiagnostics.Mode mode) {
        if (mode != NrdDiagnostics.mode()) {
            NrdDiagnostics.setMode(mode);
            dirty = true;
        }
    }

    public static void setSunQuarterSteps(int quarterSteps) {
        if (quarterSteps != LightingSettings.sunQuarterSteps()) {
            LightingSettings.setSunQuarterSteps(quarterSteps);
            dirty = true;
        }
    }

    public static void setBlockLightQuarterSteps(int quarterSteps) {
        if (quarterSteps != LightingSettings.blockLightQuarterSteps()) {
            LightingSettings.setBlockLightQuarterSteps(quarterSteps);
            dirty = true;
        }
    }

    public static void setOklabOverexposureSteps(int steps) {
        if (steps != DisplaySettings.overexposureSteps()) {
            DisplaySettings.setOverexposureSteps(steps);
            dirty = true;
        }
    }

    public static void restoreDefaults() {
        setFsrQualityMode(FsrSettings.DEFAULT_QUALITY_MODE);
        setFsrDebugView(FsrDebugView.OFF);
        setNrdDebugView(NrdDiagnostics.Mode.OFF);
        setSunQuarterSteps(LightingSettings.DEFAULT_SUN_QUARTER_STEPS);
        setBlockLightQuarterSteps(LightingSettings.DEFAULT_BLOCK_LIGHT_QUARTER_STEPS);
        setOklabOverexposureSteps(DisplaySettings.DEFAULT_OVEREXPOSURE_STEPS);
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
                    + FSR_DEBUG_VIEW_KEY + "=" + FsrSettings.debugView().id() + "\n"
                    + NRD_DEBUG_VIEW_KEY + "=" + NrdDiagnostics.mode().id() + "\n"
                    + SUN_EV_KEY + "=" + formatEv(LightingSettings.sunQuarterSteps()) + "\n"
                    + BLOCK_LIGHT_EV_KEY + "="
                    + formatEv(LightingSettings.blockLightQuarterSteps()) + "\n"
                    + OKLAB_OVEREXPOSURE_KEY + "="
                    + formatOverexposure(DisplaySettings.overexposureSteps()) + "\n";
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

    static int parseEvQuarterSteps(String value) {
        try {
            int quarterSteps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(LightingSettings.QUARTER_STEPS_PER_EV))
                    .intValueExact();
            LightingSettings.linearMultiplier(quarterSteps);
            return quarterSteps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("EV must be an exact 0.25-EV step", exception);
        }
    }

    static String formatEv(int quarterSteps) {
        LightingSettings.linearMultiplier(quarterSteps);
        return BigDecimal.valueOf(quarterSteps)
                .divide(BigDecimal.valueOf(LightingSettings.QUARTER_STEPS_PER_EV))
                .toPlainString();
    }

    static int parseOverexposureSteps(String value) {
        try {
            int steps = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(DisplaySettings.STEPS_PER_UNIT))
                    .intValueExact();
            DisplaySettings.overexposure(steps);
            return steps;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Oklab DRT overexposure must be an exact 1/32 step",
                    exception);
        }
    }

    static String formatOverexposure(int steps) {
        DisplaySettings.overexposure(steps);
        return BigDecimal.valueOf(steps)
                .divide(BigDecimal.valueOf(DisplaySettings.STEPS_PER_UNIT))
                .toPlainString();
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("prime.properties");
    }
}
