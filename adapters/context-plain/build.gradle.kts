plugins {
    id("tanseki.kotlin-library")
}

dependencies {
    implementation(project(":core:ports"))
    implementation(project(":core:text"))
}
