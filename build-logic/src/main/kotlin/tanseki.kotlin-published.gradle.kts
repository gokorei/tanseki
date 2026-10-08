plugins {
    id("tanseki.kotlin-library")
    `maven-publish`
}

// First-party Maven coordinates for the shared core: downstream consumers
// (Musubime) resolve dev.gokorei:core-{domain,ports,text} instead of
// bootstrapping them from throwaway checkouts with an out-of-tree patch.
//
// The artifactId derives from the project path (:core:domain -> core-domain)
// so the coordinate never drifts from the module layout. Group and version
// are the single release identity in gradle.properties.
java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = providers.gradleProperty("tansekiGroup").getOrElse("dev.gokorei")
            artifactId = project.path.drop(1).replace(":", "-")
            version = providers.gradleProperty("tansekiVersion").getOrElse("1.0.0")
        }
    }
}
