# Vault mode needs the `pijul` binary on PATH, and pijul publishes no Linux
# binary, so the release CI pins is compiled once here and copied into the
# runtime stage. Without this the documented `docker compose --profile vault`
# stack starts a daemon that fails when FileContextStore calls `pijul init`.
FROM rust:1.99.0-slim-bookworm@sha256:2c3a22f0a5533ea2dd5a16627bc841228151faa2d4de2644ac9987e4a2f1f2fa AS pijul

ARG PIJUL_VERSION=1.0.0-beta.24
RUN apt-get update \
    && apt-get install -y --no-install-recommends libsodium-dev libssl-dev pkg-config \
    && rm -rf /var/lib/apt/lists/* \
    && cargo install --root /pijul "pijul" --version "=$PIJUL_VERSION"

FROM eclipse-temurin:21-jdk@sha256:92a2a4d7a928d057e7bd999c418d66c26a34eb9a0442f3ab67721c3f88110b2d AS build

WORKDIR /workspace

# Only the Gradle project and the sources the distribution is built from are
# copied, so no local vault, database, credential, or documentation can reach
# the build context or the layer cache. The paths mirror .dockerignore.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties LICENSE THIRD_PARTY_NOTICES.md ./
COPY gradle ./gradle
COPY build-logic ./build-logic
COPY config ./config
COPY core ./core
COPY adapters ./adapters
COPY composition ./composition
COPY testkit ./testkit
COPY cli ./cli
COPY service ./service
COPY sdk ./sdk

RUN ./gradlew --no-daemon :service:installDist

FROM eclipse-temurin:21-jre@sha256:49e21e16e3c86eb7816a44a67549910ed090fbeb40c29c525d58bf5e02e91b0f

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl libsodium23 libssl3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 tanseki \
    && useradd --system --uid 10001 --gid tanseki --home-dir /opt/tanseki --shell /usr/sbin/nologin tanseki \
    && mkdir -p /vault

COPY --from=pijul /pijul/bin/pijul /usr/local/bin/pijul
RUN pijul --version

COPY --from=build --chown=tanseki:tanseki /workspace/service/build/install/tanseki-daemon/ /opt/tanseki/

# Apache-2.0 requires the licence text to accompany the distributed binary, and
# the MIT notice for the TOON library it embeds is a condition of that grant.
# Neither is of any use inside the image unless it is actually present here.
COPY --from=build --chown=tanseki:tanseki /workspace/LICENSE /workspace/THIRD_PARTY_NOTICES.md /opt/tanseki/

RUN chown -R tanseki:tanseki /opt/tanseki /vault

ENV TANSEKI_PATH=/vault \
    TANSEKI_INDEX_DIR=/vault/.tanseki/index \
    TANSEKI_SOCKET=/vault/.tanseki/tanseki.sock \
    TANSEKI_LOG_LEVEL=INFO

USER tanseki
VOLUME ["/vault"]
EXPOSE 8088
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8088/v1/health | grep -q '"status":"ok"'

ENTRYPOINT ["/opt/tanseki/bin/tanseki-daemon"]
