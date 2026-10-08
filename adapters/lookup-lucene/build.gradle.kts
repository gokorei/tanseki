plugins {
    id("tanseki.kotlin-library")
}

dependencies {
    implementation(project(":core:ports"))
    implementation(project(":core:text"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.lucene.core)
    implementation(libs.lucene.analysis.common)
    implementation(libs.lucene.queryparser)

    testImplementation(project(":testkit"))
}
