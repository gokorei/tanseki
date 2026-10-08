plugins {
    id("tanseki.kotlin-library")
}

dependencies {
    api(project(":core:ports"))
    api(project(":core:text"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
    api(libs.kotlinx.coroutines.core)
}
