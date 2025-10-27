plugins {
    application
}

apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

dependencies {
    implementation(project(":libs:common-models"))
    implementation(project(":libs:crypto-lib"))
    implementation(project(":libs:kms-client"))
    implementation(Deps.kotlinStdlib)
    implementation(Deps.ktorServerCore)
    implementation(Deps.ktorServerNetty)
    implementation(Deps.ktorServerContentNegotiation)
    implementation(Deps.ktorServerAuth)
    implementation(Deps.ktorServerAuthJwt)
    implementation(Deps.ktorServerCallLogging)
    implementation(Deps.ktorSerializationJson)
    implementation(Deps.ktorClientCore)
    implementation(Deps.ktorClientCio)
    implementation(Deps.ktorClientContentNegotiation)
    implementation(Deps.ktorClientAuth)
    implementation(Deps.ktorClientLogging)
    implementation(Deps.logbackClassic)
    implementation(Deps.exposedCore)
    implementation(Deps.exposedDao)
    implementation(Deps.exposedJdbc)
    implementation(Deps.exposedJavaTime)
    implementation(Deps.hikari)
    implementation(Deps.postgresDriver)
    implementation(Deps.jwksRsa)
}

application {
    mainClass.set("com.example.kms.ApplicationKt")
}
