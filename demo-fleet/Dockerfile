# Build context is the repository root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# POMs first so dependency resolution stays cached across source changes.
COPY pom.xml .
COPY sentinel-platform/pom.xml sentinel-platform/pom.xml
COPY demo-fleet/pom.xml demo-fleet/pom.xml
# Listed in the parent's <modules>, so Maven loads it even under -pl. Omitting it fails
# the reactor before a single source file is compiled.
COPY synthetic-exporter/pom.xml synthetic-exporter/pom.xml
RUN mvn -B -pl demo-fleet -am dependency:go-offline -DskipTests -q || true

COPY demo-fleet/src demo-fleet/src
RUN mvn -B -pl demo-fleet -am package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /build/demo-fleet/target/demo-fleet-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
