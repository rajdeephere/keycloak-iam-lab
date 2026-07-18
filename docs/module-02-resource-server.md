# Module 2 — `product-service` Resource Server

**JD skills proven:** Spring Boot · REST APIs & microservices · OIDC · Application integration with IAM
**Status:** ✅ Built, running on `:8081`, all auth scenarios verified

---

## 1. What I built

A Spring Boot 3 REST API (`product-service`) that is a pure **OAuth2 Resource Server**:
it never logs anyone in — it only *validates* the JWT access tokens Keycloak issues,
turns Keycloak realm roles into Spring authorities, and enforces role-based access per endpoint.

```
Client ──(Bearer JWT)──►  product-service :8081
                          │
                          │ 1. verify signature via Keycloak JWKS (cached)
                          │ 2. validate issuer + expiry (default)
                          │ 3. validate audience == product-service (custom)
                          │ 4. realm_access.roles ──► ROLE_* authorities
                          ▼
                   @PreAuthorize on the method decides 200 / 403
```

**Endpoints**
| Method | Path | Rule |
|--------|------|------|
| GET | `/api/products` | `hasAnyRole('app_user','app_admin')` |
| POST | `/api/products` | `hasRole('app_admin')` |
| GET | `/api/products/whoami` | `isAuthenticated()` |
| GET | `/actuator/health` | public |

**Verified matrix**
| Scenario | Result |
|----------|--------|
| No token | **401** |
| alice (app_user) GET | **200** |
| alice POST (needs admin) | **403** |
| bob (app_admin) POST | **200** |
| Garbage token | **401** |
| Token without `aud: product-service` | rejected by AudienceValidator |

---

## 2. Key architectural decisions (with *why*)

### D1 — Resource Server, not a Client
- **Why:** this service exposes an API; it doesn't drive a login. So it needs zero OAuth *flow* — no redirect URIs, no client secret, no session. It just needs to *trust tokens*. That's exactly `spring-boot-starter-oauth2-resource-server`.
- Mirrors the `bearer-only` client from Module 1: the security model is "validate, don't authenticate."

### D2 — Validate JWTs **locally** via JWKS (not introspection)
- **Why:** the starter fetches Keycloak's public keys once from the JWKS endpoint and verifies every token's RS256 signature **in-process**. No network call to Keycloak per request → low latency, no hard runtime dependency on the IdP being reachable on the hot path.
- **Trade-off:** a token stays valid until it expires even if revoked. Mitigations: short token lifespan (we set 5 min in Module 1) or switch to **introspection** when instant revocation matters. That's a deliberate latency-vs-freshness call.

### D3 — `issuer-uri` (discovery) instead of hard-coding `jwk-set-uri`
- **Why:** pointing at the issuer lets Spring pull the whole OIDC discovery document — JWKS URI, supported algorithms, issuer value — automatically, and it wires the default issuer + timestamp validators for free. One line of config, and key rotation is handled.
- **Trade-off / gotcha:** discovery is fetched **at startup**, so the IdP must be reachable when the app boots (this is what surfaced the port conflict — see §5).

### D4 — Custom `KeycloakRealmRoleConverter`
- **Why:** Keycloak puts roles under the non-standard `realm_access.roles` claim. Spring's default converter reads the `scope`/`scp` claim, so **out of the box you get authorities like `SCOPE_email`, not your roles**. The converter remaps `realm_access.roles → ROLE_<name>` so `hasRole('app_admin')` works.
- **`ROLE_` prefix:** `hasRole('X')` implicitly checks authority `ROLE_X`. Emitting the prefix in the converter keeps the controller annotations clean.

### D5 — Method security (`@PreAuthorize`) over URL rules for role checks
- **Why:** authorization lives next to the behavior it protects, is unit-testable, and survives refactors of URL structure. The `HttpSecurity` layer does the coarse gate (`authenticated()`); the method layer does the fine-grained role decision.
- **Trade-off:** two places to look. Acceptable — coarse at the edge, fine at the method is a common, defensible layering.

### D6 — Stateless + CSRF disabled
- **Why:** a token-secured REST API keeps no server session (`SessionCreationPolicy.STATELESS`) — every request carries its own proof (the JWT). With no session cookie, there's no CSRF vector, so CSRF protection is turned off. Enabling it on a stateless API just breaks POSTs for no security gain.

### D7 — Audience validation added on top (defense in depth)
- **Why:** see §4. Spring validates issuer + signature + expiry, **not audience**. We add it.

---

## 3. The JWT validation chain (say this precisely)

A token is accepted only if **all** pass:
1. **Signature** — RS256, verified against the Keycloak public key from JWKS. Proves Keycloak minted it and it wasn't tampered with.
2. **Issuer** (`iss`) — must equal our configured issuer. Proves it came from *our* realm.
3. **Expiry / not-before** (`exp`, `nbf`) — proves it's currently valid.
4. **Audience** (`aud`) — must contain `product-service`. Proves the token was *meant for us*. ← custom.

Only then does role-based authorization (`@PreAuthorize`) run.

---

## 4. Fixing the `aud` gotcha (carried over from Module 1)

