# syntax=docker/dockerfile:1.7

# ---- build ----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /workspace

# Warm the Maven cache separately from the sources so code edits do not re-download the world.
COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- runtime --------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S app \
    && adduser -S -G app -h /home/app app \
    && mkdir -p /home/app/.config/gcloud

WORKDIR /app
COPY --from=build /workspace/target/springtime-*.jar app.jar
RUN chown -R app:app /app

USER app
STOPSIGNAL SIGTERM
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Liveness only: a transient BigQuery outage must not recycle the JVM.
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
