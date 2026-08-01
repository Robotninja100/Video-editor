// Aparte, ingesloten build voor de conventieplugins. Bewust géén `buildSrc`:
// een wijziging in buildSrc maakt het hele hoofdproject ongeldig, terwijl een
// ingesloten build alleen de projecten opnieuw bouwt die er echt van afhangen.
//
// Let op: Google's Maven staat hier niet bij. Die is in deze omgeving
// onbereikbaar en de plugins die we nodig hebben staan alle op Maven Central.

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
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
