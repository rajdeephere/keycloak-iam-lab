#!/usr/bin/env bash
# Logout / end-session: terminate the SSO session server-side. This revokes the
# refresh token and ends the session, so introspection of related tokens flips
# to active=false. Demonstrates back-channel logout via the refresh token.
set -euo pipefail
KC=http://localhost:8085; REALM=demo; CLIENT=web-app
CLIENT_INTRO=backend-worker; SECRET=backend-worker-secret
TOK="$(dirname "$0")/.tokens.json"
[ -f "$TOK" ] || { echo "Run auth-code-pkce.sh first."; exit 1; }

REFRESH=$(python -c "import json;print(json.load(open('$TOK'))['refresh_token'])")
ACCESS=$(python -c "import json;print(json.load(open('$TOK'))['access_token'])")

introspect_active() {
  curl -s -X POST "$KC/realms/$REALM/protocol/openid-connect/token/introspect" \
    -u "$CLIENT_INTRO:$SECRET" --data-urlencode "token=$1" \
    | python -c "import sys,json;print(json.load(sys.stdin).get('active'))"
}

echo "Before logout — access token active? $(introspect_active "$ACCESS")"
echo "POST /logout (end session) with the refresh token..."
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$KC/realms/$REALM/protocol/openid-connect/logout" \
  --data-urlencode "client_id=$CLIENT" \
  --data-urlencode "refresh_token=$REFRESH")
echo "logout HTTP status: $CODE (204 = session ended)"
echo "After logout  — access token active? $(introspect_active "$ACCESS")"
echo
echo "The access token's SIGNATURE is still cryptographically valid until exp,"
echo "so a resource server doing only local JWT checks would STILL accept it."
echo "Introspection is what sees the revocation. That's the core trade-off."
