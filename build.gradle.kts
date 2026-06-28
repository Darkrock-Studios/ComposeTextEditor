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

// Aggregates the three published library modules into a single API doc site.
dependencies {
	dokka(project(":ComposeTextEditor"))
	dokka(project(":ComposeTextEditorFind"))
	dokka(project(":ComposeTextEditorSpellCheck"))
}

dokka {
	moduleName.set("Compose Text Editor")
	dokkaPublications.html {
		// Lands in a subfolder of the GitHub Pages site so it coexists with the
		// WASM sample at the site root: darkrock-studios.github.io/ComposeTextEditorLibrary/api/
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