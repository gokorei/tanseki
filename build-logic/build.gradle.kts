plugins {
    `kotlin-dsl`
}

group = "gokorei.tanseki.buildlogic"

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
}
