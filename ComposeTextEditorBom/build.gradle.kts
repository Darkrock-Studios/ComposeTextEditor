plugins {
	`java-platform`
	alias(libs.plugins.mavenPublish)
}

group = "com.darkrockstudios"
version = providers.gradleProperty("library.version").getOrElse("0.0.0-SNAPSHOT")

// Each entry is a module's root coordinate. Gradle follows it to the per-target
// artifact through the module metadata, so the targets need no entries of their own.
dependencies {
	constraints {
		api("com.darkrockstudios:composetexteditor:$version")
		api("com.darkrockstudios:composetexteditor-markdown:$version")
		api("com.darkrockstudios:composetexteditor-spellcheck:$version")
		api("com.darkrockstudios:composetexteditor-find:$version")
	}
}

mavenPublishing {
	coordinates(artifactId = "composetexteditor-bom")
	publishToMavenCentral(automaticRelease = true)
	signAllPublications()

	pom {
		name.set("Compose Text Editor BOM")
		description.set("Bill of materials that aligns the versions of the Compose Text Editor modules.")
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
