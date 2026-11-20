# Stage 1: build
FROM gradle:9.5-jdk21-alpine AS builder

WORKDIR /app

COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle clean assemble --no-daemon

# Stage 2: runtime
# glibc 이미지다. 파일 읽기와 결과물 저장이 SecureDirectoryStream(openat+O_NOFOLLOW)으로 링크 바꿔치기 경쟁을
# 막으려면 glibc 가 있어야 한다(musl 은 SecureDirectoryStream 을 주지 않는다). #359.
FROM eclipse-temurin:21-jre

WORKDIR /app

# uid 100, gid 101 은 앞선 Alpine 이미지(addgroup/adduser -S)가 주던 값이다. 운영 저장소가 이 id 로 마운트를
# 소유해 번호를 그대로 고정한다. curl 은 HEALTHCHECK 가 쓴다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r -g 101 assistant \
 && useradd -r -u 100 -g 101 -M -s /usr/sbin/nologin assistant

COPY --from=builder /app/build/libs/*.jar app.jar

USER assistant:assistant

EXPOSE 8080

# The home server runs close to its memory limit, so the heap follows the container limit rather
# than the host's total RAM. Without this the JVM sizes itself against all 14 GiB.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
