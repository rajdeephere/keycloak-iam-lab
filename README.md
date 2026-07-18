# Keycloak IAM Lab

A hands-on lab building a microservice system secured by **Keycloak** — OAuth2/OIDC
flows, Spring Boot resource servers, service-to-service auth, identity lifecycle,
and the operational nice-to-haves (custom SPIs, LDAP/AD, Kubernetes, CI/CD).

Built **learn-by-doing**: every module ends with something running you can demo,
and ships a design doc explaining the *why* and the architectural trade-offs.

📋 **Full day-by-day execution plan:** [docs/ROADMAP.md](docs/ROADMAP.md)

## Prerequisites
- Java 21, Maven 3.9+, Docker + Compose, Git, kubectl

## Core project — a microservice system secured by Keycloak
| # | Module | Focus | Status |
|---|--------|-------|--------|
| 1 | Keycloak + realm bootstrap | Keycloak config, IAM concepts | ✅ done — [docs](docs/module-01-keycloak-bootstrap.md) |
| 2 | `product-service` resource server | Spring Boot, REST APIs, OIDC | ✅ done — [docs](docs/module-02-resource-server.md) |
| 3 | OAuth2/OIDC flows by hand | OAuth2/OIDC flows | todo |
| 4 | Gateway + service-to-service auth | Microservices, IAM integration | todo |
| 5 | Identity lifecycle via Admin API | Access management | todo |

## Side labs (nice-to-haves)
- **A** — Custom SPI: write one, then remove/replace it
- **B** — LDAP/AD federation
- **C** — Kubernetes deployment
- **D** — CI/CD pipeline

## Layout
```
docker/           local infra (Keycloak, Postgres)
realm-config/     exported/importable realm definitions
services/         Spring Boot microservices
labs/             standalone side labs
docs/             design docs, architecture decisions & Q&A per module
postman/          Postman collection + environment (grows each module)
```

## Postman
Import both files, then select the **Keycloak Local** environment:
- `postman/keycloak-interview-prep.postman_collection.json`
- `postman/keycloak-local.postman_environment.json`

Token requests auto-save the `access_token` into the environment, so downstream
requests reuse it. A new folder is added per module.

## Ports
| Service | URL | Notes |
|---------|-----|-------|
| Keycloak | http://localhost:8085 | 8080 is taken by a local Apache, so Keycloak is published on 8085 |
| product-service | http://localhost:8081 | Spring Boot resource server |
| Postgres | localhost:5432 | Keycloak's database |

## Running the infra
```bash
cd docker
docker compose up -d       # start Keycloak + Postgres
# Admin console: http://localhost:8085  (admin / admin)
docker compose down        # stop
docker compose down -v     # stop and wipe data

# Run the resource server
cd ../services/product-service
mvn clean package -DskipTests && java -jar target/product-service-0.0.1-SNAPSHOT.jar
```
