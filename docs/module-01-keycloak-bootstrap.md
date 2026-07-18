# Module 1 — Keycloak + Realm Bootstrap

**JD skills proven:** Keycloak development/configuration · Identity & access management concepts · Docker
**Status:** ✅ Running and verified

---

## 1. What I built

A production-shaped local Keycloak stack:

```
┌─────────────┐        ┌──────────────────────┐
│  Postgres 16 │◄──────│  Keycloak 26.1        │
│  (kc-postgres)│  JDBC │  start-dev            │
└─────────────┘        │  --import-realm       │
                       │  :8080                │
                       └──────────┬────────────┘
                                  │ imports on first start
                                  ▼
                       realm-config/demo-realm.json
                       ├─ realm:   demo
                       ├─ roles:   app_user, app_admin
                       ├─ clients: product-service (bearer-only)
                       │           web-app         (public + PKCE)
                       │           backend-worker  (confidential + service account)
                       └─ users:   alice (app_user), bob (app_user, app_admin)
```

**Verified working:**
- OIDC discovery: `http://localhost:8085/realms/demo/.well-known/openid-configuration`
- Minted a real access token via **client-credentials** grant and decoded the JWT.

---

## 2. Core IAM vocabulary (say these precisely in the interview)

| Term | What it is | Analogy |
|------|-----------|---------|
| **Realm** | Isolated tenant: its own users, clients, roles, keys, tokens. | A separate building with its own security desk. |
| **Client** | An application that talks to Keycloak (asks for or validates tokens). | A door that needs a badge reader. |
| **Client scope** | Reusable bundle of claims/roles mappers shared across clients. | A template for what goes on the badge. |
| **Role** | A named permission. *Realm role* = global; *client role* = scoped to one client. | The clearance level printed on the badge. |
| **User / Service account** | A human identity / a non-human (machine) identity. | Employee vs. a robot with its own badge. |
| **Identity Provider (IdP)** | Who authenticates the user. Keycloak *is* one, and can also broker to others (Google, SAML, AD). | The security desk itself. |

**Realm vs client roles — the "why":** realm roles model organization-wide concepts (`app_admin`); client roles model app-specific permissions (`product-service:read`). Use client roles when the same word means different things in different apps.

---

## 3. Client types — the single most important config decision

Every client's security posture comes down to **can it keep a secret?**

| Client | Type | Flow | Secret? | Why this choice |
|--------|------|------|---------|-----------------|
| `web-app` | **public** | Authorization Code + **PKCE** | ❌ no | A browser SPA can't hide a secret (all JS is visible). PKCE replaces the secret with a per-request dynamic proof. |
| `backend-worker` | **confidential** | Client Credentials | ✅ yes | Server-to-server, no user. It authenticates *as itself* with a secret it can safely store. |
| `product-service` | **bearer-only** | (none) | n/a | It never logs anyone in. It only *validates* incoming tokens. So it initiates no flow at all. |

> **Interview gold:** "Public vs confidential is not about trust — it's about whether the runtime can physically keep a secret. A SPA cannot, so it must use PKCE. A backend can, so it uses client credentials."

`bearerOnly: true` disables both `standardFlow` and `directAccessGrants` — a resource server that accidentally has login flows enabled is a needless attack surface.

---

## 4. Architectural decisions (what I chose and *why*)

### D1 — External Postgres instead of the default H2/dev-file DB
- **Why:** H2 is in-memory/dev-only and loses data. Real Keycloak deployments run against Postgres/MySQL/etc. Wiring Postgres now means the stack mirrors production and I can talk credibly about the DB layer.
- **Trade-off:** slightly heavier local setup (two containers) vs. realism. Worth it.

### D2 — `start-dev` locally, but I know what `start` (production) requires
- **Why:** `start-dev` disables HTTPS enforcement and hostname strictness so I can iterate fast on `localhost`.
- **Production would need:** `start` (optimized build), `KC_HOSTNAME`, real TLS certs, `KC_HOSTNAME_STRICT=true`, `KC_PROXY_HEADERS` if behind a load balancer, and a build-time `kc.sh build` step to bake in providers.
- **Interview line:** "`start-dev` is convenience; the difference is TLS enforcement, hostname strictness, and an optimized pre-built image."

