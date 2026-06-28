plugins {
    base
}

// Package Nekopur server classes from the purpur-server compilation output.
tasks.named<Jar>("jar") {
    archiveBaseName.set("nekopur-server")
    from(project(":purpur-server").the<SourceSetContainer>()["main"].output) {
        include("org/nekopur/**")
    }
    dependsOn.clear()
    dependsOn(":purpur-server:classes")
}

tasks.named("assemble") {
    dependsOn("jar")
}

// Disable local compilation; jar pulls compiled classes from purpur-server.
tasks.withType<JavaCompile>().configureEach {
    enabled = false
}
tasks.named("processResources") {
    enabled = false
}
tasks.named("classes") {
    enabled = false
}

// Nekopur - 26.x paperweight renamed createMojmapBundlerJar -> createBundlerJar (mojmap is the default, no reobf variant).
// Reference the task output directly so we don't depend on the purpur bundler filename.
// Nekopur - 26.x paperweight renamed createMojmapBundlerJar -> createBundlerJar, whose bundler jar is
// "purpur-bundler-<version>.jar". Copy it by filename from purpur-server's libs dir (config-cache safe:
// no cross-project task object reference) and rename to the Nekopur bundler name.
val nekopurBundlerName = "nekopur-bundler-${project.version}-mojmap.jar"
val purpurBundlerSource = "purpur-bundler-${project.version}.jar"
tasks.register<Copy>("createNekopurMojmapBundlerJar") {
    dependsOn(":purpur-server:createBundlerJar")
    from(layout.projectDirectory.dir("../purpur-server/build/libs"))
    include(purpurBundlerSource)
    into(layout.buildDirectory.dir("libs"))
    rename(purpurBundlerSource.replace(".", "\\."), nekopurBundlerName)
}

tasks.named("assemble") {
    dependsOn("createNekopurMojmapBundlerJar")
}
