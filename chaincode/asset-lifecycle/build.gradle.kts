plugins {
    `java-library`
}

dependencies {
    implementation("org.hyperledger.fabric-chaincode-java:fabric-chaincode-shim:2.5.0")
}

tasks.withType<JavaCompile> {
    options.release.set(11)
}
