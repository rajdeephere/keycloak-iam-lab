#!/usr/bin/env bash
# Refresh token grant: trade a refresh token for a NEW access token without
# re-authenticating. This is how a session outlives the 5-min access token.
set -euo pipefail
KC=http://localhost:8085; REALM=demo; CLIENT=web-app
TOK="$(dirname "$0")/.tokens.json"
[ -f "$TOK" ] || { echo "Run auth-code-pkce.sh first."; exit 1; }

OLD_REFRESH=$(python -c "import json;print(json.load(open('$TOK'))['refresh_token'])")
echo "Old refresh token: ${OLD_REFRESH:0:32}..."
echo "POST /token grant_type=refresh_token"
NEW=$(curl -s -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
  --data-urlencode "grant_type=refresh_token" \
  --data-urlencode "client_id=$CLIENT" \
  --data-urlencode "refresh_token=$OLD_REFRESH")

echo "$NEW" | python -c "import sys,json;d=json.load(sys.stdin);print('NEW access_token :',d['access_token'][:32]+'...');print('NEW refresh_token:',d['refresh_token'][:32]+'...  (rotated -- old one is now invalid)')"
# Persist the rotated tokens so introspect/logout use the current ones.
echo "$NEW" > "$TOK"
echo "Refresh-token rotation: Keycloak issued a new refresh token; reusing the old"
echo "one now fails. This limits damage if a refresh token leaks."
