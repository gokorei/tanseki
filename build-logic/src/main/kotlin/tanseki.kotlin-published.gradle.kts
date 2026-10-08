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
    // Hosted coordinates for downstream consumers (Musubime). GitHub Packages
    // was chosen over Central: no signing burden, auth is the CI-provided
    // GITHUB_TOKEN, and the coordinates stay inside the org that owns them.
    // Credentials arrive via environment (CI) or Gradle properties (local
    // dry run); absent credentials fail loudly at publish time, never at
    // build time. Repository coordinates reuse the release identity in
    // gradle.properties so a rename edits one file.
    repositories {
        maven {
            name = "GitHubPackages"
            url =
                uri(
                    "https://maven.pkg.github.com/" +
                        providers.gradleProperty("tansekiGitUserId").getOrElse("gokorei") +
                        "/" +
                        providers.gradleProperty("tansekiGitRepoId").getOrElse("tanseki")
                )
            credentials {
                username =
                    providers.environmentVariable("GITHUB_ACTOR")
                        .orElse(providers.gradleProperty("githubActor"))
                        .getOrElse("")
                password =
                    providers.environmentVariable("GITHUB_TOKEN")
                        .orElse(providers.gradleProperty("githubToken"))
                        .getOrElse("")
            }
        }
    }
}
