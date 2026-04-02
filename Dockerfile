# ═══════════════════════════════════════════════════════════════════
# Floww Exchange — Multi-stage Docker build
# ═══════════════════════════════════════════════════════════════════

# ── Stage 1: Maven build ──────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src src
RUN mvn package -DskipTests -B

# ── Stage 2: Runtime ──────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S floww && adduser -S floww -G floww

COPY --from=build /app/target/*.jar app.jar

RUN chown -R floww:floww /app
USER floww

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
