package dev.prime.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

final class PrimeArchitecturePlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.with {
def architectureReport = layout.buildDirectory.dir('reports/prime-architecture')
def architectureClasses = layout.buildDirectory.dir('classes/java/client')

def normalizeClass = { String name ->
    int nested = name.indexOf('$')
    nested < 0 ? name : name.substring(0, nested)
}

def packageName = { String name ->
    int separator = name.lastIndexOf('.')
    separator < 0 ? '' : name.substring(0, separator)
}

def layerOf = { String name ->
    String candidatePackage = packageName(name)
    if (name == 'dev.prime.PrimeClient'
            || candidatePackage == 'dev.prime.client'
            || candidatePackage.startsWith('dev.prime.client.')
            || candidatePackage == 'dev.prime.config'
            || candidatePackage.startsWith('dev.prime.config.')
            || candidatePackage == 'dev.prime.mixin'
            || candidatePackage.startsWith('dev.prime.mixin.')) {
        return 'client'
    }
    if (candidatePackage == 'dev.prime.render.runtime'
            || candidatePackage.startsWith('dev.prime.render.runtime.')) {
        return 'runtime'
    }
    // Minecraft capture and LabPBR atlas access are side adapters, not semantic layers.
    if (candidatePackage == 'dev.prime.render.scene.vanilla'
            || candidatePackage.startsWith('dev.prime.render.scene.vanilla.')
            || name == 'dev.prime.render.vulkan.LabPbrTextureAtlas') {
        return 'adapter'
    }
    if (candidatePackage == 'dev.prime.render.vulkan'
            || candidatePackage.startsWith('dev.prime.render.vulkan.')) {
        return 'vulkan'
    }
    if (candidatePackage == 'dev.prime.render'
            || candidatePackage.startsWith('dev.prime.render.')
            || candidatePackage == 'dev.prime.infrastructure') {
        return 'core'
    }
    return 'other'
}

def stronglyConnected = { Map<String, Set<String>> graph ->
    Set<String> nodes = new TreeSet<>()
    graph.each { source, targets ->
        nodes.add(source)
        nodes.addAll(targets)
    }
    Set<String> visited = new HashSet<>()
    List<String> order = []
    Closure<Void> visit
    visit = { String node ->
        if (!visited.add(node)) {
            return
        }
        (graph[node] ?: Collections.emptySet()).each { visit(it) }
        order.add(node)
    }
    nodes.each { visit(it) }

    Map<String, Set<String>> reverse = new HashMap<>()
    nodes.each { reverse[it] = new TreeSet<>() }
    graph.each { source, targets ->
        targets.each { reverse[it].add(source) }
    }
    visited.clear()
    List<Set<String>> components = []
    Closure<Void> collect
    collect = { String node ->
        if (!visited.add(node)) {
            return
        }
        components.last().add(node)
        reverse[node].each { collect(it) }
    }
    order.reverseEach { node ->
        if (!visited.contains(node)) {
            components.add(new TreeSet<>())
            collect(node)
        }
    }
    components
}

def forbiddenEdges = { Map<String, Set<String>> graph ->
    List<String> violations = []
    graph.each { source, targets ->
        String sourcePackage = packageName(source)
        targets.each { target ->
            String targetPackage = packageName(target)
            boolean pureSource = sourcePackage == 'dev.prime.render'
                    || sourcePackage.startsWith('dev.prime.render.terrain')
                    || sourcePackage.startsWith('dev.prime.render.post')
                    || sourcePackage.startsWith('dev.prime.render.scene')
                            && !sourcePackage.startsWith('dev.prime.render.scene.vanilla')
            boolean forbiddenTarget = targetPackage == 'dev.prime.client'
                    || targetPackage.startsWith('dev.prime.client.')
                    || targetPackage == 'dev.prime.config'
                    || targetPackage.startsWith('dev.prime.config.')
                    || targetPackage == 'dev.prime.mixin'
                    || targetPackage.startsWith('dev.prime.mixin.')
                    || targetPackage == 'dev.prime.render.runtime'
                    || targetPackage.startsWith('dev.prime.render.runtime.')
            if (pureSource && forbiddenTarget) {
                violations.add("pure reverse dependency: ${source} -> ${target}")
            }
            if ((sourcePackage == 'dev.prime.render.terrain'
                    || sourcePackage.startsWith('dev.prime.render.terrain.'))
                    && (targetPackage == 'dev.prime.render.vulkan'
                    || targetPackage.startsWith('dev.prime.render.vulkan.')
                    || targetPackage == 'org.lwjgl.vulkan'
                    || targetPackage.startsWith('org.lwjgl.vulkan.'))) {
                violations.add("pure terrain depends on Vulkan: ${source} -> ${target}")
            }
        }
    }
    violations.sort()
}

def verifyArchitecture = tasks.register('verifyArchitecture') {
    group = 'verification'
    description = 'Verifies compiled Prime class dependencies and cross-layer SCCs with jdeps.'
    dependsOn tasks.named('compileClientJava')
    inputs.dir(architectureClasses)
    outputs.dir(architectureReport)

    doLast {
        File classes = architectureClasses.get().asFile
        File reportDirectory = architectureReport.get().asFile
        reportDirectory.mkdirs()
        String executableName = System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('win')
                ? 'jdeps.exe'
                : 'jdeps'
        File jdeps = new File(System.getProperty('java.home'), "bin/${executableName}")
        if (!jdeps.isFile()) {
            throw new GradleException("JDK jdeps was not found at ${jdeps}")
        }
        Process process = new ProcessBuilder(
                jdeps.absolutePath,
                '--multi-release', '21',
                '--ignore-missing-deps',
                '-verbose:class',
                '-filter:none',
                classes.absolutePath)
                .redirectErrorStream(true)
                .start()
        String output = process.inputStream.getText('UTF-8')
        int exitCode = process.waitFor()
        new File(reportDirectory, 'jdeps.txt').setText(output, 'UTF-8')
        if (exitCode != 0) {
            throw new GradleException("jdeps failed with exit code ${exitCode}")
        }

        Map<String, Set<String>> dependencies = new TreeMap<>()
        output.eachLine { line ->
            def match = line =~ /^\s+(dev\.prime\.[^\s]+)\s+->\s+([^\s]+)\s+.*$/
            if (match.matches()) {
                String source = normalizeClass(match.group(1))
                String target = normalizeClass(match.group(2))
                if (source != target) {
                    dependencies.computeIfAbsent(source) { new TreeSet<>() }.add(target)
                }
            }
        }
        Map<String, Set<String>> graph = new TreeMap<>()
        dependencies.each { source, targets ->
            targets.findAll { it.startsWith('dev.prime.') }.each { target ->
                graph.computeIfAbsent(source) { new TreeSet<>() }.add(target)
            }
        }

        // Executable detector self-test: both a forbidden edge and a cross-layer SCC must trip.
        Map<String, Set<String>> injected = [
                'dev.prime.render.terrain.InjectedCore':
                        ['dev.prime.client.InjectedClient'] as Set,
                'dev.prime.client.InjectedClient':
                        ['dev.prime.render.terrain.InjectedCore'] as Set]
        if (forbiddenEdges(injected).isEmpty()
                || !stronglyConnected(injected).any { component ->
                    component.collect { layerOf(it) }.toSet().intersect(
                            ['client', 'runtime', 'vulkan', 'core'] as Set).size() > 1
                }) {
            throw new GradleException('Architecture verifier injection self-test did not fail')
        }

        List<String> violations = forbiddenEdges(dependencies)
        List<Set<String>> components = stronglyConnected(graph)
        List<Set<String>> crossLayer = components.findAll { component ->
            component.size() > 1
                    && component.collect { layerOf(it) }.toSet().intersect(
                            ['client', 'runtime', 'vulkan', 'core'] as Set).size() > 1
        }
        crossLayer.each { component ->
            violations.add("cross-layer SCC: ${component.join(', ')}")
        }

        Map<String, Set<String>> packageGraph = new TreeMap<>()
        Map<String, Set<String>> layerGraph = new TreeMap<>()
        graph.each { source, targets ->
            String sourcePackage = packageName(source)
            String sourceLayer = layerOf(source)
            targets.each { target ->
                String targetPackage = packageName(target)
                String targetLayer = layerOf(target)
                if (sourcePackage != targetPackage) {
                    packageGraph.computeIfAbsent(sourcePackage) { new TreeSet<>() }
                            .add(targetPackage)
                }
                if (sourceLayer != targetLayer) {
                    layerGraph.computeIfAbsent(sourceLayer) { new TreeSet<>() }
                            .add(targetLayer)
                }
            }
        }

        new File(reportDirectory, 'class-edges.txt').setText(
                graph.collectMany { source, targets ->
                    targets.collect { "${source} -> ${it}" }
                }.join(System.lineSeparator()) + System.lineSeparator(),
                'UTF-8')
        new File(reportDirectory, 'scc.txt').setText(
                components.findAll { it.size() > 1 }
                        .collect { it.join(', ') }
                        .join(System.lineSeparator()) + System.lineSeparator(),
                'UTF-8')
        new File(reportDirectory, 'package-edges.txt').setText(
                packageGraph.collectMany { source, targets ->
                    targets.collect { "${source} -> ${it}" }
                }.join(System.lineSeparator()) + System.lineSeparator(),
                'UTF-8')
        new File(reportDirectory, 'layer-edges.txt').setText(
                layerGraph.collectMany { source, targets ->
                    targets.collect { "${source} -> ${it}" }
                }.join(System.lineSeparator()) + System.lineSeparator(),
                'UTF-8')
        new File(reportDirectory, 'violations.txt').setText(
                violations.join(System.lineSeparator())
                        + (violations.isEmpty() ? '' : System.lineSeparator()),
                'UTF-8')
        new File(reportDirectory, 'summary.txt').setText(
                "classes=${(graph.keySet() + graph.values().flatten()).toSet().size()}\n"
                        + "edges=${graph.values().sum { it.size() } ?: 0}\n"
                        + "packages=${(packageGraph.keySet() + packageGraph.values().flatten()).toSet().size()}\n"
                        + "layerEdges=${layerGraph.values().sum { it.size() } ?: 0}\n"
                        + "scc=${components.count { it.size() > 1 }}\n"
                        + "crossLayerScc=${crossLayer.size()}\n"
                        + "violations=${violations.size()}\n"
                        + "injectionSelfTest=passed\n",
                'UTF-8')
        if (!violations.isEmpty()) {
            throw new GradleException(
                    "Prime architecture violations: ${violations.size()}; see ${reportDirectory}")
        }
    }
}

tasks.named('check') {
    dependsOn verifyArchitecture
}
        }
    }
}
