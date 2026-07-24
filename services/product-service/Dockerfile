# --- Build stage: compile + package with Maven ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Cache dependencies first (only re-downloads when pom.xml changes)
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# --- Runtime stage: slim JRE + the jar ---
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8081
# Wait for Keycloak's realm discovery to be reachable before booting Spring,
# since the resource server resolves the issuer config at startup.
ENTRYPOINT ["sh", "-c", "echo 'waiting for Keycloak at '$KEYCLOAK_ISSUER_URI; until curl -sf \"$KEYCLOAK_ISSUER_URI/.well-known/openid-configuration\" > /dev/null; do sleep 3; done; echo 'Keycloak ready, starting product-service'; exec java -jar app.jar"]
