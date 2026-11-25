import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
	alias(libs.plugins.mavenPublish) apply false
	alias(libs.plugins.dokka)
}

// Each desktop suite runs in one JVM. Room above Gradle's 512 MB default for tests
// that build long documents on mocks, which record every call (docs/TESTING.md).
// Selecting text in a test must not replace the desktop's X11 primary selection.
subprojects {
	tasks.withType<Test>().matching { it.name == "desktopTest" }.configureEach {
		maxHeapSize = "1g"
		systemProperty("composetexteditor.primarySelection", "false")
	}
}

// Maven Central caps each month's release size, and a module's Dokka site is a few MB.
// Only the root publication carries it; every per-target publication gets an empty
// javadoc jar, which Central accepts.
subprojects {
	pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
		pluginManager.withPlugin("com.vanniktech.maven.publish") {
			pluginManager.withPlugin("org.jetbrains.dokka") {
				extensions.configure<MavenPublishBaseExtension> {
					configure(
						KotlinMultiplatform(
							javadocJar = JavadocJar.None(),
							sourcesJar = SourcesJar.Sources(),
							androidVariantsToPublish = emptyList(),
						)
					)
				}
				val dokkaHtml = tasks.named("dokkaGeneratePublicationHtml")
				extensions.configure<PublishingExtension> {
					publications.withType<MavenPublication>().configureEach {
						val publicationName = name
						// One task per publication: signing rejects a jar shared between publications.
						val javadocJar = tasks.register<Jar>("${publicationName}JavadocJar") {
							archiveClassifier.set("javadoc")
							destinationDirectory.set(layout.buildDirectory.dir("javadoc-jars/$publicationName"))
							if (publicationName == "kotlinMultiplatform") from(dokkaHtml)
						}
						artifact(javadocJar)
					}
				}
			}
		}
	}
}

// Aggregates the four published library modules into a single API doc site.
dependencies {
	dokka(project(":ComposeTextEditor"))
	dokka(project(":ComposeTextEditorMarkdown"))
	dokka(project(":ComposeTextEditorFind"))
	dokka(project(":ComposeTextEditorSpellCheck"))
}

dokka {
	moduleName.set("Compose Text Editor")
	dokkaPublications.html {
		// Lands in a subfolder of the GitHub Pages site so it coexists with the
		// WASM sample at the site root: darkrock-studios.github.io/ComposeTextEditor/api/
		outputDirectory.set(rootDir.resolve("docs/api"))
	}
}

// Refreshes the entire GitHub Pages site under docs/: the Wasm sample at the root
// and the API docs under docs/api. Commit docs/ on the publishing branch afterward.
tasks.register("updateDocs") {
	description = "Builds the Wasm demo and the Dokka API docs into docs/ for GitHub Pages."
	group = "documentation"
	dependsOn(":sampleApp:updateDemo")
	dependsOn("dokkaGenerate")
}