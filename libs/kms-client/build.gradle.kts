apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

dependencies {
    implementation(Deps.kotlinStdlib)
    implementation(Deps.ktorClientCore)
    implementation(Deps.ktorClientCio)
    implementation(Deps.ktorClientContentNegotiation)
    implementation(Deps.ktorClientLogging)
    implementation(Deps.ktorClientAuth)
    implementation(Deps.ktorSerializationJson)
    implementation(Deps.kotlinxSerializationJson)
}
