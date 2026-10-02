import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
	alias(libs.plugins.android.kmp.library)
	alias(libs.plugins.mavenPublish)
	alias(libs.plugins.dokka)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
    applyDefaultHierarchyTemplate()
    jvm("desktop")
	androidLibrary {
		namespace = "com.darkrockstudios.texteditor"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()

		compilerOptions {
			jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.get()))
        }

		withHostTestBuilder {}
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
	    outputModuleName = "ComposeTextEditor"
        browser {
            commonWebpackConfig {
	            outputFileName = "ComposeTextEditor.js"
            }
        }
        binaries.library()
    }

	iosArm64()
	iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.ksoup)
            }
        }

        // Desktop, iOS, and wasm share Compose's skiko text input API
        // (PlatformTextInputMethodRequest is the same interface on all three).
        val skikoMain by creating {
            dependsOn(commonMain)
        }

        val androidMain by getting {
            dependencies {
                // AccessibilityDelegateCompat, which Compose already brings at runtime.
                implementation(libs.androidx.core)
            }
        }

        val desktopMain by getting {
            dependsOn(skikoMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }

        val iosMain by getting {
            dependsOn(skikoMain)
        }

        val wasmJsMain by getting {
            dependsOn(skikoMain)
        }

        // Runs on the simulator, for what only the platform can answer (UIPasteboard).
        val iosTest by getting {
            dependencies {
                implementation(libs.jetbrains.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val androidHostTest by getting {
            dependencies {
                implementation(libs.jetbrains.kotlin.test)
                implementation(libs.jetbrains.kotlin.test.junit)
                implementation(libs.mockk)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val desktopTest by getting {
            kotlin.srcDir(rootDir.resolve("testUtils/countingMeasurer"))
            kotlin.srcDir(rootDir.resolve("testUtils/blockLines"))
            kotlin.srcDir(rootDir.resolve("testUtils/stateFuzz"))
            kotlin.srcDir(rootDir.resolve("testUtils/uiTest"))
            kotlin.srcDir(rootDir.resolve("testUtils/uiFuzz"))
            kotlin.srcDir(rootDir.resolve("testUtils/testFont/kotlin"))
            resources.srcDir(rootDir.resolve("testUtils/testFont/resources"))
            dependencies {
                implementation(libs.jetbrains.kotlin.test)
                implementation(libs.jetbrains.kotlin.test.junit)
                implementation(libs.mockk)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.coroutines.test.jvm)
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

// There are no wasmJs tests; this Compose check trips on the Skiko that main pulls in.
tasks.matching { it.name == "checkComposeUiTestConfigurationForWasmJs" }.configureEach { enabled = false }

// Golden screenshots (docs/TESTING.md); -PupdateGoldens rewrites them.
class GoldenScreenshotArguments(
	@get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) val goldens: File,
	@get:Internal val failures: File,
	@get:Input val update: Boolean,
) : CommandLineArgumentProvider {
	override fun asArguments() = listOf(
		"-Dgoldens.dir=${goldens.absolutePath}",
		"-Dgoldens.failures=${failures.absolutePath}",
		"-Dgoldens.update=$update",
	)
}

tasks.withType<Test>().matching { it.name == "desktopTest" }.configureEach {
	val failures = layout.buildDirectory.dir("golden-failures").get().asFile
	jvmArgumentProviders += GoldenScreenshotArguments(
		goldens = layout.projectDirectory.dir("src/desktopTest/goldens").asFile,
		failures = failures,
		update = providers.gradleProperty("updateGoldens").map { it != "false" }.getOrElse(false),
	)
	doFirst { failures.deleteRecursively() }
}

// The window the nightly real-input job types into (testUtils/osInput/drive.sh).
tasks.register<JavaExec>("runOsInputProbe") {
	description = "Opens a focused editor that writes its text to the directory given in --args."
	group = "verification"
	val test = kotlin.targets.getByName("desktop").compilations.getByName("test")
	classpath = files(test.output.allOutputs, test.runtimeDependencyFiles)
	mainClass.set("osinput.OsInputProbeKt")
}

dokka {
	moduleName.set("Editor")
	dokkaSourceSets.configureEach {
		includes.from("Module.md")
		sourceLink {
			localDirectory.set(rootDir)
			remoteUrl("https://github.com/Darkrock-Studios/ComposeTextEditor/blob/main")
			remoteLineSuffix.set("#L")
		}
	}
}

group = "com.darkrockstudios"
version = providers.gradleProperty("library.version").getOrElse("0.0.0-SNAPSHOT")

mavenPublishing {
	coordinates(artifactId = "composetexteditor")
	publishToMavenCentral(automaticRelease = true)
	signAllPublications()

	pom {
		name.set("Compose Text Editor")
		description.set("A Kotlin Multiplatform Text Editor.")
		url.set("https://github.com/Darkrock-Studios/ComposeTextEditor")

		licenses {
			license {
				name.set("MIT")
				url.set("https://opensource.org/licenses/MIT")
			}
		}
		issueManagement {
			system.set("Github")
			url.set("https://github.com/Darkrock-Studios/ComposeTextEditor/issues")
		}
		scm {
			connection.set("scm:git:git://github.com/Darkrock-Studios/ComposeTextEditor.git")
			developerConnection.set("scm:git:ssh://github.com/Darkrock-Studios/ComposeTextEditor.git")
			url.set("https://github.com/Darkrock-Studios/ComposeTextEditor")
		}
		developers {
			developer {
				name.set("Adam Brown")
				id.set("Wavesonics")
				email.set("adamwbrown@gmail.com")
			}
		}
	}
}