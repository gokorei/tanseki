# Release

How versioned core artifacts reach downstream consumers (ticket VQT2D11G).

## Decision: GitHub Packages

`dev.gokorei:core-{domain,ports,text}` are published to GitHub Packages
(`maven.pkg.github.com/gokorei/tanseki`), not Maven Central:

- No signing burden: Central requires signed artifacts; Packages does not.
- Auth stays inside the org: CI publishes with the automatic `GITHUB_TOKEN`,
  no long-lived secret to rotate and nothing committed.
- Coordinates stay with the code: the repository URL derives from
  `tansekiGitUserId`/`tansekiGitRepoId` in `gradle.properties`, next to
  `tansekiGroup`/`tansekiVersion`.

Trade-off, stated plainly: consumers authenticate. GitHub Packages refuses
anonymous reads, so every consumer configures the repository with a personal
access token holding `read:packages` (snippet below). If anonymous resolution
ever matters more than the signing/auth savings, the decision to revisit is
Maven Central — the `maven` publication itself is registry-agnostic.

## Version policy

`tansekiVersion` in `gradle.properties` is the single release identity, and
the git tag is `v<tansekiVersion>` (currently `1.0.0` → `v1.0.0`). Publishing
`1.0.0` fixes what that coordinate means, so the tag is cut on the exact
commit whose artifacts go out, and the tag-version guard in `publish.yml`
fails the run when they disagree.

## Cutting a release (mechanical)

1. Set `tansekiVersion` in `gradle.properties` (it is read by the build, the
   SDKs, and the publish coordinates alike).
2. Merge to `main` and let CI go green.
3. Tag the green commit and push the tag:
   `git tag v<tansekiVersion> && git push origin v<tansekiVersion>`.
4. The `publish-core` workflow publishes the three artifacts; the
   tag-version guard names the coordinates in the log.
5. Verify: an empty `~/.m2` consumer resolves
   `dev.gokorei:core-domain:<tansekiVersion>` from GitHub Packages.

## Consuming (Musubime)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/gokorei/tanseki")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").getOrNull()
                password = providers.environmentVariable("GITHUB_TOKEN").getOrNull()
            }
        }
    }
}
```

With this, Musubime drops `mavenLocal()`, the `.deps` bootstrap checkouts,
and `handover/` — coordinates resolve like any other dependency, and
per-bump checksum refreshes end for unchanged APIs.
