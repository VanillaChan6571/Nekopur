// Applied to purpur-server, which owns the compiled native generator and runtime dependencies.
tasks.register<JavaExec>("previewSeed") {
    group = "worldgen"
    description = "Export a native Neko seed preview as HTML and PNG maps"
    dependsOn("classes")
    classpath(project.the<SourceSetContainer>()["main"].runtimeClasspath)
    mainClass.set("org.nekopur.worldgen.preview.NekoSeedPreview")
    javaLauncher.set(project.the<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    maxHeapSize = "2G"
    systemProperty("java.awt.headless", "true")
    workingDir = rootProject.layout.projectDirectory.asFile
    for ((option, fallback) in mapOf("preset" to "neko", "seed" to "6571", "size" to "256", "step" to "128", "x" to "0", "z" to "0", "threads" to "4", "mode" to "fast", "output" to "seed-previews")) {
        args("--$option", providers.gradleProperty("preview.$option").getOrElse(fallback))
    }
}

tasks.register<JavaExec>("seedViewer") {
    group = "worldgen"
    description = "Launch the interactive native Neko seed viewer on loopback"
    dependsOn("classes")
    classpath(project.the<SourceSetContainer>()["main"].runtimeClasspath)
    mainClass.set("org.nekopur.worldgen.preview.NekoSeedViewer")
    javaLauncher.set(project.the<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    maxHeapSize = "2G"
    systemProperty("java.awt.headless", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = rootProject.layout.projectDirectory.asFile
    for ((option, fallback) in mapOf("preset" to "neko", "port" to "8765", "threads" to "4", "seed" to "6571", "x" to "0", "z" to "0", "mode" to "fast")) {
        args("--$option", providers.gradleProperty("preview.$option").getOrElse(fallback))
    }
}
