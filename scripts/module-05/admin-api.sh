#!/usr/bin/env bash
# The Keycloak Admin REST API BY HAND. Same operations admin-service performs,
# but with raw curl so the endpoints are visible. Auth is via the admin-service
# client's service account (client_credentials), which holds realm-management roles.
set -euo pipefail

KC=http://localhost:8085
REALM=demo
ADMIN=$KC/admin/realms/$REALM

echo "=== 0. Get an admin token (client_credentials for admin-service) ==="
TOKEN=$(curl -s -X POST "$KC/realms/$REALM/protocol/openid-connect/token" \
  -d grant_type=client_credentials \
  -d client_id=admin-service \
  -d client_secret=admin-service-secret \
  | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
echo "got admin token (len ${#TOKEN})"
auth() { curl -s -H "Authorization: Bearer $TOKEN" "$@"; }

echo; echo "=== 1. JOINER: create user 'erin' (POST /users -> 201, id in Location) ==="
LOC=$(curl -s -o /dev/null -D - -X POST "$ADMIN/users" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"username":"erin","email":"erin@demo.local","firstName":"Erin","lastName":"Joiner","enabled":true,"emailVerified":true}' \
  | tr -d '\r' | sed -n 's/^[Ll]ocation: //p')
UID_=${LOC##*/}
echo "created erin id=$UID_"

echo; echo "=== 2. set a password (PUT /users/{id}/reset-password) ==="
auth -X PUT "$ADMIN/users/$UID_/reset-password" -H "Content-Type: application/json" \
  -d '{"type":"password","value":"erinpass","temporary":false}' -o /dev/null -w "   http %{http_code}\n"

echo; echo "=== 3. MOVER: assign realm role app_user (POST /users/{id}/role-mappings/realm) ==="
ROLE=$(auth "$ADMIN/roles/app_user")
auth -X POST "$ADMIN/users/$UID_/role-mappings/realm" -H "Content-Type: application/json" \
  -d "[$ROLE]" -o /dev/null -w "   http %{http_code}\n"

echo; echo "=== 4. list users matching 'erin' (GET /users?search=) ==="
auth "$ADMIN/users?search=erin" \
  | python -c "import sys,json;[print('   ',u['username'],u['id'],'enabled='+str(u['enabled'])) for u in json.load(sys.stdin)]"

echo; echo "=== 5. LEAVER (soft): disable (PUT /users/{id} enabled=false) ==="
auth -X PUT "$ADMIN/users/$UID_" -H "Content-Type: application/json" \
  -d '{"enabled":false}' -o /dev/null -w "   http %{http_code}\n"

echo; echo "=== 6. LEAVER (instant): revoke sessions (POST /users/{id}/logout) ==="
auth -X POST "$ADMIN/users/$UID_/logout" -o /dev/null -w "   http %{http_code}\n"

echo; echo "=== 7. cleanup: delete (DELETE /users/{id}) ==="
auth -X DELETE "$ADMIN/users/$UID_" -o /dev/null -w "   http %{http_code}\n"
echo "done."
