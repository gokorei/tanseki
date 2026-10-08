plugins {
    id("tanseki.kotlin-library")
}

dependencies {
    api(project(":core:domain"))
    api(project(":core:ports"))
    implementation(project(":core:text"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":testkit"))
}
