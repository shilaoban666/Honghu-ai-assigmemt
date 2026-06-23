# ============================================================
# Stage 1 — Build the Spring Boot fat jar with Maven + JDK 17
# ============================================================
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Use Aliyun mirror for faster downloads in China.
COPY .mvn/maven-settings.xml /root/.m2/settings.xml

# Resolve dependencies first — uses a BuildKit cache mount so .m2 survives rebuilds.
# The cache is named "m2-repo" and shared across builds of this project.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2,id=m2-repo \
    mvn -B -ntp dependency:go-offline -DskipTests

# Compile and package (tests run in CI, not in the image build).
COPY src ./src
RUN --mount=type=cache,target=/root/.m2,id=m2-repo \
    mvn -B -ntp clean package -DskipTests

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
