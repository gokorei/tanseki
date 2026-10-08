plugins {
    id("tanseki.kotlin-library")
}

dependencies {
    implementation(project(":core:ports"))
    implementation(project(":core:text"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":testkit"))
}
