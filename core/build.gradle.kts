plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("com.github.mwiede:jsch:2.28.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.apache.sshd:sshd-core:2.14.0")
    testImplementation("net.i2p.crypto:eddsa:0.3.0")
    testImplementation("org.slf4j:slf4j-simple:2.0.16")
}
tasks.test {
    systemProperty("pocket.root", rootProject.projectDir.absolutePath)
    testLogging { events("passed", "failed", "skipped") }
}
tasks.register<Test>("liveSshProbe") {
    description = "Read-only SSH integration probe against the existing local Codex daemon"
    group = "verification"
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("pocket.root", rootProject.projectDir.absolutePath)
    environment("CODEX_POCKET_LIVE", "true")
    filter { includeTestsMatching("*actual Codex service*") }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
tasks.register<Test>("liveSettingsProbe") {
    description = "Verify settings on a disposable ephemeral thread; never starts a model turn"
    group = "verification"
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("pocket.root", rootProject.projectDir.absolutePath)
    environment("CODEX_POCKET_LIVE", "true")
    environment("CODEX_POCKET_LIVE_SETTINGS", "true")
    filter { includeTestsMatching("*ephemeral Codex settings*") }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}

tasks.register<Test>("liveImageProbe") {
    description = "Send one tiny generated test image to a disposable ephemeral vision conversation"
    group = "verification"
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("pocket.root", rootProject.projectDir.absolutePath)
    environment("CODEX_POCKET_LIVE", "true")
    environment("CODEX_POCKET_LIVE_IMAGES", "true")
    filter { includeTestsMatching("*ephemeral image input*") }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
