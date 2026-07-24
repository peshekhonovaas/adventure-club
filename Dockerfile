# syntax=docker/dockerfile:1

# --- Frontend stage: build the React app with Vite ---
FROM node:22-alpine AS frontend
WORKDIR /frontend

# Install deps first (cached until the lockfile changes), then build.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
# vite.config.js emits to ../src/main/resources/static, which from this WORKDIR
# resolves to /src/main/resources/static.
RUN npm run build

# --- Build stage: compile and package the Spring Boot fat jar ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Cache Maven dependencies: copy wrapper + pom first, resolve, then copy sources.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Drop the built React app into the static folder so it's packaged into the jar.
COPY --from=frontend /src/main/resources/static/ src/main/resources/static/
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