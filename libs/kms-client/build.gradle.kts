apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

dependencies {
    api(Deps.kotlinStdlib)
    api(Deps.ktorClientCore)
    api(Deps.ktorClientCio)
    api(Deps.ktorClientContentNegotiation)
    api(Deps.ktorClientLogging)
    api(Deps.ktorClientAuth)
    api(Deps.ktorSerializationJson)
    api(Deps.kotlinxSerializationJson)
}
