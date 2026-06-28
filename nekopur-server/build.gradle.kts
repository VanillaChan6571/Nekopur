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

val purpurProject = project(":purpur-server")
val purpurBundlerName = "purpur-bundler-${project.version}-mojmap.jar"
val nekopurBundlerName = "nekopur-bundler-${project.version}-mojmap.jar"
val purpurBundlerFile = purpurProject.layout.buildDirectory.dir("libs").map { it.file(purpurBundlerName) }
tasks.register<Copy>("createNekopurMojmapBundlerJar") {
    dependsOn(":purpur-server:createMojmapBundlerJar")
    from(purpurBundlerFile)
    into(layout.buildDirectory.dir("libs"))
    rename(purpurBundlerName, nekopurBundlerName)
}

tasks.named("assemble") {
    dependsOn("createNekopurMojmapBundlerJar")
}
