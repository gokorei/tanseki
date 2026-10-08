pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "tanseki"

include(
    ":core:domain",
    ":core:text",
    ":core:ports",
    ":core:application",
    ":adapters:pijul-cli",
    ":adapters:context-file",
    ":adapters:context-plain",
    ":adapters:context-sqlite",
    ":adapters:lookup-lucene",
    ":adapters:watcher-os",
    ":adapters:embedder-onnx",
    ":adapters:context-postgres",
    ":adapters:lookup-meili",
    ":service",
    ":composition",
    ":cli",
    ":sdk:generated:kotlin",
    ":testkit",
)
