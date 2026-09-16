# Stage 1: build
FROM gradle:9.5-jdk21-alpine AS builder

WORKDIR /app

COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle clean assemble --no-daemon

# Stage 2: runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN apk add --no-cache curl \
 && addgroup -S assistant && adduser -S assistant -G assistant

COPY --from=builder /app/build/libs/*.jar app.jar

USER assistant:assistant

EXPOSE 8080

# The home server runs close to its memory limit, so the heap follows the container limit rather
# than the host's total RAM. Without this the JVM sizes itself against all 14 GiB.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
