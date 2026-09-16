#!/usr/bin/env bash
# Drives the whole slice against a fake Hermes: sign-in token, profile binding, one chat turn,
# and the usage row it produced. No home server and no real AI credential involved.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
JWT_SECRET="smoke-secret-smoke-secret-smoke-secret"
HERMES_PORT=18899
APP_PORT=18080
MAIN_CLASS="com.bifos.assistant.AssistantApplication"
FAKE_PID=""
GRADLE_PID=""

cleanup() {
  [[ -n "$GRADLE_PID" ]] && kill "$GRADLE_PID" 2>/dev/null || true
  pkill -f "$MAIN_CLASS" 2>/dev/null || true
  [[ -n "$FAKE_PID" ]] && kill "$FAKE_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT
# 실패한 자리에서 Control Plane 로그를 보여준다
trap 'echo "--- Control Plane 로그 ---"; grep -nE "ERROR|Caused by|at com.bifos" "$WORK/app.log" 2>/dev/null | tail -30; tail -5 "$WORK/app.log" 2>/dev/null' ERR

mkdir -p "$WORK/keys"
printf 'dad-key' > "$WORK/keys/dad"
chmod 600 "$WORK/keys/dad"

mint_token() {
  python3 - "$1" "$JWT_SECRET" <<'PY'
import base64, hashlib, hmac, json, sys, time

def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

email, secret = sys.argv[1], sys.argv[2]
header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
now = int(time.time())
payload = b64(json.dumps(
    {"sub": email, "name": email, "iat": now, "exp": now + 600}, separators=(",", ":")
).encode())
signing_input = f"{header}.{payload}".encode()
signature = b64(hmac.new(secret.encode(), signing_input, hashlib.sha256).digest())
print(f"{header}.{payload}.{signature}")
PY
}

require_code() {
  local expected="$1" actual="$2" file="$3"
  if [[ "$expected" != "$actual" ]]; then
    echo "HTTP $expected 을 기대했는데 $actual 이 왔다"
    cat "$file" 2>/dev/null || true
    exit 1
  fi
}

echo "== fake Hermes 기동"
HERMES_FAKE_KEYS='dad=dad-key' python3 "$ROOT/tools/fake-hermes/fake_hermes.py" "$HERMES_PORT" &
FAKE_PID=$!

echo "== Control Plane 기동"
cd "$ROOT/backend"
DB_URL="jdbc:h2:mem:smoke;MODE=MySQL;DB_CLOSE_DELAY=-1" \
DB_USERNAME=sa \
DB_PASSWORD="" \
SERVER_PORT="$APP_PORT" \
ASSISTANT_JWT_SECRET="$JWT_SECRET" \
HERMES_PROFILE_KEY_DIR="$WORK/keys" \
SPRING_FLYWAY_ENABLED=false \
SPRING_JPA_HIBERNATE_DDL_AUTO=create-drop \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.h2.Driver \
./gradlew --no-daemon --quiet smokeRun > "$WORK/app.log" 2>&1 &
GRADLE_PID=$!

for _ in $(seq 1 90); do
  if curl -fsS "http://127.0.0.1:$APP_PORT/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
if ! curl -fsS "http://127.0.0.1:$APP_PORT/actuator/health" >/dev/null 2>&1; then
  echo "Control Plane 이 뜨지 않았다"
  tail -40 "$WORK/app.log"
  exit 1
fi

DAD="$(mint_token dad@example.com)"
KID="$(mint_token kid@example.com)"
API="http://127.0.0.1:$APP_PORT/api/v1"

echo "== 첫 사용자가 admin 이 되고 다음 사용자는 member 가 된다"
curl -fsS -H "Authorization: Bearer $DAD" "$API/chat/conversations" >/dev/null
curl -fsS -H "Authorization: Bearer $KID" "$API/chat/conversations" >/dev/null

echo "== 위조한 토큰은 통과하지 못한다"
CODE="$(curl -s -o "$WORK/forged.json" -w '%{http_code}' "$API/chat/conversations" \
  -H "Authorization: Bearer ${DAD%.*}.deadbeef")"
require_code 403 "$CODE" "$WORK/forged.json"
echo "   HTTP $CODE"

echo "== 바인딩이 없는 사용자는 남의 credential 로 돌지 않고 거절된다"
CODE="$(curl -s -o "$WORK/nobind.json" -w '%{http_code}' -X POST "$API/chat/messages" \
  -H "Authorization: Bearer $KID" -H 'Content-Type: application/json' \
  -d '{"text":"숙제 도와줘"}')"
require_code 409 "$CODE" "$WORK/nobind.json"
grep -q HERMES_BINDING_MISSING "$WORK/nobind.json" || { echo "기대한 오류 코드가 아니다"; cat "$WORK/nobind.json"; exit 1; }
echo "   HTTP $CODE $(cat "$WORK/nobind.json")"

echo "== admin 이 dad 에게 profile 을 연결한다"
curl -fsS -X POST "$API/admin/hermes-bindings" \
  -H "Authorization: Bearer $DAD" -H 'Content-Type: application/json' \
  -d "{\"email\":\"dad@example.com\",\"profileName\":\"dad\",\"apiBaseUrl\":\"http://127.0.0.1:$HERMES_PORT/p/dad\",\"provider\":\"openai-codex\",\"model\":\"gpt-5.5\",\"costMode\":\"SUBSCRIPTION\",\"credentialScope\":\"SHARED_HOUSEHOLD\"}" \
  > "$WORK/binding.json"
cat "$WORK/binding.json"; echo

echo "== member 는 admin 전용 엔드포인트를 쓰지 못한다"
CODE="$(curl -s -o "$WORK/forbidden.json" -w '%{http_code}' -X POST "$API/admin/hermes-bindings" \
  -H "Authorization: Bearer $KID" -H 'Content-Type: application/json' \
  -d '{"email":"kid@example.com","profileName":"kid","apiBaseUrl":"http://127.0.0.1:1/p/kid","provider":"x","model":"y","costMode":"API","credentialScope":"DEDICATED"}')"
require_code 403 "$CODE" "$WORK/forbidden.json"
echo "   HTTP $CODE"

echo "== 대화 한 번"
curl -fsS -X POST "$API/chat/messages" \
  -H "Authorization: Bearer $DAD" -H 'Content-Type: application/json' \
  -d '{"text":"오늘 저녁 뭐 먹을까?"}' > "$WORK/turn.json"
cat "$WORK/turn.json"; echo
CONV="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["conversationId"])' "$WORK/turn.json")"
grep -q "오늘 저녁 뭐 먹을까?" "$WORK/turn.json" || { echo "보낸 문장이 Hermes 까지 가지 않았다"; cat "$WORK/turn.json"; exit 1; }

echo "== 같은 대화를 이어서 보낸다"
curl -fsS -X POST "$API/chat/messages" \
  -H "Authorization: Bearer $DAD" -H 'Content-Type: application/json' \
  -d "{\"conversationId\":$CONV,\"text\":\"재료는 뭐가 필요해?\"}" > /dev/null

echo "== 다른 사용자는 그 대화를 읽지 못한다"
CODE="$(curl -s -o "$WORK/other.json" -w '%{http_code}' "$API/chat/conversations/$CONV/messages" \
  -H "Authorization: Bearer $KID")"
require_code 404 "$CODE" "$WORK/other.json"
echo "   HTTP $CODE"

echo "== 사용량 기록"
curl -fsS "$API/usage/executions?limit=10" -H "Authorization: Bearer $DAD" | python3 -m json.tool

echo
echo "모두 통과했다"
