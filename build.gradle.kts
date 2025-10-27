buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
        classpath("org.jetbrains.kotlin:kotlin-serialization:1.9.22")
    }
}

subprojects {
    repositories {
        mavenCentral()
    }

    if (project.path != ":chaincode:asset-lifecycle") {
        apply(plugin = "org.jetbrains.kotlin.jvm")

        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(17)
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
