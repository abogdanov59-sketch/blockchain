apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

dependencies {
    implementation(Deps.kotlinStdlib)
    implementation(Deps.kotlinReflect)
    implementation(Deps.kotlinxSerializationJson)
}
