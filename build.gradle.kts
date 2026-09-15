import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java // TODO java launcher tasks
    id("io.papermc.paperweight.patcher") version "2.0.0-beta.21"
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"

paperweight {
    upstreams.paper {
        ref = providers.gradleProperty("paperCommit")

        patchFile {
            path = "paper-server/build.gradle.kts"
            outputFile = file("purpur-server/build.gradle.kts")
            patchFile = file("purpur-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-api/build.gradle.kts"
            outputFile = file("purpur-api/build.gradle.kts")
            patchFile = file("purpur-api/build.gradle.kts.patch")
        }
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("purpur-api/paper-patches")
            outputDir = file("paper-api")
        }
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
        options.compilerArgs.addAll(listOf("-Xlint:-deprecation", "-Xlint:-removal"))
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
    }

    extensions.configure<PublishingExtension> {
        repositories {
            maven("https://repo.purpurmc.org/snapshots") {
                name = "purpur"
                credentials(PasswordCredentials::class)
            }
        }
    }
}

tasks.register("printMinecraftVersion") {
    doLast {
        println(providers.gradleProperty("mcVersion").get().trim())
    }
}

tasks.register("printNekopurVersion") {
    doLast {
        println(project.version)
    }
}

// Nekopur build entry points. These exist because the correct commands for this fork are not
// discoverable: the build task is named for paperweight's bundler rather than for Nekopur, and
// `nekopur-overlay/README.md` documents an overlay task that is not registered anywhere. Anyone
// (or anything) reading `gradlew tasks` should not have to reverse-engineer the fork's layout.
val nekopurGroup = "nekopur"

tasks.register("nekopurBuild") {
    group = nekopurGroup
    description = "Builds the Nekopur server jar (Mojmap bundler) into nekopur-server/build/libs."
    dependsOn(":nekopur-server:createNekopurMojmapBundlerJar")
}

tasks.register("nekopurApplyPatches") {
    group = nekopurGroup
    description =
        "Applies Purpur's Minecraft patches into purpur-server/src/minecraft, the tree Nekopur compiles."
    dependsOn(":purpur-server:applyMinecraftPatches")
}

tasks.register("nekopurWhere") {
    group = nekopurGroup
    description = "Prints where each kind of Nekopur change belongs, and what is currently unresolved."
    doLast {
        println(
            """
            Nekopur source layout
            ---------------------
            Nekopur's own classes      nekopur-server/src/main/java/org/nekopur/...
                                       Ordinary tracked sources. Edit directly.

            Minecraft (net.minecraft)  purpur-server/src/minecraft/java/...
                                       This tree is gitignored and generated, but it IS what
                                       `nekopurBuild` compiles, and an edit there survives a build.
                                       Existing Nekopur hooks live in
                                       purpur-server/minecraft-patches/sources/... as `// Nekopur`
                                       hunks, so that is where such a change is meant to end up.

            Capturing a net.minecraft edit into its patch (do this BEFORE applyAllPatches):
                ./gradlew :purpur-server:fixupMinecraftSourcePatches
                ./gradlew :purpur-server:rebuildMinecraftSourcePatches

            fixup commits the generated tree's changes into the file-patches commit via an
            autosquash rebase; rebuild then exports that commit. Rebuild ALONE does nothing:
            it stashes working changes and checks out the `file` commit before exporting, so an
            uncommitted edit is invisible to it by design. That is why existing hunks survive a
            rebuild while a new one silently never appears.

            Back the edit up first, and stop if the rebase reports a conflict. Note fixup stages
            changes in this repository, so stash unrelated work before running it. Verify the
            hunk landed in purpur-server/minecraft-patches/sources/... before building.

            nekopur-overlay/ is NOT wired into the build. Its README describes a task
            (:purpur-server:applyNekopurOverlay) that does not exist, and no build script
            references the directory. scripts/apply-nekopur-overlay.sh therefore always fails.
            """.trimIndent()
        )
    }
}
