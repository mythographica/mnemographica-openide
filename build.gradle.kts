import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

val platformLocalPath = providers.gradleProperty("platformLocalPath").orNull.orEmpty()

// Developers compile against the installed OpenIDE. CI has no OpenIDE, so it passes -PciPlatform=true (or an empty
// -PplatformLocalPath=) and gets IntelliJ IDEA of the same 262 branch from JetBrains' repositories instead.
val useCiPlatform = platformLocalPath.isBlank() ||
    providers.gradleProperty("ciPlatform").map { it.toBoolean() }.getOrElse(false)

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    if (useCiPlatform) {
        intellijPlatform {
            // IDEA Community is no longer published separately since 253, so CI uses IntelliJ IDEA of the same branch.
            intellijIdea(providers.gradleProperty("verifyIdeVersion"))
            bundledPlugin("com.intellij.modules.jcef")
        }
    } else {
        intellijPlatform {
            // Development target: the installed OpenIDE itself (runIde starts it).
            local(platformLocalPath)
        }
        // JBCefApp / JBCefBrowser live in the bundled "Web Browser (JCEF)" plugin (com.intellij.modules.jcef). Its jars are
        // added directly: bundledPlugin(...) makes the Gradle plugin parse the OpenIDE layout, which it cannot read
        // (see pluginVerification).
        val jcefJars = files(
            "$platformLocalPath/Contents/plugins/jcef-plugin/lib/modules/intellij.platform.ui.jcef.jar",
            "$platformLocalPath/Contents/plugins/jcef-plugin/lib/modules/intellij.libraries.jcef.jar",
        )
        compileOnly(jcefJars)
        testCompileOnly(jcefJars)
    }
    // org.eclipse.lsp4j.jsonrpc comes from the platform (lib/eclipse.lsp4j.jsonrpc.jar is on the boot class path).
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        // Call interface default methods directly instead of generating bridges that the verifier reports as
        // overrides of deprecated/experimental ToolWindowFactory and StatusBarWidget methods.
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

intellijPlatform {
    // No Java forms / @NotNull instrumentation needed; avoids requiring a full JDK (the JBR has no javac).
    instrumentCode = false
    pluginConfiguration {
        id = "ru.mythographica.mnemographica"
        name = "Mnemonica Graphica"
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // ResourceSchemeHandler registers the mnemo-res:// scheme through JBCefApp.addCefCustomSchemeHandlerFactory, which is
        // @ApiStatus.Internal but the only way to get a standard, fetch-enabled scheme before JCEF starts. The verifier still
        // prints that usage; it just does not fail the build.
        failureLevel = FailureLevel.values().toList() - FailureLevel.INTERNAL_API_USAGES
        ides {
            // The verifier cannot read the OpenIDE layout (its core descriptor is META-INF/OpenIdeProPlugin.xml,
            // the verifier looks for IdeaPlugin.xml), so verify against IntelliJ IDEA of the same 262 branch
            // (IDEA Community is no longer published separately since 253).
            create(IntelliJPlatformType.IntellijIdea, providers.gradleProperty("verifyIdeVersion"))
        }
    }
}

val sidecarDir = layout.projectDirectory.dir("sidecar")
val buildSidecarEnabled = providers.gradleProperty("buildSidecar").map { it.toBoolean() }.getOrElse(false)

val buildSidecar = tasks.register<Exec>("buildSidecar") {
    group = "build"
    description = "Builds the Node sidecar (sidecar/dist) with npm, using nvm when it is installed."
    workingDir = sidecarDir.asFile
    commandLine(
        "sh",
        "-c",
        "export NVM_DIR=\"\$HOME/.nvm\"; " +
            "if [ -s \"\$NVM_DIR/nvm.sh\" ]; then . \"\$NVM_DIR/nvm.sh\" && nvm use 22 >/dev/null; fi; " +
            "npm ci && npm run build",
    )
    onlyIf { sidecarDir.file("package.json").asFile.exists() }
}

tasks {
    // runIde, testIde and verifyPlugin each get their own PrepareSandboxTask (prepareSandbox_runIde, ...),
    // so configure the type, not just the `prepareSandbox` instance behind buildPlugin.
    withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
        if (buildSidecarEnabled) {
            dependsOn(buildSidecar)
        }
        from(sidecarDir.dir("dist")) {
            into(pluginName.map { "$it/sidecar" })
            // The extension's LoggerService writes dist/logs during the sidecar tests; not part of the plugin.
            exclude("logs/**")
        }
    }

    runIde {
        // ./gradlew runIde -PsidecarMain=/abs/path/fake-sidecar.js -PrunIdeProject=/abs/path/to/project
        providers.gradleProperty("sidecarMain").orNull?.let {
            systemProperty("mnemographica.sidecar.main", it)
        }
        providers.gradleProperty("runIdeProject").orNull?.let { args(it) }
        // -PdebugRunCommand=mnemographica.showTypeGraph runs that command 5 s after the sidecar is up (debug hook).
        providers.gradleProperty("debugRunCommand").orNull?.let {
            systemProperty("mnemographica.debug.runCommand", it)
        }
        systemProperty("idea.trust.all.projects", "true")
        systemProperty("ide.show.tips.on.startup.default.value", "false")
    }

    test {
        useJUnit()
        // Plain JUnit tests: skip the IDE test-sandbox classpath, whose bundled-plugin index cannot read the
        // OpenIDE layout (core descriptor is META-INF/OpenIdeProPlugin.xml, not IdeaPlugin.xml).
        // The platform jars (and the Kotlin stdlib they ship) are compile-only; put them on the plain test JVM too.
        classpath = sourceSets.test.get().runtimeClasspath + sourceSets.test.get().compileClasspath
        jvmArgumentProviders.clear()
        doFirst {
            systemProperties.keys.removeIf { it == "java.system.class.loader" }
            jvmArgs = jvmArgs.orEmpty().filterNot { it.startsWith("-Djava.system.class.loader=") }
        }
    }
}
