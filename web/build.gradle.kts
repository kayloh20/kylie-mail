plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

kotlin {
    jvmToolchain(21)
}

val ktor = "3.6.1"

// The Android app's mail code that doesn't depend on Android, compiled here as-is so the web
// version reads, parses, files and replies exactly like the app. Only add files that import
// nothing from Android or the app's database.
sourceSets {
    main {
        kotlin.srcDir("../app/src/main/java")
        kotlin.include(
            "com/kayloh20/kyliemail/web/**",
            "com/kayloh20/kyliemail/data/mail/BodyParser.kt",
            "com/kayloh20/kyliemail/data/mail/EmailHtml.kt",
            "com/kayloh20/kyliemail/data/mail/FolderRole.kt",
            "com/kayloh20/kyliemail/data/mail/ImapConnector.kt",
            "com/kayloh20/kyliemail/data/mail/MessageContent.kt",
            "com/kayloh20/kyliemail/data/mail/ServerConfig.kt",
            "com/kayloh20/kyliemail/data/filing/FilingModel.kt",
            "com/kayloh20/kyliemail/data/compose/MessageComposer.kt",
            "com/kayloh20/kyliemail/data/compose/ReplyBuilder.kt",
            "com/kayloh20/kyliemail/data/compose/SmtpSender.kt",
            "com/kayloh20/kyliemail/data/search/SearchQuery.kt",
            "com/kayloh20/kyliemail/data/search/ServerSearch.kt",
        )
    }
}

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-default-headers:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.webauthn4j:webauthn4j-core:0.32.0.RELEASE")
    implementation("org.jsoup:jsoup:1.24.1")
    // The same mail library as the app (javax.mail, version 1.6), in its plain-Java build.
    implementation("com.sun.mail:jakarta.mail:1.6.8")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    // A real IMAP server for tests; it uses our mail library instead of bringing its own.
    testImplementation("com.icegreen:greenmail:1.6.15") { exclude(group = "com.sun.mail") }
}

application {
    mainClass.set("com.kayloh20.kyliemail.web.MainKt")
    applicationName = "web"
}

tasks.test {
    useJUnit()
}

// Runs the server locally with a pretend iCloud login; see LocalServer.kt.
tasks.register<JavaExec>("runLocal") {
    group = "application"
    description = "Runs Kylie Mail on http://localhost:8080 with a pretend iCloud login"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.kayloh20.kyliemail.web.LocalServerKt")
    workingDir = projectDir
}
