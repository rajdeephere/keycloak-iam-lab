# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8083
# Wait for Keycloak discovery (resource server + client both need it at startup).
ENTRYPOINT ["sh", "-c", "until curl -sf \"$KEYCLOAK_ISSUER_URI/.well-known/openid-configuration\" > /dev/null; do echo 'waiting for Keycloak'; sleep 3; done; exec java -jar app.jar"]
