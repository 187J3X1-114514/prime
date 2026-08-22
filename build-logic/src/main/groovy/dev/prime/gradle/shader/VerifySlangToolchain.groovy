package dev.prime.gradle.shader

import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*

abstract class VerifySlangToolchain extends DefaultTask {
	@InputFile
	@PathSensitive(PathSensitivity.RELATIVE)
	abstract RegularFileProperty getSmokeShader()

	@Input
	abstract Property<String> getSlangCompiler()

	@Input
	abstract Property<String> getExpectedVersion()

	@Input
	abstract Property<String> getExpectedSpirvToolsVersions()

	@Input
	abstract Property<String> getSpirvValidator()

	@Input
	abstract Property<String> getSpirvDisassembler()

	private static String runAndCapture(List<String> arguments) {
		def command = arguments.collect { it.toString() }
		def process
		try {
			process = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.start()
		} catch (IOException exception) {
			throw new GradleException("Could not start shader tool: ${command[0]}", exception)
		}
		def output = process.inputStream.getText('UTF-8')
		try {
			def exitCode = process.waitFor()
			if (exitCode != 0) {
				throw new GradleException(
						"Shader tool failed with exit code ${exitCode}: ${command[0]}\n${output}")
			}
		} catch (InterruptedException exception) {
			process.destroyForcibly()
			Thread.currentThread().interrupt()
			throw new GradleException("Shader tool was interrupted: ${command[0]}", exception)
		}
		return output.trim()
	}

	@TaskAction
	void verify() {
		def compiler = slangCompiler.get()
		def expected = expectedVersion.get()
		def version = runAndCapture([compiler, '-version'])
		def versionPattern = java.util.regex.Pattern.compile(
				"(?m)(^|\\s)${java.util.regex.Pattern.quote(expected)}(\\s|\$)")
		if (!versionPattern.matcher(version).find()) {
			throw new GradleException(
					"Prime requires Slang ${expected}, but ${compiler} reported: ${version}")
		}

		def expectedSpirvTools = expectedSpirvToolsVersions.get()
				.split('\\|')
				.collect { it.trim() }
				.findAll { !it.empty }
		def validator = spirvValidator.get()
		def validatorVersion = runAndCapture([validator, '--version'])
		def validatorReported = validatorVersion.readLines().first()
		if (!expectedSpirvTools.contains(validatorReported)) {
			throw new GradleException(
					"Prime requires one of ${expectedSpirvTools}, but ${validator} reported: "
							+ validatorVersion)
		}
		def disassembler = spirvDisassembler.get()
		def disassemblerVersion = runAndCapture([disassembler, '--version'])
		def disassemblerReported = disassemblerVersion.readLines().first()
		if (!expectedSpirvTools.contains(disassemblerReported)) {
			throw new GradleException(
					"Prime requires one of ${expectedSpirvTools}, but ${disassembler} reported: "
							+ disassemblerVersion)
		}

		def output = new File(temporaryDir, 'toolchain_smoke.spv')
		output.parentFile.mkdirs()
		runAndCapture([
				compiler,
				smokeShader.get().asFile.absolutePath,
				'-target', 'spirv',
				'-profile', 'glsl_460',
				'-capability', 'spirv_1_5',
				'-entry', 'main',
				'-stage', 'compute',
				// Slang matrix terminology is inverted when lowered to SPIR-V. This emits the
				// ColMajor decoration used by the existing GLSL ABI.
				'-matrix-layout-row-major',
				'-fvk-use-gl-layout',
				'-emit-spirv-directly',
				'-warnings-as-errors', 'all',
				'-O0', '-g0',
				'-o', output.absolutePath
		])
		runAndCapture([
				spirvValidator.get(),
				'--target-env', 'vulkan1.2',
				output.absolutePath
		])
		def assembly = runAndCapture([spirvDisassembler.get(), output.absolutePath])
		def abiPatterns = [
				'(?m)OpMemberDecorate %\\w+ 0 ColMajor$',
				'(?m)OpMemberDecorate %\\w+ 0 MatrixStride 16$',
				'(?m)OpMemberDecorate %\\w+ 1 Offset 64$',
				'(?m)OpDecorate %\\w+ Binding 0$',
				'(?m)OpDecorate %\\w+ DescriptorSet 0$',
				'(?m)OpTypePointer PushConstant '
		]
		def missing = abiPatterns.findAll { pattern ->
			!java.util.regex.Pattern.compile(pattern).matcher(assembly).find()
		}
		if (!missing.empty) {
			throw new GradleException(
					"Slang smoke SPIR-V does not preserve the Prime ABI baseline: ${missing}")
		}
	}
}
