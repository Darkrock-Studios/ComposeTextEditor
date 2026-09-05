@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
	alias(libs.plugins.android.kmp.library)
	alias(libs.plugins.composeHotReload)
}

kotlin {
	jvmToolchain(libs.versions.jvm.get().toInt())
	applyDefaultHierarchyTemplate()
	jvm("desktop")
	androidLibrary {
		namespace = "com.darkrockstudios.texteditor.sample.shared"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()

		compilerOptions {
			jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.get()))
		}

		androidResources {
			enable = true
		}
	}
	wasmJs {
		outputModuleName = "sampleApp"
		browser {
			commonWebpackConfig {
				outputFileName = "sampleApp.js"
			}
		}
		binaries.executable()
		compilerOptions {
			freeCompilerArgs.add("-Xwasm-use-new-exception-proposal")
		}
	}

	listOf(
		iosArm64(),
		iosSimulatorArm64()
	).forEach { iosTarget ->
		iosTarget.binaries.framework {
			baseName = "SampleApp"
			isStatic = true
		}
	}

	sourceSets {

		val commonMain by getting {
			dependencies {
				implementation(projects.composeTextEditor)
				implementation(projects.composeTextEditorSpellCheck)
				implementation(projects.composeTextEditorFind)
				implementation(compose.runtime)
				implementation(compose.foundation)
				implementation(compose.material3)
				implementation(compose.materialIconsExtended)
				implementation(compose.ui)
				implementation(compose.components.resources)
				implementation(compose.components.uiToolingPreview)
				implementation(compose.components.resources)
			}
		}

		val desktopMain by getting {
			dependencies {
				implementation(libs.kotlinx.coroutines.swing)

				implementation(libs.androidx.lifecycle.viewmodel)
				implementation(libs.androidx.lifecycle.runtime.compose)

				implementation(compose.desktop.currentOs)
				implementation(compose.runtime)
				implementation(compose.foundation)
				implementation(compose.material3)
				implementation(compose.materialIconsExtended)
				implementation(compose.ui)
				implementation(compose.components.resources)
				implementation(compose.components.uiToolingPreview)
				implementation(libs.platform.spellchecker)
			}
		}

		val desktopTest by getting {
			dependencies {
				implementation(libs.jetbrains.kotlin.test)
				implementation(libs.jetbrains.kotlin.test.junit)
				implementation(libs.mockk)
				implementation(libs.kotlinx.coroutines.test)
				implementation(libs.kotlinx.coroutines.test.jvm)
			}
		}

		val androidMain by getting {
			dependencies {
				implementation(compose.runtime)
				implementation(compose.ui)
				implementation(libs.platform.spellchecker)
			}
		}

		val wasmJsMain by getting {
			dependencies {
				implementation(libs.symspellkt)
				implementation(libs.symspellkt.fdic)
			}
		}

		val iosMain by getting {
			dependencies {
				implementation(libs.platform.spellchecker)
			}
		}
	}
}

compose.desktop {
	application {
		mainClass = "com.darkrockstudios.texteditor.sample.MainKt"

		nativeDistributions {
			targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
			packageName = "com.darkrockstudios.texteditor"
			packageVersion = "1.0.0"
		}
	}
}

tasks.register<Copy>("updateDemo") {
	description = "Builds the WASM distribution and copies it to the docs directory for GitHub Pages"
	group = "distribution"

	dependsOn("wasmJsBrowserDistribution")

	from(layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
	into(layout.projectDirectory.dir("../docs"))

	doLast {
		val docsDir = layout.projectDirectory.dir("../docs").asFile
		val buildDir = layout.buildDirectory.dir("dist/wasmJs/productionExecutable").get().asFile

		// Get list of WASM files in build output
		val newWasmFiles =
			buildDir.listFiles { file -> file.extension == "wasm" }?.map { it.name }?.toSet() ?: emptySet()

		// Remove old WASM files that are no longer in the build
		docsDir.listFiles { file -> file.extension == "wasm" }?.forEach { oldFile ->
			if (oldFile.name !in newWasmFiles) {
				println("Removing old WASM file: ${oldFile.name}")
				oldFile.delete()
			}
		}

		println("Demo updated in docs/ directory")
	}
}
