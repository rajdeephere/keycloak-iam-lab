#!/usr/bin/env bash
# Decode and compare the three tokens from the last auth-code run.
# ID token = "who the user is" (for the CLIENT). Access token = "what you may
# call" (for the RESOURCE SERVER). Refresh token = "get a new access token".
set -euo pipefail
TOK="$(dirname "$0")/.tokens.json"
[ -f "$TOK" ] || { echo "Run auth-code-pkce.sh first."; exit 1; }

decode() { # $1 = jwt -> pretty-print payload
  python -c "import sys,base64,json; t=sys.argv[1].split('.')[1]; t+='='*(-len(t)%4); print(json.dumps(json.loads(base64.urlsafe_b64decode(t)),indent=2))" "$1"
}
field() { python -c "import sys,json;print(json.load(open(sys.argv[1]))[sys.argv[2]])" "$TOK" "$2"; }

echo "================= ID TOKEN (audience = the client web-app) ================="
decode "$(field "$TOK" id_token)"
echo
echo "================= ACCESS TOKEN (audience = product-service) ================="
decode "$(field "$TOK" access_token)"
echo
echo "Note: typ=ID vs typ=Bearer; ID token carries profile claims for the client,"
echo "access token carries aud/roles/scope for the API. Never send an ID token"
echo "to an API as a bearer credential — that's a common mistake."
