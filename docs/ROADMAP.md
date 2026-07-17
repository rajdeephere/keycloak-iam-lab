# Execution Roadmap — Keycloak IAM Lab

A day-by-day execution + implementation plan across ~2 weeks (10 working days,
~4–6 focused hours/day). Each day is time-blocked and ends with **three
deliverables**: running code, a design doc (the *why* + trade-offs), and an
updated Postman folder. Adjust pace to your schedule — the sequence matters more
than the calendar.

**Legend:** 🔨 build · 📝 document · 📮 Postman · 🎤 interview checkpoint · ✅ done

---

## Week 1 — Core project (the JD must-haves)

### Day 1 — Keycloak + realm bootstrap ✅ (done)
**Goal:** a production-shaped IdP running as code.
- 🔨 Docker Compose: Keycloak 26.1 + Postgres 16
- 🔨 Realm-as-code: `demo` realm, 3 client archetypes, roles, users
- 📝 `docs/module-01-keycloak-bootstrap.md`
- 📮 Module 1 folder (discovery, JWKS, client-credentials token)
- 🎤 Explain realm vs client, public vs confidential, bearer-only, the `aud` gotcha

### Day 2 — `product-service` resource server (Module 2)
**Goal:** a Spring Boot REST API that validates Keycloak JWTs and enforces roles.
- **AM (build)**
  - 🔨 `services/product-service`: Spring Boot 3 + `spring-boot-starter-oauth2-resource-server`
  - 🔨 Configure `issuer-uri`; expose `GET /api/products`, `POST /api/products`
  - 🔨 Convert realm roles → Spring authorities (custom `JwtAuthenticationConverter`)
  - 🔨 Method security: `@PreAuthorize("hasRole('app_admin')")` on writes
- **PM (verify + fix + doc)**
  - 🔨 Fix the audience gotcha: add an audience mapper/client scope; add `JwtValidators` audience check
  - 🔨 Prove 401 (no token), 403 (alice → admin route), 200 (bob → admin route)
  - 📝 `docs/module-02-resource-server.md` — resource-server vs client, JWT validation chain (sig/iss/exp/aud), roles→authorities, stateless security
  - 📮 add `GET/POST /api/products` with bearer token
- 🎤 "How does the API trust a token without calling Keycloak?" (JWKS + local verify)

### Day 3 — OAuth2 / OIDC flows by hand (Module 3)
**Goal:** deeply understand every flow by executing it raw.
- **AM**
  - 🔨 Authorization Code + PKCE end-to-end with `web-app` (generate verifier/challenge, hit `/auth`, exchange `/token`)
  - 🔨 Inspect ID token vs access token vs refresh token — what each is *for*
  - 🔨 Refresh token grant; token revocation; `/userinfo`; end-session/logout
- **PM**
  - 🔨 Token introspection (`/token/introspect`) with `backend-worker`
  - 📝 `docs/module-03-oauth2-oidc-flows.md` — flow-by-flow with sequence diagrams, when to use each, PKCE math, OAuth2 (authz) vs OIDC (authn)
  - 📮 add PKCE flow requests + refresh + introspect + logout
- 🎤 "Walk me through Authorization Code + PKCE step by step." / "OAuth2 vs OIDC?"

### Day 4 — API Gateway + service-to-service auth (Module 4)
**Goal:** a microservice topology with token propagation.
- **AM**
  - 🔨 `services/gateway` (Spring Cloud Gateway) as the single entry point; route to `product-service`
  - 🔨 A second service `order-service` that calls `product-service`
- **PM**
  - 🔨 Token relay (propagate the bearer downstream) + client-credentials for pure M2M hops
  - 🔨 Scope/role-based authorization at the gateway edge
  - 📝 `docs/module-04-gateway-service-to-service.md` — edge vs in-service authz, token relay vs client-credentials, audience per service, defense in depth
  - 📮 add gateway routes + order→product call
- 🎤 "How do you secure service-to-service calls?" / "Where do you enforce authz — edge or service?"

### Day 5 — Identity lifecycle via Admin API (Module 5) + Week-1 consolidation
**Goal:** programmatic user/identity management.
- **AM**
  - 🔨 Use Keycloak Admin REST API (get admin token → CRUD users, assign roles, reset password, enable/disable, force logout/sessions)
  - 🔨 Optional: a small `admin-ops` CLI or REST controller wrapping these