**Problem:** by default Keycloak stamps `aud: account`, not your service. A strict resource server would reject every token — or worse, a lax one accepts tokens minted for a totally different service.

**Fix (two halves):**
- **Keycloak side:** added an *Audience protocol mapper* to `web-app` and `backend-worker` so their access tokens carry `aud: product-service`. (Verified: `whoami` shows `"audience": ["product-service"]`.)
- **Spring side:** `AudienceValidator` (an `OAuth2TokenValidator<Jwt>`) composed with the default validators via `DelegatingOAuth2TokenValidator`, so audience is enforced alongside issuer/expiry.

**Interview line:** *"Signature proves the token is authentic; audience proves it was intended for this service. Skipping audience validation is how a token minted for service A gets replayed against service B."*

---

## 5. War story: the port conflict (great "how do you debug" answer)

**Symptom:** app crashed at startup — `Unable to resolve the Configuration with the provided Issuer`. But `curl` to the discovery URL returned 200.

**Debug path:**
1. Ruled out proxy (`ProxySelector` was `DIRECT`, no proxy env).
2. Wrote a 10-line Java `HttpClient` probe → it got **HTTP 404 with `Server: Apache`**, while curl got 200 JSON from Keycloak.
3. `netstat -ano` on `:8080` showed **three** listeners; one was `httpd.exe` — a local **Apache/XAMPP** already on 8080. Connections to 8080 were racing between Apache and Docker's published Keycloak port.

**Fix:** republished Keycloak on host port **8085** (`8085:8080`), leaving Apache alone. Updated `issuer-uri` accordingly. Keycloak's issuer is derived from the request host, so it became `http://localhost:8085/realms/demo` automatically.

**Lesson to state:** *"When two things claim the same port, behavior is nondeterministic. Isolate the actual bytes on the wire (a raw client + netstat) instead of trusting one tool — curl and the JVM disagreed, and that disagreement was the clue."*

---

## 6. Interview Q&A

**Q: Resource Server vs Client vs Authorization Server?**
Authorization Server = Keycloak (issues tokens). Client = the app that *obtains* a token (web-app). Resource Server = the API that *validates* a token and serves protected resources (product-service). One app can be both a client and a resource server, but separating concerns keeps the model clear.

**Q: How does the API validate a token without calling Keycloak each time?**
It downloads Keycloak's public signing keys from the JWKS endpoint once (and refreshes on rotation), then verifies each JWT's signature locally. Plus issuer/expiry/audience checks — all in-process.

**Q: JWKS vs introspection — when would you use introspection?**
JWKS = fast, offline, stateless, but can't detect revocation before expiry. Introspection = a call to Keycloak per token, so it reflects revocation instantly but adds latency and couples you to IdP availability. Use introspection for opaque tokens or when immediate revocation is a hard requirement.

**Q: Why didn't `hasRole('app_admin')` work until you wrote a converter?**
Keycloak stores roles in `realm_access.roles`, but Spring's default authority converter reads the `scope` claim. So roles never became authorities. The custom converter maps `realm_access.roles` to `ROLE_*`.

**Q: Realm role vs client role in the token — where do they land?**
Realm roles → `realm_access.roles`. Client roles → `resource_access.<clientId>.roles`. My converter reads realm roles; I'd extend it to read client roles if I modeled per-service permissions.

**Q: Why stateless? Why disable CSRF?**
The JWT is self-contained proof on every request, so no server session is needed. No session cookie means no CSRF attack surface, so CSRF protection is unnecessary and would only break API clients.

**Q: Where should authorization happen — gateway or service?**
Both, in layers. Coarse checks (is there a valid token? broad scope?) can happen at the gateway; the service still enforces its own fine-grained rules because it must never trust that something upstream did. That's defense in depth (expanded in Module 4).

**Q: What does the `azp` claim mean vs `aud`?**
`azp` (authorized party) = the client the token was issued to. `aud` (audience) = the resource servers the token is intended for. You validate `aud`; you can use `azp` for client-aware logic.

---

## 7. Run it

```bash
# 1. Infra (Keycloak on 8085 + Postgres)
cd docker && docker compose up -d

# 2. Build + run the service
cd ../services/product-service
mvn clean package -DskipTests
java -jar target/product-service-0.0.1-SNAPSHOT.jar     # :8081

# 3. Get a token and call the API
TOKEN=$(curl -s -X POST http://localhost:8085/realms/demo/protocol/openid-connect/token \
  -d grant_type=password -d client_id=web-app -d username=bob -d password=password -d scope=openid \
  | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/products
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/products/whoami
```

(Postman: **Module 2** folder — run a token request, then the API calls.)

---

## 8. What I can demo live
1. Call the API with no token → 401.
2. Get alice's token, GET products → 200; POST → 403.
3. Get bob's token, POST → 200.
4. `whoami` → show `aud: product-service`, issuer, roles pulled from the JWT.
5. Explain the 4-step validation chain and why audience matters.

**Next module:** Module 3 — execute every OAuth2/OIDC flow by hand (Authorization Code + PKCE, refresh, introspection, logout) so I can whiteboard them cold.
