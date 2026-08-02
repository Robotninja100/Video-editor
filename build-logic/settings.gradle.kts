// Aparte, ingesloten build voor de conventieplugins. Bewust géén `buildSrc`:
// een wijziging in buildSrc maakt het hele hoofdproject ongeldig, terwijl een
// ingesloten build alleen de projecten opnieuw bouwt die er echt van afhangen.
//
// Google's Maven staat er wél bij, maar wordt alleen aangesproken als de
// Android-plugin daadwerkelijk gevraagd wordt — zie convention/build.gradle.kts,
// waar dat achter dezelfde SDK-voorwaarde staat als in de hoofdbuild. Op een
// machine zonder Android SDK wordt er dus niets bij Google opgehaald, en dat is
// precies de omgeving waarin de pure-JVM modules geschreven zijn.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
