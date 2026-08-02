plugins {
    id("videoeditor.kotlin-library-serialization")
}

dependencies {
    api(project(":core-model"))

    // Eén foutenvocabulaire voor de hele app: elke fout draagt een stabiele
    // code, een retryable-vlag en een tekst die de gebruiker snapt.
    api(project(":core-errors"))
}
