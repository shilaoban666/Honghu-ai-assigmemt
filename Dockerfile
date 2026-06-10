# syntax=docker/dockerfile:1

# ============================================================
# Stage 1 — Build the Spring Boot fat jar with Maven + JDK 17
# ============================================================
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Resolve dependencies first so this layer is cached across source-only changes.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline -DskipTests || true

# Compile and package (tests run in CI, not in the image build).
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# ============================================================
# Stage 2 — Minimal JRE runtime
# ============================================================
FROM eclipse-temurin:17-jre
WORKDIR /app

# Run as a non-root user.
RUN groupadd --system honghu && useradd --system --gid honghu honghu

COPY --from=build /build/target/*.jar app.jar
RUN chown -R honghu:honghu /app
USER honghu

EXPOSE 8080

# Container-aware heap sizing; override JAVA_OPTS at runtime if needed.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

# Honour SQS-off by default; full env is supplied by docker-compose / .env.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
