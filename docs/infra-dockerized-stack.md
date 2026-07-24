# Infrastructure — Implementation, War Stories & Fixes

**JD skills proven:** Docker · microservices · application integration with IAM · debugging
**Status:** ✅ `docker compose up -d --build` runs the whole system; verified end to end

This is the authoritative infra doc: how the stack is wired, **why `host.docker.internal`**,
how to implement it, and every real bug hit along the way with its fix.

---

## 1. The stack

```
docker compose up -d --build
┌────────────┐   ┌──────────────┐   ┌───────────────────┐   ┌──────────────┐
│ postgres   │◄──│ keycloak     │◄──│ product-service   │   │ web-client   │
│ :5432      │   │ :8085->8080  │   │ :8081 (resource)  │◄──│ :8082 (OIDC) │
└────────────┘   └──────────────┘   └───────────────────┘   └──────────────┘
       DB            IdP                validates JWTs         logs in users
                                                              calls product-svc
```

Four containers on one Docker network. Postgres backs Keycloak; the two Spring Boot
services trust/consume tokens from Keycloak.

---

## 2. Docker networking in one minute (the concept behind everything here)

Each container runs in its **own network namespace**. Consequences:

- **`localhost` inside a container means *that container*** — not your laptop, not another
  container. So a service in a container can't reach "localhost:8085" and find Keycloak.
- Containers on the same compose network reach each other by **service name** via Docker's
  embedded DNS: `product-service` → `keycloak:8080` resolves to the container IP
  (`172.19.0.3` here).
- Your **browser runs on the host**, *outside* Docker. It reaches containers only through
  **published ports** (`ports:` mappings), i.e. `localhost:<hostPort>`. It cannot resolve
  Docker service names like `keycloak`.

So there are three vantage points — host browser, container-to-container, and host tools
(curl) — and they address the same services by **different names**. That mismatch is the
root of the big war story (§5).

---

## 3. `host.docker.internal` — what, why, how

### What it is
A special DNS name Docker provides that resolves, *from inside a container*, to the
**host machine's gateway**. Verified inside our container:

```
getent hosts host.docker.internal   ->  192.168.65.254   host.docker.internal   (the host)
getent hosts keycloak               ->  172.19.0.3       keycloak               (a container)
```

`192.168.65.254` is Docker Desktop's host-gateway address; traffic to it lands on the
host, so `host.docker.internal:8085` reaches Keycloak's **published** port (a "hairpin":
container → host → back into the published container port). Both routes work:

```
curl http://keycloak:8080/...              -> 200   (internal Docker network, direct)
curl http://host.docker.internal:8085/...  -> 200   (out to the host gateway, hairpin)
```

### Why we need it (not just the service name)
Because the **browser is a participant** in the OIDC flow — it gets redirected to
Keycloak's login page. The browser can't resolve `keycloak` (that name only exists inside
Docker). The only name that resolves to the *same Keycloak* from **both** the host browser
**and** the containers is `host.docker.internal`. One name → one URL → one token issuer
(§5). Using the internal `keycloak:8080` for services would give them a *different* issuer
than the browser's, and validation would fail.

### How to implement it
1. **Make the name resolvable inside each container.** On Docker Desktop (Win/Mac) it's
   often present automatically, but declaring it is explicit and portable:
   ```yaml
   services:
     product-service:
       extra_hosts:
         - "host.docker.internal:host-gateway"   # Docker maps host-gateway to the host
   ```
   `host-gateway` is a Docker-provided magic value; Docker substitutes the real host IP.
2. **Point config at it:**
   ```yaml
   keycloak:
     environment:
       KC_HOSTNAME: http://host.docker.internal:8085   # pins the public/issuer URL
   product-service:
     environment:
       KEYCLOAK_ISSUER_URI: http://host.docker.internal:8085/realms/demo
   ```

### Platform note
- **Docker Desktop (Windows/macOS — this machine):** `host.docker.internal` works; from
  the host it also resolves (Docker Desktop wires it up), which is why the browser can use it.
- **Plain Docker on Linux:** not automatic. The `extra_hosts: host-gateway` line above is
  what makes it work there; older setups used the `docker0` bridge IP (`172.17.0.1`).
- **Production / Kubernetes:** this trick disappears — you use a *real DNS hostname* for
  Keycloak that browsers and in-cluster services share (see §7).

---

## 4. Implementation walkthrough

### 4a. Multi-stage Dockerfiles (both services)
```dockerfile
# build stage: full Maven toolchain
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline      # cache deps in their own layer
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# runtime stage: slim JRE + jar only
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8081
# wait for Keycloak discovery, THEN boot (see §5 startup race)
ENTRYPOINT ["sh","-c","until curl -sf \"$KEYCLOAK_ISSUER_URI/.well-known/openid-configuration\" >/dev/null; do sleep 3; done; exec java -jar app.jar"]
```
Why: build tools never ship to runtime (smaller, fewer CVEs); deps are layer-cached so
code-only changes rebuild fast.

