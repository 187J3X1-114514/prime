package dev.prime.gradle.shader

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/** Build-wide concurrency token for memory-heavy Slang compiler processes. */
abstract class PrimeSlangCompilerGate implements BuildService<BuildServiceParameters.None>, AutoCloseable {
    @Override
    void close() {}
}
