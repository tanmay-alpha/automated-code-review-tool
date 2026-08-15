# syntax=docker/dockerfile:1
# Build stage — compile and package
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Cache Maven dependencies
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=5 dependency:go-offline

# Compile and test
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=5 \
    -DskipTests=false -DexcludedGroups=postgres package

# Runtime stage — minimal JRE image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Install wget for the health check
RUN apk add --no-cache wget

# Create non-root user
RUN addgroup -S automated-code-review-tool && adduser -S automated-code-review-tool -G automated-code-review-tool

# Copy the built jar and set ownership
COPY --from=build /app/target/automated-code-review-tool-api.jar app.jar
RUN chown -R automated-code-review-tool:automated-code-review-tool /app
RUN chmod 500 /app/app.jar

USER automated-code-review-tool
EXPOSE 8080

# Health check must come BEFORE entrypoint
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Production-ready JVM settings
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
