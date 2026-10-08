plugins {
    id("tanseki.kotlin-library")
}

dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.coroutines.core)
}
