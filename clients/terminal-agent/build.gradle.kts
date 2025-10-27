plugins {
    application
}

dependencies {
    implementation(Deps.kotlinStdlib)
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.6")
    implementation(project(":libs:common-models"))
}

application {
    mainClass.set("com.example.TerminalAgentKt")
}
