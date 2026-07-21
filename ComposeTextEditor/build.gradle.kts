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
                implementation(libs.markdown)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
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