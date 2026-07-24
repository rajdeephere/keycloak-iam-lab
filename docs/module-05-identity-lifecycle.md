# Module 5 — Identity Lifecycle via the Admin API

**JD skills proven:** Identity lifecycle & access management concepts · application integration with IAM · Java
**Status:** ✅ Built (`admin-service`), dockerized, full lifecycle verified

---

## 1. What I built

A Java **`admin-service`** (:8084) that drives the Keycloak **Admin REST API** to manage
identities programmatically — the joiner/mover/leaver lifecycle — plus a raw-curl script
(`scripts/module-05/admin-api.sh`) showing the same endpoints by hand.

```
bob (app_admin) ──► gateway :8090 ──► admin-service :8084 ──(Admin REST API)──► Keycloak
     token                edge check   requires app_admin      as its own
                                        + calls as itself       service account
                                                                (realm-management roles)
```

Two layers of identity in one call:
- **Who may operate:** only an `app_admin` **user** (bob) can call admin-service.
- **How it operates:** admin-service authenticates to Keycloak as its **own service
  account** (client_credentials), which holds `realm-management` roles. It never uses
  the master admin password.

---

## 2. The identity lifecycle (joiner / mover / leaver)

| Phase | Meaning | Endpoint | Admin API call |
|-------|---------|----------|----------------|
| **Joiner** | provision a new identity | `POST /api/admin/users` | `POST /users` (+ `reset-password`) |
| **Mover** | change access as role changes | `POST/DELETE .../roles/{role}` | `POST/DELETE /users/{id}/role-mappings/realm` |
| **Leaver (soft)** | revoke ability to log in | `POST .../disable` | `PUT /users/{id}` `enabled=false` |
| **Leaver (instant)** | kill active sessions now | `POST .../logout` | `POST /users/{id}/logout` |
| (cleanup) | remove the identity | `DELETE .../{id}` | `DELETE /users/{id}` |

**Verified end to end (through the gateway, as bob):**
- alice (app_user) → admin API = **403**; no token = **401** at the edge
- create `carol` → assign `app_user` → carol **can log in**
- disable carol → carol login = **"Account disabled"**
- create `dave`, log in, capture refresh token → admin `logout` → refresh = **"Session
  not active"** (instant revocation)

---

## 3. Disable vs revoke — the crucial distinction (ties back to Module 3)

- **Disable** (`enabled=false`) stops *future* logins, but **existing tokens/sessions
  survive** until they expire. A user you "disabled" can still be calling APIs with a
  valid access token for minutes.
- **Logout** (`/logout`) **revokes active sessions now** — refresh tokens die immediately
  (verified: "Session not active").
- Access tokens already issued still pass **local** JWT validation until `exp` — so true
  instant deprovisioning = disable **+** logout **+** short access-token lifetimes (or
  introspection on sensitive calls). This is the M3 statelessness-vs-revocation trade-off,
  now from the *admin* side.

**Interview line:** *"Disabling a user isn't instant deprovisioning — outstanding tokens
live on. You disable to stop new logins, revoke sessions to kill refresh, and rely on
short access-token TTL (or introspection) for the residual window."*

---

## 4. Architectural decisions (with *why*)

### D1 — Service account with `realm-management` roles, NOT the master admin
- **Why:** using `admin`/`admin` from an app is a security anti-pattern (over-privileged,
  shared, hard to rotate/audit). Instead, `admin-service`'s service account is granted
  exactly the `realm-management` client roles it needs (`manage-users`, `view-users`,
  `query-users`, `view-realm`) — **least privilege**, scoped to the `demo` realm only.

### D2 — admin-service is BOTH resource server and client
- **Resource server:** validates the caller's token and requires `app_admin`, so identity
  management is itself access-controlled.
- **Client (client_credentials):** obtains its own token for the Admin API. Same dual role
  as order-service, reused here.

### D3 — Called the Admin REST API directly (RestClient), not the admin-client library
- **Why:** the endpoints (`/users`, `/role-mappings/realm`, `/logout`) stay visible, which
  is what an interviewer probes. `org.keycloak:keycloak-admin-client` is the typed
  alternative; I note it in the code and would use it in a larger codebase.

