package dev.prime.gradle.shader

import groovy.json.JsonOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Compiles and validates exactly one manifest-declared Slang artifact. */
@CacheableTask
abstract class CompilePrimeSlangProgram extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getSourceFile()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getShaderDependencies()

    @Internal
    abstract ConfigurableFileCollection getIncludeDirectories()

    @Input
    abstract Property<String> getArtifactId()

    @Input
    abstract Property<String> getStage()

    @Input
    abstract ListProperty<String> getDefinitions()

    @Input
    abstract Property<String> getSlangCompiler()

    @Input
    abstract Property<String> getSlangToolchainVersion()

    @Input
    abstract Property<String> getSpirvValidator()

    @Input
    abstract Property<String> getDebugLevel()

    /** -g2 embeds canonical source paths, so cross-workspace cache reuse would corrupt debug paths. */
    @Input
    abstract Property<String> getCanonicalWorkspacePath()

    @OutputFile
    abstract RegularFileProperty getOutputFile()

    @OutputFile
    abstract RegularFileProperty getDependencyFile()

    @OutputFile
    abstract RegularFileProperty getMetricsFile()

    private static String runCommand(List<String> arguments) {
        def command = arguments.collect { it.toString() }
        def process = new ProcessBuilder(command).redirectErrorStream(true).start()
        def output = new ByteArrayOutputStream()
        def drain = new Thread({ process.inputStream.transferTo(output) }, 'prime-shader-tool-output')
        drain.start()
        try {
            def exitCode = process.waitFor()
            drain.join()
            if (exitCode != 0) {
                throw new GradleException(
                        "Shader tool failed with exit code ${exitCode}: ${command.join(' ')}"
                                + System.lineSeparator()
                                + output.toString(java.nio.charset.StandardCharsets.UTF_8))
            }
            return output.toString(java.nio.charset.StandardCharsets.UTF_8)
        } catch (InterruptedException exception) {
            process.destroyForcibly()
            drain.interrupt()
            Thread.currentThread().interrupt()
            throw new GradleException("Shader tool was interrupted: ${command.join(' ')}", exception)
        }
    }

    private static Set<String> parseDependencies(File depfile) {
        def text = depfile.getText('UTF-8')
        int separator = text.indexOf(': ')
        if (separator < 0) {
            throw new GradleException("Malformed Slang depfile: ${depfile}")
        }
        def dependencies = new TreeSet<String>()
        def token = new StringBuilder()
        boolean escaped = false
        text.substring(separator + 2).each { character ->
            if (escaped) {
                token.append(character)
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (Character.isWhitespace((char) character)) {
                if (token.length() > 0) {
                    dependencies.add(new File(token.toString()).toPath()
                            .toAbsolutePath().normalize().toString())
                    token.setLength(0)
                }
            } else {
                token.append(character)
            }
        }
        if (escaped) token.append('\\')
        if (token.length() > 0) {
            dependencies.add(new File(token.toString()).toPath()
                    .toAbsolutePath().normalize().toString())
        }
        return dependencies
    }

    @TaskAction
    void compile() {
        def output = outputFile.get().asFile
        def depfile = dependencyFile.get().asFile
        def metrics = metricsFile.get().asFile
        output.parentFile.mkdirs()
        depfile.parentFile.mkdirs()
        metrics.parentFile.mkdirs()

        def arguments = [
                slangCompiler.get(), sourceFile.get().asFile.absolutePath,
                '-target', 'spirv',
                '-profile', 'glsl_460',
                '-capability', 'spirv_1_5',
                '-capability', 'SPV_KHR_non_semantic_info',
                '-capability', 'SPV_GOOGLE_user_type',
                '-capability', 'spvSparseResidency',
                '-capability', 'spvMinLod',
                '-capability', 'spvFragmentFullyCoveredEXT',
                '-capability', 'spvGroupNonUniform',
                '-capability', 'spvGroupNonUniformBallot',
                '-capability', 'spvShaderInvocationReorderEXT',
                '-entry', 'main', '-stage', stage.get(),
                '-allow-glsl', '-matrix-layout-row-major', '-fvk-use-gl-layout',
                '-emit-spirv-directly', '-warnings-as-errors', 'all',
                '-O2', debugLevel.get()
        ]
        arguments.addAll(definitions.get())
        includeDirectories.files.findAll { it.isDirectory() }.sort { it.absolutePath }.each {
            arguments.addAll(['-I', it.absolutePath])
        }
        arguments.addAll(['-depfile', depfile.absolutePath, '-o', output.absolutePath])

        long started = System.nanoTime()
        runCommand(arguments)
        long compiled = System.nanoTime()
        def declaredDependencies = shaderDependencies.files.collect {
            it.toPath().toAbsolutePath().normalize().toString()
        }.toSet()
        def compilerDependencies = parseDependencies(depfile)
        if (declaredDependencies != compilerDependencies) {
            throw new GradleException(
                    "Slang dependency closure drift for ${artifactId.get()}; "
                            + "declaredOnly=${declaredDependencies - compilerDependencies}, "
                            + "compilerOnly=${compilerDependencies - declaredDependencies}")
        }
        runCommand([spirvValidator.get(), '--target-env', 'vulkan1.2', output.absolutePath])
        long finished = System.nanoTime()
        metrics.setText(JsonOutput.prettyPrint(JsonOutput.toJson([
                artifact: artifactId.get(),
                compileNanoseconds: compiled - started,
                validationNanoseconds: finished - compiled,
                outputBytes: output.length(),
                dependencyCount: shaderDependencies.files.size()
        ])) + System.lineSeparator(), 'UTF-8')
    }
}
