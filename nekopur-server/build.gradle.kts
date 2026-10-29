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

// Nekopur - 26.x paperweight renamed createMojmapBundlerJar -> createBundlerJar (mojmap is the default,
// no reobf variant), whose bundler jar is "purpur-bundler-<version>.jar". Copy it by filename from
// purpur-server's libs dir (config-cache safe: no cross-project task object reference / closures) and
// rename to the Nekopur bundler name. The survival line names its jar distinctly so a
// built artifact is never mistaken for the base 26.3 build.
val nekopurBundlerName = "nekopur-mmo-survival-bundler-${project.version}-mojmap.jar"
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

// Sample the same native registries as the server without creating a world.
tasks.register("previewSeed") {
    group = "worldgen"
    description = "Export a native Neko seed preview as HTML and PNG maps"
    dependsOn(":purpur-server:previewSeed")
}

tasks.register("seedViewer") {
    group = "worldgen"
    description = "Launch the interactive native Neko seed viewer"
    dependsOn(":purpur-server:seedViewer")
}
