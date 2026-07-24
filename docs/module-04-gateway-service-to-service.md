# Module 4 — API Gateway + Service-to-Service Auth

**JD skills proven:** microservices · application integration with IAM · REST APIs
**Status:** ✅ Built, dockerized, verified end to end

---

## 1. What I built

An **API gateway** as the single entry point, and a second resource server
(**order-service**) that calls `product-service` two different ways.

```
                         ┌────────────────────────────┐
Client ──Bearer──► gateway :8090 (edge JWT check) ─────┤
                         │  /api/products/**  ─────────┼──► product-service :8081
                         │  /api/orders/**    ─────────┼──► order-service   :8083
                         └────────────────────────────┘         │
                                                                 │ calls product-service:
                                     TOKEN RELAY (as the user) ──┤  GET /api/orders
                                CLIENT CREDENTIALS (as itself) ──┘  GET /api/orders/sync
```

**Verified**
| Check | Result |
|-------|--------|
| `GET /api/products` via gateway, no token | **401** (edge rejects) |
| `GET /api/products` via gateway, alice | **200** (routed to product-service) |
| `GET /api/orders` via gateway, alice | **200** (routed to order-service) |
| `/api/orders` → product-service call | as user **alice** (token relay) |
| `/api/orders/sync` → product-service call | as **service-account order-service** (client credentials) |

---

## 2. The two service-to-service patterns (the core of this module)

### Pattern 1 — Token relay (on behalf of the user)
order-service takes the **caller's** access token and forwards it when calling
product-service. Downstream sees **alice**, with alice's roles.

```java
@GetMapping
public Map<String,Object> orders(@AuthenticationPrincipal Jwt jwt) {
    return fetchProducts(jwt.getTokenValue());   // forward the USER's token
}
```
- **Use when:** the downstream action is *on behalf of the logged-in user* and should
  respect that user's permissions (most user-facing request chains).
- **Property:** identity + roles propagate; product-service can enforce alice's access.

### Pattern 2 — Client credentials (as the service itself)
order-service authenticates as **itself** (its own service account) and calls
product-service. No user involved.

```java
OAuth2AuthorizedClient c = authorizedClientManager.authorize(
    OAuth2AuthorizeRequest.withClientRegistrationId("order-service")
        .principal("order-service").build());
return fetchProducts(c.getAccessToken().getTokenValue());   // the SERVICE's token
```
- **Use when:** background jobs, scheduled syncs, or any call where there is no user
  on whose behalf to act.
- **Property:** the token's subject is `service-account-order-service`; its permissions
  are the service's, not any user's.

**Interview line:** *"Token relay propagates the user's identity downstream; client
credentials calls as the service's own identity. Pick relay when the action is on behalf
of a user and must respect their permissions; pick client credentials for autonomous
machine work."*

> Why order-service's service account needed the `app_user` role: product-service's
> `GET /api/products` requires `app_user`/`app_admin`. A bare service account has none,
> so I granted `app_user` to `service-account-order-service` in the realm. This is a
> teaching point: **machine identities need roles too** — least privilege applies.

---

## 3. Edge vs in-service authorization (defense in depth)

| Layer | What it checks | Why |
|-------|----------------|-----|
| **Gateway (edge)** | valid token? (signature, issuer, expiry) | cheap, coarse gate; reject anonymous traffic before it reaches services |
| **Service (in-service)** | roles (`@PreAuthorize`), audience | the service owns the resource and must never trust that the edge already checked |

The gateway does **not** validate audience — it doesn't own the resource. Each service
does. The rule: **every hop re-validates; the edge is an optimization, not the
authority.** If someone bypassed the gateway and hit a service directly, the service
still enforces its own rules.

**Interview line:** *"Authorization is layered. The gateway rejects the obviously
unauthenticated at the edge; the service still does fine-grained checks because it can't
assume anything upstream was trustworthy. That's defense in depth."*

---

## 4. Architectural decisions (with *why*)

### D1 — Spring Cloud Gateway (reactive) as the entry point
- **Why:** it's the standard Spring gateway — declarative path routing, filters, and it
  integrates with Spring Security's **reactive** resource server. One place to terminate
  auth at the edge and fan out to services.
