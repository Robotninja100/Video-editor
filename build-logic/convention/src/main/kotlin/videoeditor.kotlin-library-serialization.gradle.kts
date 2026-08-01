/**
 * Als [videoeditor.kotlin-library], plus kotlinx.serialization.
 *
 * De serialisatie-runtime staat er als `api` bij: elke module die deze variant
 * gebruikt draagt `@Serializable`-typen in zijn eigen API, dus consumenten
 * hebben `kotlinx-serialization-json` sowieso nodig.
 */

plugins {
    id("videoeditor.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "api"(libs.findLibrary("kotlinx-serialization-json").get())
}