### 4b. Compose wiring (the important env)
```yaml
keycloak:
  environment:
    KC_HOSTNAME: http://host.docker.internal:8085   # <-- single canonical issuer
    KC_HTTP_ENABLED: "true"
    KC_HOSTNAME_STRICT: "false"
  extra_hosts: ["host.docker.internal:host-gateway"]

product-service:
  build: ../services/product-service
  environment:
    KEYCLOAK_ISSUER_URI: http://host.docker.internal:8085/realms/demo
  extra_hosts: ["host.docker.internal:host-gateway"]
  depends_on: [keycloak]

web-client:
  build: ../services/web-client
  environment:
    KEYCLOAK_ISSUER_URI: http://host.docker.internal:8085/realms/demo
    PRODUCT_SERVICE_URL: http://product-service:8081   # internal service-to-service
  extra_hosts: ["host.docker.internal:host-gateway"]
  depends_on: [keycloak, product-service]
```

### 4c. Env-configurable services (one jar, two run modes)
```yaml
# application.yml (product-service)
issuer-uri: ${KEYCLOAK_ISSUER_URI:http://host.docker.internal:8085/realms/demo}
```
Compose sets the env; the default lets the same jar run on the host for fast iteration.

Note the **asymmetry**: `web-client` calls `product-service` by the internal name
(`product-service:8081`) because that's pure container-to-container traffic, but talks to
Keycloak via `host.docker.internal:8085` so its tokens share the browser's issuer.

---

## 5. War stories & fixes (chronological — the real debugging)

### WS-1 — Port 8080: Keycloak "worked, then randomly 404'd"
- **Symptom:** discovery returned 200 once, then later curl got `404` with `Server: Apache`.
  The Spring resource server crashed at boot: *"Unable to resolve the Configuration with the
  provided Issuer."*
- **Debug:** `netstat -ano | grep :8080` showed **three** listeners; one was `httpd.exe`.
  A local **Apache (XAMPP/WAMP)** already owned 8080, and connections raced between it and
  Docker's published Keycloak port → nondeterministic responses.
- **Fix:** publish Keycloak on **8085** (`8085:8080`), leaving Apache alone.
- **Lesson:** when two processes bind the same port, routing is undefined. `netstat` the
  port before assuming your process owns it.

### WS-2 — curl said 200, the JVM said 404 (same URL)
- **Symptom:** `curl` got Keycloak JSON; a tiny Java `HttpClient` probe got a 404 Apache
  HTML page — same URL, same host.
- **Debug:** this *disagreement* was the clue — two different servers were answering,
  confirming WS-1's port collision. Isolating the raw bytes (a minimal client + netstat)
  beat trusting one tool.
- **Fix:** same as WS-1 (move off 8080).
- **Red herring ruled out:** initially suspected IPv4/IPv6 (`localhost`→`::1`); forcing
  `-Djava.net.preferIPv4Stack=true` changed nothing, which *disproved* that theory and
  pushed us to netstat.

### WS-3 — Issuer mismatch after containerizing (**the big one**)
- **Symptom:** once services ran *in containers*, tokens were rejected — issuer wouldn't
  validate. Browser-minted tokens said `iss: localhost:8085`; a container validating against
  `keycloak:8080` (or vice-versa) saw a different issuer.
- **Root cause:** a JWT's `iss` is derived from the URL used to reach Keycloak, and the
  browser, containers, and host tools all address Keycloak by **different names** (§2).
- **Fix:** pin `KC_HOSTNAME: http://host.docker.internal:8085` so Keycloak **always** stamps
  `iss: http://host.docker.internal:8085/realms/demo`, regardless of entry path — and make
  every service validate that one issuer, reaching it via `host.docker.internal` (§3).
- **Verified:** discovery `issuer = http://host.docker.internal:8085/realms/demo`; a headless
  browser login through the dockerized `web-client` successfully called the dockerized
  `product-service`.
- **Lesson:** an IdP needs **one canonical URL** shared by every participant *including the
  browser*. This is the #1 gotcha integrating apps with Keycloak behind any proxy/NAT/container.

### WS-4 — Boot-order race: services died before Keycloak was ready
- **Symptom:** services failed at startup resolving the issuer, because Spring fetches OIDC
  metadata **at boot** and Keycloak wasn't serving yet.
- **Why `depends_on` isn't enough:** plain `depends_on` waits for the container to *start*,
  not for the app inside to be *ready*.
