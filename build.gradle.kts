import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    // Bundled into the plugin distribution (lib/) so the DBF parser is available at runtime.
    implementation(libs.javadbf)

    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }

        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(project.version.toString()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }

    // Run the IntelliJ Plugin Verifier against the compatibility-range endpoints: the sinceBuild
    // floor (2024.2 / build 242) and the platform the plugin is built against (current(), i.e.
    // 2025.3.5 — reused from the build, no extra download). Run with `./gradlew verifyPlugin`.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2")
            current()
        }
    }

    // `./gradlew publishPlugin` uploads the built distribution to JetBrains Marketplace, updating
    // the existing listing (the very first version must be uploaded manually for moderation). The
    // version published comes from `gradle.properties` (`version=`, must not be a -SNAPSHOT), and
    // change notes from CHANGELOG.md via pluginConfiguration.changeNotes above. The artifact is not
    // self-signed here, so Marketplace signs it with its own certificate.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    // Ship the plugin's own license plus the third-party (LGPL) notices and license texts
    // inside the distribution zip, alongside lib/. buildPlugin already sets the archive root
    // to the plugin folder via into(projectName), so these land at <plugin>/... next to lib/.
    // This satisfies LGPL-3.0's requirement that the license text and attribution for the
    // bundled javadbf library be conveyed together with the binary. See THIRD-PARTY-NOTICES.md.
    named<Zip>("buildPlugin") {
        from("LICENSE")
        from("THIRD-PARTY-NOTICES.md")
        from("licenses") {
            into("licenses")
        }
    }
}