- **Note:** it's WebFlux (reactive), so security uses `ServerHttpSecurity` /
  `SecurityWebFilterChain`, not the servlet `HttpSecurity` used in the other services.

### D2 — Gateway forwards the `Authorization` header automatically
- **Why:** the gateway proxies headers downstream by default, so the bearer token reaches
  the routed service unchanged — no extra filter needed for pass-through. (Spring's
  `TokenRelay` filter is for a gateway that is itself an OAuth2 *client* holding tokens;
  ours is a resource server, so the incoming header just flows through.)

### D3 — order-service is BOTH a resource server AND an OAuth2 client
- **Why:** it must *validate* incoming user tokens (resource server) **and** *obtain* its
  own token for M2M calls (client, `client_credentials` grant). Both starters coexist;
  the `AuthorizedClientServiceOAuth2AuthorizedClientManager` acquires and caches the
  machine token without any user request context.

### D4 — Reused the realm-role converter + audience model
- **Why:** consistency. order-service validates tokens exactly like product-service
  (realm roles → `ROLE_*`), so the security model is uniform across services.

### D5 — Everything env-configurable, wired into the same Compose stack
- **Why:** the gateway routes and downstream URLs are env vars (`PRODUCT_SERVICE_URL`,
  `ORDER_SERVICE_URL`) — compose sets service names; on-host defaults to localhost. Same
  jar, either mode. Consistent with the Module-infra approach.

---

## 5. Interview Q&A

**Q: Token relay vs client credentials — when each?**
Relay forwards the user's token so downstream respects the user's identity/permissions —
use for user-initiated chains. Client credentials calls as the service's own identity —
use for background/M2M work with no user.

**Q: Where do you enforce authorization — gateway or service?**
Both, layered. Gateway does a coarse "is this authenticated?" at the edge; each service
enforces fine-grained roles and audience because it owns its resource and can't trust
upstream. Defense in depth.

**Q: Why doesn't the gateway validate audience?**
Audience says "this token is meant for service X." The gateway isn't the audience — the
downstream service is. So the service validates `aud`; the gateway only proves the token
is authentic and unexpired.

**Q: How does the user's token reach product-service through order-service?**
order-service reads the incoming JWT (`@AuthenticationPrincipal Jwt`) and sets it as the
`Authorization: Bearer` header on its RestClient call — token relay.

**Q: A machine call got 403 from product-service. Why?**
The service account had no `app_user`/`app_admin` role, and product-service requires one.
Machine identities need roles assigned too — least privilege still applies. I granted
`app_user` to `service-account-order-service`.

**Q: Is putting auth only at the gateway enough?**
No. If anything can reach a service directly (misconfig, internal traffic, a compromised
pod), edge-only auth leaves it open. Services must enforce their own rules.

**Q: How would you propagate identity without forwarding the raw token (e.g. token exchange)?**
Keycloak supports the OAuth2 Token Exchange grant: a service swaps the user's token for a
new one scoped to the downstream audience. Heavier but avoids over-broad token reuse and
lets you narrow scope/audience per hop.

---

## 6. Run it

```bash
cd docker && docker compose up -d --build     # 6 containers now

TOKEN=$(curl -s -X POST http://localhost:8085/realms/demo/protocol/openid-connect/token \
  -d grant_type=password -d client_id=web-app -d username=alice -d password=password -d scope=openid \
  | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")

# All traffic goes through the gateway (:8090)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/products        # routed to product-service
curl -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/orders          # token relay
curl -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/orders/sync     # client credentials
curl -i http://localhost:8090/api/products                                       # 401 at the edge
```

(Postman: **Module 4** folder.)

---

## 7. What I can demo live
1. Hit `/api/products` through the gateway with no token → 401 at the edge.
2. With alice's token → 200, and show it was routed to product-service.
3. `/api/orders` → show `mode: TOKEN RELAY ... as user 'alice'` — the user's identity
   reached product-service.
4. `/api/orders/sync` → show `mode: CLIENT CREDENTIALS ... service-account 'order-service'`
   — same downstream call, different identity.
5. Explain edge-vs-service authz and why every hop re-validates.

**Next module:** Module 5 — identity lifecycle via the Keycloak Admin API (provision/
disable users, assign roles, revoke sessions).
