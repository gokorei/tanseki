plugins {
    id("tanseki.kotlin-library")
}

dependencies {
    api(project(":core:ports"))
    implementation(project(":adapters:pijul-cli"))
    implementation(project(":adapters:context-file"))
    implementation(project(":adapters:context-plain"))
    implementation(project(":adapters:context-sqlite"))
    implementation(project(":adapters:lookup-lucene"))
    implementation(project(":adapters:context-postgres"))
    implementation(project(":adapters:lookup-meili"))
    implementation(project(":adapters:embedder-onnx"))
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.sqlite.driver)

    testImplementation(project(":testkit"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}
