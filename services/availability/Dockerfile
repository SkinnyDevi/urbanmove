# Imagen del servicio availability. Se construye desde la carpeta services/ (contexto),
# porque necesita el pom padre:  docker build -f availability/Dockerfile services/
#
# Los tests NO se ejecutan aquí: necesitan Docker (Testcontainers) y los ejecuta el CI.

# --- 1. Compilación -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Primero solo los pom, para que Docker reutilice la capa de dependencias
# mientras no cambien (las compilaciones siguientes tardan segundos).
COPY pom.xml ./
COPY availability/pom.xml availability/
RUN mvn -f availability/pom.xml -q -B dependency:go-offline

COPY availability/src availability/src
RUN mvn -f availability/pom.xml -q -B -DskipTests package

# --- 2. Ejecución -------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/availability/target/availability.jar app.jar
USER app

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/health > /dev/null || exit 1

# 75 % de la memoria del contenedor para el heap (Fargate: 512 MB, AGENTS.md 8.3)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
