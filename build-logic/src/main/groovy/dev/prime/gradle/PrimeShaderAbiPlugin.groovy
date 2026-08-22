package dev.prime.gradle

import dev.prime.gradle.shader.CompilePrimeSlangComputeShaders
import dev.prime.gradle.shader.GenerateShaderAbi
import dev.prime.gradle.shader.PrepareNsightCapture
import dev.prime.gradle.shader.VerifyGeneratedSlangAbi
import dev.prime.gradle.shader.VerifySlangRayPayloadAbi
import dev.prime.gradle.shader.VerifySlangToolchain
import org.gradle.api.Plugin
import org.gradle.api.Project

final class PrimeShaderAbiPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.extensions.extraProperties.set('primeShaderTaskTypes', [
                generateAbi: GenerateShaderAbi,
                compileSlang: CompilePrimeSlangComputeShaders,
                verifyPayloadAbi: VerifySlangRayPayloadAbi,
                prepareNsight: PrepareNsightCapture,
                verifyToolchain: VerifySlangToolchain,
                verifyGeneratedAbi: VerifyGeneratedSlangAbi
        ])
        project.apply(from: new File(project.rootDir,
                'build-logic/conventions/prime-shader-abi.gradle'))
    }
}