### D3 — Realm-as-code (`--import-realm` from JSON) instead of clicking the UI
- **Why:** the realm is now **reproducible, reviewable, and version-controlled**. Anyone can `docker compose up` and get an identical IdP. This is the GitOps mindset interviewers look for.
- **Trade-off:** import JSON is verbose and drifts if you later edit via UI. In prod you'd manage config with Terraform (`keycloak` provider) or the Admin CLI for true drift control.

### D4 — Keycloak 26+ admin bootstrap via `KC_BOOTSTRAP_ADMIN_*`
- **Why:** the old `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` env vars were renamed in v26. Knowing the current variable names signals hands-on recency with the tool.

### D5 — Short access-token lifespan (5 min), longer SSO session
- **Why:** access tokens are bearer credentials — if leaked, short expiry limits the blast radius. Sessions live longer so users aren't forced to re-login; refresh tokens bridge the gap.

---

## 5. The `aud: account` gotcha (verified in our token)

Our decoded token had `"aud": "account"`, **not** `product-service`. This bites everyone:

- A resource server that validates the **audience** claim will *reject* this token because it isn't the intended recipient.
- **Fix (Module 2 preview):** add an *Audience* protocol mapper (or a client scope) so tokens meant for `product-service` carry `aud: product-service`. Spring Security by default validates issuer + signature + expiry, not audience — but you *should* add audience validation for defense in depth.

---

## 6. Interview Q&A

**Q: What is a realm and why not put everything in one realm?**
A realm is a fully isolated security domain — its own users, clients, roles, and **signing keys**. You separate realms for hard tenant/security isolation (e.g., `internal-employees` vs `customers`), because a token signed by one realm is meaningless to another. You do *not* create a realm per application — apps are `clients` within a realm.

**Q: Public vs confidential client — how do you decide?**
Can the client keep a secret at runtime? Browser SPA / mobile = public → Authorization Code + PKCE. Server-side backend = confidential → holds a secret, can use client credentials or auth-code with a secret.

**Q: What's a bearer-only client?**
A client that only *validates* tokens and never initiates authentication — a pure resource server / API. It has no login flow, which reduces attack surface.

**Q: What's a service account?**
A non-human identity attached to a confidential client, enabling the Client Credentials grant for machine-to-machine calls. Our `backend-worker` logs in as `service-account-backend-worker`.

**Q: How does a resource server know a token is valid without calling Keycloak every time?**
It fetches Keycloak's public keys from the **JWKS** endpoint (`/protocol/openid-connect/certs`) and verifies the JWT signature *locally* — no network round-trip per request. It refreshes JWKS on key rotation. (Alternative: token **introspection** endpoint for opaque tokens or instant revocation checks — a latency/freshness trade-off.)

**Q: Why external Postgres and not the built-in DB?**
The default dev database (H2) is ephemeral and single-node. Production Keycloak needs a real RDBMS for persistence, clustering, and backups.

**Q: How do you promote this config to production safely?**
Realm-as-code (import JSON / Terraform keycloak provider), `start` mode with an optimized build, enforced TLS + strict hostname, secrets from a vault (not env literals), and DB migrations handled by Keycloak's built-in Liquibase on upgrade.

---

## 7. Commands cheat sheet

```bash
# Start / stop
cd docker
docker compose up -d
docker compose down          # keep data
docker compose down -v       # wipe Postgres volume (forces realm re-import)

# Logs
docker compose logs -f keycloak

# Admin console
#   http://localhost:8085  →  admin / admin

# OIDC discovery
curl -s http://localhost:8085/realms/demo/.well-known/openid-configuration | python -m json.tool

# Get a machine token (client credentials)
curl -s -X POST http://localhost:8085/realms/demo/protocol/openid-connect/token \
  -d grant_type=client_credentials \
  -d client_id=backend-worker \
  -d client_secret=backend-worker-secret

# Decode a JWT payload (paste the access_token)
echo "<token>" | cut -d. -f2 | base64 -d 2>/dev/null | python -m json.tool
```

---

## 8. What I can demo live
1. `docker compose up -d` → fully configured IdP in ~15s.
2. Open the admin console, walk through realm → clients → roles → users.
3. `curl` a client-credentials token and decode it on screen.
4. Explain every field in the JWT (`iss`, `sub`, `azp`, `aud`, `realm_access.roles`, `exp`).

**Next module:** stand up `product-service` (Spring Boot resource server) that validates these tokens and enforces the `app_user` / `app_admin` roles — and fix the `aud` gotcha.
