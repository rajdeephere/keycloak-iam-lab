# Module 3 — OAuth2 / OIDC Flows by Hand

**JD skills proven:** Strong knowledge of OAuth2 · OIDC flows
**Status:** ✅ All flows executed headlessly with curl and verified

> Goal: be able to whiteboard every flow cold. Each one below was run raw
> (`scripts/module-03/*.sh`) so nothing is hidden behind a library.

---

## 1. OAuth2 vs OIDC (get this straight first)

- **OAuth2 = authorization.** It issues **access tokens** so a client can *call an API on your behalf*. It says nothing standard about *who you are*.
- **OIDC = authentication, built on top of OAuth2.** It adds the **ID token** (a JWT about the user) and the `/userinfo` endpoint, plus the `openid` scope. It answers *who logged in*.

One sentence: **"OIDC is a thin identity layer on top of OAuth2 — OAuth2 gets you an access token for APIs, OIDC adds an ID token that proves who the user is."**

| Token | Issued by | Audience | Purpose | Send it to... |
|-------|-----------|----------|---------|----------------|
| **ID token** | OIDC | the **client** (`aud: web-app`) | prove *who* authenticated | nobody — the client reads it |
| **Access token** | OAuth2 | the **resource server** (`aud: product-service`) | authorize *API calls* | the API, as `Bearer` |
| **Refresh token** | OAuth2 | the auth server | get a new access token | only back to Keycloak `/token` |

Verified with `token-anatomy.sh`: ID token → `typ: ID, aud: web-app`; access token → `typ: Bearer, aud: product-service, scope: openid profile email`.

---

## 2. Authorization Code + PKCE (the one that matters)

This is what `web-app` (a public SPA) uses. Runnable: `scripts/module-03/auth-code-pkce.sh`.

```
Browser/SPA            Keycloak (/auth, /token)         Resource Server
    │                        │                                │
    │ 0. verifier=random                                      │
    │    challenge=SHA256(verifier)                           │
    │ 1. GET /auth?client_id&response_type=code               │
    │    &code_challenge=CHALLENGE&method=S256 ─────────────► │
    │ 2. user logs in (form POST)                             │
    │ ◄──── 302 redirect_uri?code=ONE_TIME_CODE               │
    │ 3. POST /token grant=authorization_code                 │
    │    code=ONE_TIME_CODE & code_verifier=VERIFIER ───────► │
    │        Keycloak checks SHA256(verifier)==challenge      │
    │ ◄──── access_token + id_token + refresh_token           │
    │ 4. GET /api  Authorization: Bearer access_token ──────────────────► 200
```

**Why the code step at all (why not return the token directly)?**
The code comes back on the browser redirect (the front channel — visible in URLs, history, logs). The **token** is fetched over a direct back-channel POST. So the sensitive credential never rides in a URL.

**Why PKCE?**
A public client has no secret, so a stolen authorization code could be redeemed by an attacker. PKCE binds the code to the original requester:
- `code_verifier` = random secret the client keeps.
- `code_challenge` = `BASE64URL(SHA256(verifier))`, sent on the authorization request.
- At token exchange the client presents the **verifier**; Keycloak hashes it and checks it equals the earlier challenge.
- An attacker who intercepts the code doesn't have the verifier → exchange fails.

**Interview line:** *"PKCE replaces the client secret with a dynamic, per-request proof. It stops authorization-code interception on clients that can't hold a secret."*

---

## 3. Client Credentials (machine-to-machine)

No user, no browser. A confidential client authenticates *as itself*.
(Covered in Module 1; `backend-worker` with its secret → token for its service account.)
Use for cron jobs, service-to-service calls, backend workers.

```
backend-worker ──(client_id + secret, grant=client_credentials)──► Keycloak ──► access_token (sub = service account)
```

---

## 4. Refresh Token grant

Runnable: `scripts/module-03/refresh.sh`.

Access tokens are deliberately short-lived (5 min here). Rather than forcing re-login, the client trades its **refresh token** for a new access token.

