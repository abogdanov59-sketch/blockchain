object Versions {
    const val kotlin = "1.9.22"
    const val ktor = "2.3.8"
    const val kotlinxSerialization = "1.6.2"
    const val coroutines = "1.7.3"
    const val springBoot = "3.2.2"
    const val flowable = "6.8.0"
    const val fabricSdk = "2.2.15"
    const val logback = "1.4.14"
}

object Deps {
    const val kotlinStdlib = "org.jetbrains.kotlin:kotlin-stdlib-jdk8:${'$'}{Versions.kotlin}"
    const val kotlinReflect = "org.jetbrains.kotlin:kotlin-reflect:${'$'}{Versions.kotlin}"
    const val ktorServerCore = "io.ktor:ktor-server-core:${'$'}{Versions.ktor}"
    const val ktorServerNetty = "io.ktor:ktor-server-netty:${'$'}{Versions.ktor}"
    const val ktorServerContentNegotiation = "io.ktor:ktor-server-content-negotiation:${'$'}{Versions.ktor}"
    const val ktorSerializationJson = "io.ktor:ktor-serialization-kotlinx-json:${'$'}{Versions.ktor}"
    const val logbackClassic = "ch.qos.logback:logback-classic:${'$'}{Versions.logback}"
    const val kotlinxSerializationJson = "org.jetbrains.kotlinx:kotlinx-serialization-json:${'$'}{Versions.kotlinxSerialization}"
}