### D4 — All admin traffic routes through the gateway
- **Why:** consistency — `/api/admin/**` is just another route with edge authn, and the
  service still enforces `app_admin`. Defense in depth all the way down.

### D5 — Realm-as-code for the privileged service account
- The `admin-service` client and its role grants live in `demo-realm.json`
  (`clientRoles: { realm-management: [...] }` on `service-account-admin-service`), so the
  privilege set is reviewable and reproducible — not clicked in by hand.

---

## 5. Interview Q&A

**Q: Describe the identity lifecycle.**
Joiner (provision the account + initial access), mover (adjust roles/groups as the person
changes teams), leaver (disable, revoke sessions, eventually delete). Each maps to Admin
API operations; the goal is that access always reflects current entitlement.

**Q: How do you deprovision a user immediately?**
Disable the account (stops new logins) **and** revoke active sessions via `/logout` (kills
refresh tokens now). Access tokens already issued remain valid until `exp` under local
validation, so keep access-token TTL short or use introspection for the residual window.

**Q: How should an app authenticate to the Admin API — admin password?**
No. Use a dedicated client with a service account granted only the needed
`realm-management` roles. Least privilege, auditable, rotatable — never the master admin.

**Q: What roles does managing users require?**
`realm-management` client roles: `manage-users` (create/update/delete), `view-users` /
`query-users` (read/search), plus `view-realm`. Assign the narrowest set that works.

**Q: Disable vs delete vs logout?**
Disable = reversible, blocks new logins, keeps the record (good for leavers you may
re-enable or must retain for audit). Delete = removes the identity entirely. Logout =
terminates active sessions without changing the account.

**Q: What is SCIM and how does it relate?**
SCIM is a standard protocol for cross-system user provisioning (an IdP pushes joiner/mover/
leaver changes to downstream apps). Here I drove Keycloak's own Admin API; SCIM would be
the interop layer if HR/IGA systems needed to provision *into* or *out of* Keycloak.

**Q: How would you audit these operations?**
Enable Keycloak admin events (and login events), ship them to a SIEM. Every create/disable/
role-change is an admin event with the acting service account — traceable.

---

## 6. Run it

```bash
cd docker && docker compose up -d --build     # 7 containers

# As bob (app_admin), through the gateway:
BOB=$(curl -s -X POST http://localhost:8085/realms/demo/protocol/openid-connect/token \
  -d grant_type=password -d client_id=web-app -d username=bob -d password=password -d scope=openid \
  | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

curl -X POST -H "Authorization: Bearer $BOB" -H 'Content-Type: application/json' \
  -d '{"username":"carol","email":"carol@demo.local","firstName":"Carol","lastName":"N","password":"carolpass"}' \
  http://localhost:8090/api/admin/users                       # joiner
curl -X POST -H "Authorization: Bearer $BOB" http://localhost:8090/api/admin/users/<id>/roles/app_user   # mover
curl -X POST -H "Authorization: Bearer $BOB" http://localhost:8090/api/admin/users/<id>/disable          # leaver (soft)
curl -X POST -H "Authorization: Bearer $BOB" http://localhost:8090/api/admin/users/<id>/logout           # leaver (instant)

# Raw protocol version (no Java):
export LC_ALL=C.UTF-8 && bash scripts/module-05/admin-api.sh
```

(Postman: **Module 5** folder.)

---

## 7. What I can demo live
1. As alice → admin API returns 403; no token → 401. Only admins manage identities.
2. As bob: create `carol` → assign `app_user` → carol logs in successfully.
3. Disable carol → her login now fails with "Account disabled".
4. Create `dave`, log in, then admin `/logout` → his refresh token is instantly "Session
   not active" — instant deprovisioning.
5. Explain disable-vs-revoke and least-privilege service accounts.

**This completes the core project (Modules 1–5).** Next: the side labs — **A** custom SPI
(write, then remove/replace), **B** LDAP/AD, **C** Kubernetes, **D** CI/CD.
