# syntax=docker/dockerfile:1

# --- Build stage: compile and package the Spring Boot fat jar ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Cache Maven dependencies: copy wrapper + pom first, resolve, then copy sources.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q clean package -DskipTests

# --- Runtime stage: slim JRE with just the jar ---
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user.
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring

COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