- **Fix:** the entrypoint polls `"$KEYCLOAK_ISSUER_URI/.well-known/openid-configuration"`
  until 200, then `exec java -jar`. (Alternative: a Keycloak healthcheck + `condition:
  service_healthy`, but the KC image lacks curl, so an app-side wait was simpler.)

### WS-5 — Build & test papercuts
- **Maven offline:** an early build used `-o` (offline) before deps were cached →
  "Cannot access central in offline mode." Fix: build online first (`mvn` without `-o`).
- **Shell JSON escaping:** a `POST` test sent `-d '{\"name\":...}'` in single quotes →
  literal backslashes → `400` (bad JSON) *before* the security check, masking the real
  `403`. Fix: send the body from a file (`--data @body.json`). Reminder that a 400 can
  pre-empt the authz result you're trying to observe.

---

## 6. Alternatives considered (and why not)

| Approach | Why rejected |
|----------|--------------|
| Use `keycloak:8080` everywhere | The browser can't resolve Docker service names — login redirect would break. |
| Keep `localhost:8085` everywhere | Containers can't reach the host's `localhost`; only `network_mode: host` would, and that's unsupported on Docker Desktop. |
| Edit the host's `hosts` file to add a shared name | Requires admin, non-portable, easy to forget — worse than a Docker-native name. |
| Split front/back-channel URLs (`KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`) | Works, but more moving parts; a single shared hostname is simpler and closer to prod. |

---

## 7. How this maps to production / Kubernetes

- Keycloak gets a **real public DNS name + TLS**: `KC_HOSTNAME=https://sso.example.com`,
  used by browsers *and* in-cluster services (that name resolves both externally and via
  cluster DNS / split-horizon). `host.docker.internal` is a dev-only stand-in for "the one
  hostname everyone shares."
- If internal and external URLs genuinely differ, use `KC_HOSTNAME_BACKCHANNEL_DYNAMIC`.
- Startup waits become **readiness/liveness probes**; `depends_on` becomes init-containers
  or app-level retry.
- Secrets come from a vault/Secret, not env literals; images pinned by digest. (Lab C.)

---

## 8. Interview Q&A

**Q: Why `host.docker.internal` instead of the container name `keycloak`?**
Because the browser participates in OIDC and can't resolve Docker service names. Only
`host.docker.internal` resolves to the same Keycloak from both the host browser and the
containers, giving one consistent token issuer.

**Q: What does `host.docker.internal` actually resolve to?**
The host's gateway IP as seen from the container (here `192.168.65.254` on Docker Desktop).
You make it resolvable with `extra_hosts: ["host.docker.internal:host-gateway"]`.

**Q: We containerized an app and OIDC broke with "invalid issuer" — why?**
The token's `iss` comes from the URL used to reach the IdP, and browser vs container use
different addresses. Pin the IdP's frontend hostname to one value everyone shares, and
validate that.

**Q: Why not rely on `depends_on` for ordering?**
It only waits for container *start*, not app *readiness*. Since the resource server
resolves OIDC metadata at boot, we poll the discovery endpoint until it answers.

**Q: Why multi-stage Docker builds?**
Keep the Maven toolchain out of the runtime image (smaller, fewer CVEs) and layer-cache
dependencies for fast rebuilds.

**Q: A container can reach Keycloak two ways — which do you use and why?**
`keycloak:8080` (direct) is fine for pure back-channel calls, but I route token-related
traffic through `host.docker.internal:8085` so the issuer matches the browser's. Consistency
of `iss` wins over the marginally more direct path.

---

## 9. Commands & troubleshooting cheat sheet

```bash
# Bring up / tear down
cd docker
docker compose up -d --build
docker compose ps
docker compose logs -f web-client
docker compose down            # keep data
docker compose down -v         # wipe DB -> realm re-imports on next up

# Who owns a port? (WS-1)
netstat -ano | grep ":8080 " | grep LISTEN     # then: tasklist | findstr <pid>

# What does a name resolve to inside a container? (§3)
docker compose exec product-service getent hosts host.docker.internal
docker compose exec product-service getent hosts keycloak

# Prove reachability from inside a container
docker compose exec product-service curl -s -o /dev/null -w '%{http_code}\n' \
  http://host.docker.internal:8085/realms/demo/.well-known/openid-configuration

# Confirm the canonical issuer
curl -s http://localhost:8085/realms/demo/.well-known/openid-configuration \
  | python -c "import sys,json;print(json.load(sys.stdin)['issuer'])"
# -> http://host.docker.internal:8085/realms/demo
```

---

## 10. What I can demo live
1. `docker compose up -d --build` → a full IAM-secured microservice system from nothing.
2. `docker compose exec product-service getent hosts host.docker.internal keycloak` →
   show the two addresses and explain the browser-vs-container split.
3. Browser login at `http://localhost:8082` → `/products`, then explain how one canonical
   issuer makes the token valid across the network boundary.
