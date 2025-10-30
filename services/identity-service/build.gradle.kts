plugins {
    application
}

apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

dependencies {
    implementation(project(":libs:common-models"))
    implementation(project(":libs:crypto-lib"))
    implementation(Deps.kotlinStdlib)
    implementation(Deps.ktorServerCore)
    implementation(Deps.ktorServerNetty)
    implementation(Deps.ktorServerContentNegotiation)
    implementation(Deps.ktorSerializationJson)
    implementation(Deps.logbackClassic)
}

application {
    mainClass.set("com.example.ApplicationKt")
}
