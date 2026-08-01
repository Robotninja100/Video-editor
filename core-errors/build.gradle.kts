plugins {
    // Een mislukte taak blijft met fout en al in de wachtrij op schijf staan,
    // dus draagt deze module @Serializable-typen in zijn API.
    id("videoeditor.kotlin-library-serialization")
}