- **PM**
  - 📝 `docs/module-05-identity-lifecycle.md` — joiner/mover/leaver, provisioning/deprovisioning, sessions & token revocation, SCIM concept
  - 📮 add Admin API folder (get admin token, create user, assign role, disable user)
  - 🎤 Mock round: run through the Week-1 Q&A banks out loud
- 🎤 "Describe the identity lifecycle." / "How would you deprovision a user immediately?"

---

## Week 2 — Nice-to-haves + polish (differentiators)

### Day 6 — Custom SPI: write one (Lab A, part 1)
**Goal:** extend Keycloak with custom Java — the headline nice-to-have.
- 🔨 `labs/spi-custom`: a custom **Event Listener SPI** (or a custom Authenticator) packaged as a JAR
- 🔨 Deploy the provider into the Keycloak container (providers dir + build), see it fire
- 📝 `docs/lab-a-spi.md` (part 1) — the SPI/provider-factory model, deployment mechanics, when a custom SPI is justified
- 🎤 "What's an SPI? Give an example where you'd write one."

### Day 7 — Custom SPI: remove/replace it (Lab A, part 2) ⭐ JD-specific
**Goal:** the exact JD phrase — "removing/replacing custom SPI logic."
- 🔨 Replace the custom SPI with a built-in feature (or a cleaner provider); safely un-deploy the old JAR
- 🔨 Migration checklist: config that referenced it, realm settings, roll-back plan
- 📝 `docs/lab-a-spi.md` (part 2) — why teams remove custom SPIs (upgrade friction, maintenance), safe migration/rollback strategy
- 🎤 "How would you remove a legacy custom SPI without breaking login?" (this maps directly to the JD)

### Day 8 — LDAP / AD federation (Lab B)
**Goal:** federate an external directory.
- 🔨 `labs/ldap`: OpenLDAP container seeded with users; add User Federation provider in Keycloak
- 🔨 Map LDAP attributes → Keycloak; group→role mapping; sync modes (import vs read-only)
- 📝 `docs/lab-b-ldap.md` — federation vs brokering, sync strategies, AD specifics (Kerberos/LDAPS), fail-over
- 📮 add "login as an LDAP-federated user" request
- 🎤 "How does Keycloak integrate with corporate AD?"

### Day 9 — Kubernetes deployment (Lab C)
**Goal:** run the stack on a cluster.
- 🔨 `labs/k8s`: manifests (or Helm) for Keycloak + Postgres + `product-service` on kind/minikube/Docker Desktop k8s
- 🔨 Ingress, secrets, readiness/liveness probes, `KC_HOSTNAME` + proxy headers
- 📝 `docs/lab-c-kubernetes.md` — production mode vs dev, statefulset/DB, HA/clustering notes, secret management
- 🎤 "How would you run Keycloak in production on Kubernetes?"

### Day 10 — CI/CD pipeline (Lab D) + final polish
**Goal:** automate build/test and finish the portfolio.
- **AM**
  - 🔨 `.github/workflows/ci.yml`: build all services, run tests, build Docker images, (optional) spin Keycloak via Testcontainers for an integration test
- **PM**
  - 📝 `docs/lab-d-cicd.md` — pipeline stages, testing IAM code, image scanning
  - 📝 Polish top-level `README.md`: architecture diagram, screenshots/GIF, "what this demonstrates" section
  - 🎤 Full mock interview: cover the whole JD end to end from the docs
- 🎤 Final: be able to `docker compose up` and narrate the entire system in 10 minutes

---

## Daily ritual (repeat every day)
1. **Start:** re-read yesterday's doc's Q&A out loud (spaced repetition).
2. **Build:** implement the day's module; commit in small, labeled steps.
3. **Verify:** prove it works with curl/Postman before documenting.
4. **Document:** write the design doc — always answer *why* and *what did I trade off*.
5. **Postman:** add/refresh the module's folder.
6. **Checkpoint:** answer the day's 🎤 questions without looking.

## Definition of done (per module)
- [ ] Code runs from a clean `docker compose up` / `mvn spring-boot:run`
- [ ] Design doc committed with architecture decisions + Q&A
- [ ] Postman folder added and passing
- [ ] Can demo + explain it in under 5 minutes

## The one-sentence pitch (rehearse this)
> "I built a Keycloak-secured microservice system from scratch — OAuth2/OIDC flows,
> Spring Boot resource servers with role-based authz, an API gateway with token relay,
> programmatic identity lifecycle via the Admin API, a custom SPI I then migrated off,
> plus LDAP federation, Kubernetes deployment, and a CI/CD pipeline — all as code."
