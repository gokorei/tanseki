plugins {
    id("tanseki.kotlin-library")
}

dependencies {
    implementation(project(":core:ports"))
    implementation(libs.kotlinx.coroutines.core)

    // runtimeOnly: OnnxEmbedder talks to ORT reflectively. Keeping the large jar
    // off the Kotlin compile classpath avoids pathological compile times.
    runtimeOnly(libs.onnxruntime)
}

// No unit test loads the ONNX runtime, so keep it off the test runtime classpath
// too (it bundles native libraries and slows test JVMs).
configurations.named("testRuntimeClasspath") {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime")
}

