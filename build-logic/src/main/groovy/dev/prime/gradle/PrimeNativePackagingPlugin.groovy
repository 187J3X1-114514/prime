package dev.prime.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

final class PrimeNativePackagingPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.apply(from: new File(project.rootDir,
                'build-logic/conventions/prime-native-packaging.gradle'))
    }
}
