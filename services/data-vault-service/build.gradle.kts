plugins {
    application
}

apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

dependencies {
    implementation(project(":libs:common-models"))
    implementation(project(":libs:crypto-lib"))
    implementation(project(":libs:kms-client"))
    implementation(Deps.kotlinStdlib)
    implementation(Deps.kotlinReflect)
    implementation(Deps.ktorServerCore)
    implementation(Deps.ktorServerNetty)
    implementation(Deps.ktorServerContentNegotiation)
    implementation(Deps.ktorSerializationJson)
    implementation(Deps.ktorServerCallLogging)
    implementation(Deps.logbackClassic)
    implementation(Deps.coroutinesCore)
    implementation(Deps.exposedCore)
    implementation(Deps.exposedDao)
    implementation(Deps.exposedJdbc)
    implementation(Deps.exposedJavaTime)
    implementation(Deps.hikari)
    implementation(Deps.postgresDriver)
    implementation(Deps.kafkaClients)
    implementation(Deps.minio)
}

application {
    mainClass.set("com.example.ApplicationKt")
}
