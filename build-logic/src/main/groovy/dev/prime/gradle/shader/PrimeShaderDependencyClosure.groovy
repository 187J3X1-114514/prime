package dev.prime.gradle.shader

import org.gradle.api.GradleException
import java.util.concurrent.Callable

/** Lazily resolves the exact source closure of one Slang entry point. */
class PrimeShaderDependencyClosure implements Callable<List<File>>, Serializable {
    private final String sourcePath
    private final List<String> includeRootPaths

    @javax.inject.Inject
    PrimeShaderDependencyClosure(File source, Collection<File> includeRoots) {
        this.sourcePath = source.toPath().toAbsolutePath().normalize().toString()
        this.includeRootPaths = includeRoots.collect {
            it.toPath().toAbsolutePath().normalize().toString()
        }
    }

    @Override
    List<File> call() {
        def roots = includeRootPaths.collect { new File(it) }.findAll { it.isDirectory() }
        def sources = []
        roots.each { root ->
            root.eachFileRecurse(groovy.io.FileType.FILES) { candidate ->
                if (candidate.name.endsWith('.slang') || candidate.name.endsWith('.h')) {
                    sources.add(candidate.toPath().toAbsolutePath().normalize().toFile())
                }
            }
        }
        def pathKey = { File file ->
            file.toPath().toAbsolutePath().normalize().toString()
        }
        def sourceByPath = sources.collectEntries { [(pathKey(it)): it] }
        def dependencyPattern = java.util.regex.Pattern.compile(
                '(?m)^\\s*(?:#\\s*include\\s+"([^"]+)"|import\\s+"([^"]+)"\\s*;)')
        def graph = new HashMap<String, Set<String>>()
        sources.each { candidate ->
            def targets = graph.computeIfAbsent(pathKey(candidate)) { new TreeSet<String>() }
            def matcher = dependencyPattern.matcher(candidate.getText('UTF-8'))
            while (matcher.find()) {
                def dependencyName = matcher.group(1) ?: matcher.group(2)
                def matches = ([new File(candidate.parentFile, dependencyName)]
                        + roots.collect { new File(it, dependencyName) })
                        .collect { it.toPath().toAbsolutePath().normalize().toFile() }
                        .findAll { sourceByPath.containsKey(pathKey(it)) }
                        .unique { pathKey(it) }
                if (matches.size() != 1) {
                    throw new GradleException(
                            "Cannot resolve unique shader dependency ${dependencyName} from ${candidate}")
                }
                targets.add(pathKey(matches.first()))
            }
        }

        def root = new File(sourcePath).toPath().toAbsolutePath().normalize().toFile()
        def rootPath = pathKey(root)
        if (!sourceByPath.containsKey(rootPath)) {
            throw new GradleException("Shader entry is outside its include roots: ${root}")
        }
        def closure = new TreeSet<String>()
        def pending = new ArrayDeque<String>()
        pending.add(rootPath)
        while (!pending.empty) {
            def current = pending.removeLast()
            if (closure.add(current)) {
                (graph[current] ?: Collections.emptySet()).each { pending.add(it) }
            }
        }
        return closure.collect { sourceByPath[it] }
    }
}