- **Refresh token rotation** (verified): Keycloak returns a *new* refresh token and invalidates the old one. If a refresh token leaks and the attacker uses it, the legitimate client's next refresh fails (reuse detection) — surfacing the compromise.
- Refresh tokens live longer than access tokens but still expire (SSO idle/max in Module 1).

**Trade-off framed:** short access token = small blast radius if leaked; refresh token = usability so users aren't re-prompted. Rotation keeps refresh tokens from being long-lived bearer secrets.

---

## 5. Token Introspection (RFC 7662)

Runnable: `scripts/module-03/introspect.sh`.

A confidential client POSTs a token to `/token/introspect` and Keycloak replies `{"active": true/false, ...}`.

- **Why it exists:** local JWT validation can't know a token was revoked before its `exp`. Introspection asks the source of truth, so it reflects revocation **instantly**.
- **Cost:** a network round-trip per check + coupling to IdP availability.
- **When to use:** opaque tokens, or when immediate revocation is a hard requirement (e.g. logout must kill API access now).

---

## 6. Logout / End Session — and the revocation gotcha

Runnable: `scripts/module-03/logout.sh`. **This is the best single demo in the module.**

```
Before logout:  introspect(access_token) -> active = true
POST /logout (client_id + refresh_token) -> 204
After  logout:  introspect(access_token) -> active = false   ✅ revoked server-side
```

**But** the access token's signature is still cryptographically valid until `exp`. So a resource server doing **only local JWT validation would still accept it** for up to 5 minutes.

**The lesson (say this):** *"Logout ends the session and revokes the refresh token, but outstanding access tokens live until they expire unless you check introspection. That's the fundamental JWT trade-off — statelessness vs instant revocation. You manage it with short token lifetimes, or introspection on sensitive operations."*

- **Front-channel logout:** browser redirects clear each app's session (user-driven).
- **Back-channel logout:** Keycloak calls each client's logout URL server-to-server (reliable, no browser needed).

---

## 7. Interview Q&A

**Q: Walk me through Authorization Code + PKCE.**
Client makes a verifier and its SHA-256 challenge → redirects the user to `/auth` with the challenge → user logs in → Keycloak redirects back with a one-time code → client POSTs code + verifier to `/token` → Keycloak checks the hash and returns tokens. Code travels the front channel; token travels the back channel; PKCE binds them.

**Q: Why not the Implicit flow anymore?**
Implicit returned the access token directly in the redirect URL (front channel) — leaky via history/logs, no way to bind to the requester. OAuth 2.1 deprecates it; Auth Code + PKCE is the replacement even for SPAs.

**Q: OAuth2 vs OIDC in one line?**
OAuth2 authorizes API access (access token); OIDC adds authentication (ID token) on top.

**Q: Where do ID vs access tokens go?**
ID token stays with the client (who logged in). Access token goes to the API as a Bearer. Sending an ID token to an API is a common bug — the API should reject it (wrong audience/typ).

**Q: How do you revoke access immediately?**
Refresh tokens can be revoked at logout, but access tokens are valid until `exp` under local validation. For instant revocation, use introspection (or keep access tokens very short). Back-channel logout propagates session termination to clients.

**Q: What stops a stolen authorization code from being used?**
PKCE — the attacker lacks the verifier. Also: codes are one-time and short-lived, and `redirect_uri` must match the registered value.

**Q: What is refresh token rotation and why does it matter?**
Each refresh issues a new refresh token and invalidates the previous one. Reuse of an old token signals theft and can trigger session revocation — it turns a long-lived bearer secret into a moving target.

**Q: client_credentials vs authorization_code — when each?**
client_credentials = no user, service acting as itself (M2M). authorization_code = a user is present and delegating access to a client.

---

## 8. Run it

