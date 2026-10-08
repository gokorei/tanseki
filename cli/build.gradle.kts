plugins {
    id("tanseki.kotlin-library")
}

dependencies {
    implementation(project(":core:application"))
    implementation(project(":core:text"))
    implementation(project(":composition"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":adapters:context-file"))
    testImplementation(project(":adapters:context-sqlite"))
    testImplementation(project(":adapters:context-postgres"))
    testImplementation(project(":testkit"))
    testImplementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.postgresql)
    testImplementation(libs.testcontainers.postgresql)
}

// Synthetic large-vault benchmark (see gokorei.tanseki.cli.bench.VaultBench).
// Fixtures live under build/bench-vault and are never committed.
tasks.register<JavaExec>("benchLargeVault") {
    group = "verification"
    description = "Builds a synthetic vault and reports index-build, search-latency and reconcile timings."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("gokorei.tanseki.cli.bench.VaultBenchKt")
    val benchDocs = (project.findProperty("benchDocs") as String?).orEmpty().ifEmpty { "1000" }
    args = listOf("--docs", benchDocs)
}
