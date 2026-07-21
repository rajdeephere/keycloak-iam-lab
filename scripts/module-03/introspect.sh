#!/usr/bin/env bash
# Token introspection (RFC 7662): ask Keycloak whether a token is still active.
# Unlike local JWT validation, this reflects revocation INSTANTLY — at the cost
# of a network call. A confidential client (backend-worker) authenticates to ask.
set -euo pipefail
KC=http://localhost:8085; REALM=demo
CLIENT=backend-worker; SECRET=backend-worker-secret
TOK="$(dirname "$0")/.tokens.json"
[ -f "$TOK" ] || { echo "Run auth-code-pkce.sh first."; exit 1; }

ACCESS=$(python -c "import json;print(json.load(open('$TOK'))['access_token'])")
echo "POST /token/introspect (authenticated as $CLIENT)"
curl -s -X POST "$KC/realms/$REALM/protocol/openid-connect/token/introspect" \
  -u "$CLIENT:$SECRET" \
  --data-urlencode "token=$ACCESS" \
  | python -c "import sys,json;d=json.load(sys.stdin);print('active   :',d.get('active'));print('username :',d.get('username'));print('client_id:',d.get('client_id'));print('scope    :',d.get('scope'));print('aud      :',d.get('aud'));print('exp      :',d.get('exp'))"
echo
echo "active=true means Keycloak still considers it valid. After logout.sh this"
echo "flips to active=false — that's the revocation signal local JWT checks miss."
