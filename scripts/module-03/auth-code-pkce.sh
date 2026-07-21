#!/usr/bin/env bash
# Authorization Code + PKCE flow, executed BY HAND with curl.
# Normally a browser does steps 1-3; we drive Keycloak's login form directly
# so every step is visible. This is the flow web-app (a public SPA) uses.
set -euo pipefail

KC=http://localhost:8085
REALM=demo
CLIENT=web-app
REDIRECT_URI=http://localhost:8081/callback
USER=${1:-alice}
PASS=${2:-password}

AUTH=$KC/realms/$REALM/protocol/openid-connect/auth
TOKEN=$KC/realms/$REALM/protocol/openid-connect/token
COOKIES=$(mktemp)

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

echo "=================================================================="
echo "STEP 0 — PKCE: create a per-request secret (verifier) + its hash (challenge)"
echo "=================================================================="
CODE_VERIFIER=$(openssl rand -base64 64 | tr '+/' '-_' | tr -d '=\n' | cut -c1-64)
CODE_CHALLENGE=$(printf '%s' "$CODE_VERIFIER" | openssl dgst -binary -sha256 | b64url)
echo "code_verifier : $CODE_VERIFIER"
echo "code_challenge: $CODE_CHALLENGE   (= BASE64URL(SHA256(verifier)))"
echo

echo "=================================================================="
echo "STEP 1 — Authorization request: GET /auth (browser would show login page)"
echo "=================================================================="
AUTH_URL="$AUTH?client_id=$CLIENT&response_type=code&scope=openid&redirect_uri=$REDIRECT_URI&state=xyz123&code_challenge=$CODE_CHALLENGE&code_challenge_method=S256"
echo "GET $AUTH_URL"
LOGIN_HTML=$(curl -s -c "$COOKIES" "$AUTH_URL")
# The login page contains <form action="...kc-form-login..."> — extract it.
FORM_ACTION=$(printf '%s' "$LOGIN_HTML" | sed -n 's/.*action="\([^"]*\)".*/\1/p' | head -1 | sed 's/\&amp;/\&/g')
echo "Login form action extracted (contains session/execution + tab id)."
echo

echo "=================================================================="
echo "STEP 2 — User authenticates: POST credentials to the login form"
echo "=================================================================="
# Keycloak responds 302 to redirect_uri?code=...&state=...  We capture the Location.
REDIRECT_LOCATION=$(curl -s -o /dev/null -c "$COOKIES" -b "$COOKIES" \
  --data-urlencode "username=$USER" \
  --data-urlencode "password=$PASS" \
  --data-urlencode "credentialId=" \
  -w '%{redirect_url}' "$FORM_ACTION")
echo "302 Location: $REDIRECT_LOCATION"
AUTH_CODE=$(printf '%s' "$REDIRECT_LOCATION" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
if [ -z "$AUTH_CODE" ]; then echo "!! No auth code returned. Login likely failed."; exit 1; fi
echo "Authorization code: ${AUTH_CODE:0:24}...  (one-time use, ~60s TTL)"
echo

echo "=================================================================="
echo "STEP 3 — Token request: exchange code + verifier at /token"
echo "=================================================================="
echo "The verifier proves this is the SAME party that started the flow."
TOKENS=$(curl -s -X POST "$TOKEN" \
  --data-urlencode "grant_type=authorization_code" \
  --data-urlencode "client_id=$CLIENT" \
  --data-urlencode "code=$AUTH_CODE" \
  --data-urlencode "redirect_uri=$REDIRECT_URI" \
  --data-urlencode "code_verifier=$CODE_VERIFIER")

echo "$TOKENS" | python -c "import sys,json; d=json.load(sys.stdin); print('access_token  :', d['access_token'][:32]+'...'); print('refresh_token :', d['refresh_token'][:32]+'...'); print('id_token      :', d['id_token'][:32]+'...'); print('expires_in    :', d['expires_in'], 's'); print('token_type    :', d['token_type'])"

# Save tokens for the other scripts (refresh, introspect, logout)
echo "$TOKENS" > "$(dirname "$0")/.tokens.json"
echo
echo "Tokens saved to scripts/module-03/.tokens.json (used by refresh/introspect/logout)."
rm -f "$COOKIES"
