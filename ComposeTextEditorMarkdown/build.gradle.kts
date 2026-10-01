import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.composeMultiplatform)
	// Required by the Compose Multiplatform plugin, whose accessors the dependencies use.
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
		namespace = "com.darkrockstudios.texteditor.markdown"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()

		compilerOptions {
			jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.get()))
		}
	}
	@OptIn(ExperimentalWasmDsl::class)
	wasmJs {
		outputModuleName = "ComposeTextEditorMarkdown"
		browser {
			commonWebpackConfig {
				outputFileName = "composeTextEditorMarkdownLibrary.js"
			}
		}
		binaries.library()
	}

	iosArm64()
	iosSimulatorArm64()

	sourceSets {
		val commonMain by getting {
			dependencies {
				api(projects.composeTextEditor)

				implementation(compose.runtime)
				implementation(compose.ui)
				implementation(libs.markdown)
				implementation(libs.ksoup)
			}
		}

		val desktopMain by getting {
			dependencies {
				implementation(compose.desktop.currentOs)
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

dokka {
	moduleName.set("Markdown")
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
	coordinates(artifactId = "composetexteditor-markdown")
	publishToMavenCentral(automaticRelease = true)
	signAllPublications()

	pom {
		name.set("Compose Text Editor Markdown")
		description.set("Markdown import and export addon for Compose Text Editor.")
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
