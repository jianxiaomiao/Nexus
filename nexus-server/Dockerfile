FROM eclipse-temurin:21-jre-jammy@sha256:d63bd8d9b171999cbed8576f2c76e874dd4856791a358536e5c4d407e77edc13 AS java21
FROM mcr.microsoft.com/playwright/java:v1.63.0-noble@sha256:a0f0a471c0bbdc3f024ed4727fc80d41b280b2420d9552ac85d7827f6cd37c00

COPY --from=java21 /opt/java/openjdk /opt/java/openjdk
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
ENV XDG_CACHE_HOME=/tmp/nexus-cache
ENV NEXUS_SCREENSHOT_JOB_RESULT_DIR=/var/lib/nexus/screenshot-jobs

RUN mkdir -p /tmp/nexus-cache /var/lib/nexus/screenshot-jobs \
    && chown 10001:10001 /tmp/nexus-cache /var/lib/nexus/screenshot-jobs

WORKDIR /app
COPY target/nexus-server-0.0.1-SNAPSHOT.jar app.jar

USER 10001:10001

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
