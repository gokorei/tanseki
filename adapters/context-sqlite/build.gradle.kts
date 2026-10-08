plugins {
    id("tanseki.kotlin-library")
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("TansekiDatabase") {
            packageName.set("gokorei.tanseki.adapters.context.sqlite.db")
        }
    }
}

dependencies {
    implementation(project(":core:ports"))
    implementation(project(":core:text"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.sqlite.driver)

    testImplementation(project(":testkit"))
}