```bash
cd docker && docker compose up -d           # Keycloak on :8085
cd ..
export LC_ALL=C.UTF-8                        # git-bash locale for the scripts
bash scripts/module-03/auth-code-pkce.sh     # full PKCE dance -> saves tokens
bash scripts/module-03/token-anatomy.sh      # ID vs access token, side by side
bash scripts/module-03/introspect.sh         # active = true
bash scripts/module-03/refresh.sh            # rotated tokens
bash scripts/module-03/logout.sh             # active true -> false (revocation)
```

(Postman: **Module 3** folder mirrors these — plus the interactive Authorization tab for a real browser login.)

---

## 9. The same flow, in real Java — `web-client`

The scripts prove I understand the *protocol*. `services/web-client` proves I can
*build* it: a Spring Boot **OAuth2 client** (not a resource server) that logs a user
in with Authorization Code + PKCE and then calls `product-service` on their behalf.

```
Browser --> web-client :8082 --(redirect)--> Keycloak login --> callback
                |                                                   |
                |  Spring exchanges code + PKCE verifier            |
                | <------- access_token + id_token + refresh -------+
                |
                +-(GET /products, Bearer access_token)--> product-service :8081 --> 200 data
```

**Verified headlessly end to end:** login as alice → redirected to Keycloak →
credentials → callback → Spring completes the PKCE exchange → `/me` shows the
ID-token claims → `/products` calls `product-service` with the access token and
renders the product list.

### Key decisions (with *why*)
- **`spring-boot-starter-oauth2-client`, not resource-server.** This app *initiates*
  login and holds a **server-side session**; product-service only *validates* tokens.
  Same realm, opposite roles — the two halves of an OIDC system.
- **Public client + PKCE, driven by config.** Setting
  `client-authentication-method: none` tells Spring the client has no secret, so it
  **auto-enables PKCE** — verified: the `/auth` redirect carries `code_challenge` +
  `code_challenge_method=S256`. No manual crypto; Spring Security does it correctly.
- **`{baseUrl}/login/oauth2/code/keycloak` redirect URI.** Spring's built-in callback
  endpoint; I registered `http://localhost:8082/*` on the `web-app` client so Keycloak
  accepts it.
- **Calling downstream with `@RegisteredOAuth2AuthorizedClient`.** Spring hands me the
  authorized client holding the user's access token; I attach it as a `Bearer` header
  on the `RestClient` call to product-service. This is *token relay* at the app level
  (Module 4 does it at the gateway).
- **RP-initiated logout** via `OidcClientInitiatedLogoutSuccessHandler` — local logout
  *and* ends the Keycloak SSO session, then returns home. Ties back to §6.

### Extra Q&A this unlocks
**Q: Your SPA/web app is a "public client" — how does Spring keep the flow secure without a secret?**
PKCE. With `client-authentication-method: none`, Spring generates a per-login verifier,
sends its SHA-256 as the `code_challenge`, and presents the verifier at token exchange.
No secret to leak, and an intercepted code is useless without the verifier.

**Q: How does the web app call a downstream API as the user?**
It stores the access token from login (as an `OAuth2AuthorizedClient`) and forwards it
as a `Bearer` header. The API validates it independently — it never trusts the caller,
only the token.

**Run it:**
```bash
cd services/product-service && java -jar target/*.jar &   # :8081
cd services/web-client     && java -jar target/*.jar &   # :8082
# open http://localhost:8082  -> Log in -> alice/password -> /me -> Call product-service
```

---

## 10. What I can demo live
1. **Browser demo:** open `http://localhost:8082`, log in as alice on the real Keycloak
   page, land on `/me` (ID-token claims), click through to `/products` (live call to
   product-service with the access token). The whole OIDC loop, visibly.
2. Run `auth-code-pkce.sh` and narrate each step (verifier → challenge → code → token).
3. Show `token-anatomy.sh`: ID token `aud: web-app` vs access token `aud: product-service`.
4. Run `logout.sh`: introspection flips `active: true → false`, then explain why a locally-validated JWT would still pass — the statelessness-vs-revocation trade-off.

**Next module:** Module 4 — API gateway + service-to-service auth (token relay vs client-credentials, edge vs in-service authorization).
