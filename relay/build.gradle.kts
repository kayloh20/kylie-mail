plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.eclipse.angus:angus-mail:2.0.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.58.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.icegreen:greenmail:2.1.15")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}

application {
    mainClass.set("com.kayloh20.kyliemail.relay.MainKt")
    applicationName = "relay"
}

tasks.test {
    useJUnit()
}
