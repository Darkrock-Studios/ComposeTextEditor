import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
}

kotlin {
	jvmToolchain(libs.versions.jvm.get().toInt())
	compilerOptions {
		jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.get()))
	}
}

android {
	namespace = "com.darkrockstudios.texteditor.sample"
	compileSdk = libs.versions.android.compileSdk.get().toInt()
	defaultConfig {
		applicationId = "com.darkrockstudios.texteditor.sample"
		minSdk = libs.versions.android.minSdk.get().toInt()
		targetSdk = libs.versions.android.compileSdk.get().toInt()
		versionCode = 1
		versionName = "1.0"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}
	buildFeatures {
		compose = true
	}
	compileOptions {
		sourceCompatibility = JavaVersion.toVersion(libs.versions.jvm.get().toInt())
		targetCompatibility = JavaVersion.toVersion(libs.versions.jvm.get().toInt())
	}

	buildTypes {
		debug {
		}

		release {
		}
	}
}

dependencies {
	implementation(projects.sampleApp)

	implementation(compose.runtime)
	implementation(compose.foundation)
	implementation(compose.material3)
	implementation(compose.ui)

	implementation(libs.lifecycle.runtime.ktx)
	implementation(libs.activity.compose)
	implementation(libs.activity.ktx)
	implementation(platform(libs.compose.bom))
	implementation(libs.ui)
	implementation(libs.ui.graphics)
	implementation(libs.ui.tooling.preview)
	implementation(libs.material3)

	androidTestImplementation(platform(libs.compose.bom))
	androidTestImplementation(libs.ui.test.junit4)
	debugImplementation(libs.ui.tooling)
	debugImplementation(libs.ui.test.manifest)
}
