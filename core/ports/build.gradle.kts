plugins {
    id("tanseki.kotlin-published")
}

dependencies {
    api(project(":core:domain"))
    api(libs.kotlinx.coroutines.core)
}
