plugins {
    id("videoeditor.kotlin-library-serialization")
}

/**
 * De enige module die andere modules aan elkaar knoopt.
 *
 * De negen modules eronder kennen elkaar bewust niet: `:core-jobs` weet niets
 * van temperatuur, `:core-thermal` niets van taken. Dat houdt ze los testbaar,
 * maar het betekent ook dat de koppeling ergens moet gebeuren — en dat er
 * getest moet worden dat ze samen doen wat de bedoeling is.
 */
dependencies {
    api(project(":core-model"))
    api(project(":core-errors"))
    api(project(":core-jobs"))
    api(project(":core-thermal"))
    api(project(":core-library"))
    api(project(":core-project"))
    api(project(":core-analysis"))
}
