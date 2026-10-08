plugins {
    id("tanseki.kotlin-library")
}

dependencies {
    implementation(project(":core:ports"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.directory.watcher)

    testImplementation(project(":testkit"))
}
