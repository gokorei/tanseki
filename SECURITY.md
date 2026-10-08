# Security policy

Security reports are the one channel the project's
[read-only policy](GOVERNANCE.md) does not shut. Tanseki does not merge external
patches, so a reporter should not send proof-of-concept exploit code expecting
it to be committed; the report itself, and a private conversation about it, is
what the maintainer can act on.

## Reporting a vulnerability

Do not report suspected vulnerabilities in a public GitHub issue, discussion,
pull request, or chat message.

Use the repository's **Security** tab and GitHub private vulnerability
reporting. If private reporting is unavailable, contact the repository owner
through a private channel and ask for a private security conversation. Do not
publish exploit details or proof-of-concept code until the maintainers have
coordinated a fix and disclosure.

Include, when available:

- the affected commit, tag, or build and Tanseki mode;
- the HTTP, MCP, CLI, SDK, adapter, or deployment surface involved;
- reproducible steps and the expected versus observed behavior;
- impact, data exposure, and whether a safe workaround exists;
- sanitized logs, configuration, and request/response samples.

Never send API keys, credential registries, database passwords, Meilisearch
keys, private embeddings, vault contents, or other user data. Replace values
with placeholders and describe the field shape when possible.

## Safe testing

Use a disposable test vault and test services. Do not access data you do not own
or are authorized to test. Avoid destructive actions, persistence, denial of
service, and social engineering. If proof requires sensitive material, ask for
a private test path before running it.

Reporters should allow maintainers reasonable time to ship a coordinated fix
before public disclosure.

## Supported security targets

The repository does not yet define tagged release support windows. Until that
policy exists, reports against the current default branch and the latest source
build are in scope. Maintainers decide whether a fix also needs a backport or a
patch release.

There is no published response-time SLA, and no commitment to fix, backport, or
publish an advisory. Do not build a deployment's security posture on the
assumption that a fix will arrive.

Reports are in scope for any surface a user can reach:

- the `/v1` HTTP API, including its authentication and collection scoping;
- the MCP stdio surface;
- the operator CLI and the daemon's local IPC socket;
- the container image and the JVM distribution;
- the build and dependency-resolution path.

Out of scope: reports requiring physical access to a host you already control,
and vulnerabilities in upstream dependencies — report those upstream, where the
maintainer can pick up a fixed version.

Use the same standards and verification path as other changes: run the full
Gradle checks, add a focused regression test where practical, and review
changes to generated or supply-chain artifacts carefully. Because the project
accepts no external patches, a reporter's own fix cannot be merged here;
publish it in a fork if the exposure needs closing faster than upstream can
close it, and say so in the report so the two can be reconciled.
