# Build context is the repository root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# POMs first so dependency resolution stays cached across source changes.
COPY pom.xml .
COPY sentinel-platform/pom.xml sentinel-platform/pom.xml
COPY demo-fleet/pom.xml demo-fleet/pom.xml
COPY synthetic-exporter/pom.xml synthetic-exporter/pom.xml
RUN mvn -B -pl synthetic-exporter -am dependency:go-offline -DskipTests -q || true

COPY synthetic-exporter/src synthetic-exporter/src
RUN mvn -B -pl synthetic-exporter -am package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /build/synthetic-exporter/target/synthetic-exporter-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
