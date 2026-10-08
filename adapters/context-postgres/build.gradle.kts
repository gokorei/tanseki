plugins {
    id("tanseki.kotlin-library")
}

dependencies {
    implementation(project(":core:ports"))
    implementation(project(":core:text"))
    implementation(libs.postgresql)
    implementation(libs.hikaricp)

    testImplementation(project(":testkit"))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}
