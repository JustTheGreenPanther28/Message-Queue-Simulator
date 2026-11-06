#!/usr/bin/env bash
# Checks the production-mode stack's security properties from the outside.
#   docker compose -p tilechat-prod -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.prod up -d --build
#   ./scripts/security-check.sh
set -u
WEB=${WEB:-http://localhost:3000}
S3=${S3:-$WEB/storage}
P=${PROJECT:-tilechat-prod}
pass=0; fail=0
ok()  { pass=$((pass+1)); printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad() { fail=$((fail+1)); printf '  \033[31m✗ %s\033[0m\n      %s\n' "$1" "${2:-}"; }
check() { if eval "$2"; then ok "$1"; else bad "$1" "$3"; fi; }
section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

section "HTTP security headers (nginx)"
H=$(curl -s -D - -o /dev/null "$WEB/")
check "CSP allows no other origins (storage is same-origin)" "grep -qiE \"connect-src 'self' *;\" <<<\"\$H\"" "$(grep -i '^content-security-policy' <<<"$H")"
for h in "content-security-policy: default-src 'self'" "x-content-type-options: nosniff" "x-frame-options: DENY" \
         "referrer-policy: no-referrer" "permissions-policy:" "cross-origin-opener-policy: same-origin"; do
  check "$h" "grep -qi \"^$h\" <<<\"\$H\"" "missing"
done
check "no nginx version leaked" "! grep -qi '^server: nginx/' <<<\"\$H\"" "$(grep -i '^server' <<<"$H")"
A=$(curl -s -D - -o /dev/null "$WEB/api/conversations")
check "headers also on API responses" "grep -qi '^x-content-type-options' <<<\"\$A\"" ""

section "Attack surface"
check "actuator is not reachable through the web entry point" "! curl -s $WEB/actuator/gateway/routes | grep -q route_id" "gateway routes exposed"
for port in 5432 6379 5672 15672 8080 8081 8082 8083; do
  check "port $port is not published" "! nc -z -w 1 localhost $port 2>/dev/null" "port $port is open"
done

section "WebSocket origin check"
ws() {  # retries if rate-limited (e.g. right after an e2e run)
  local code
  for _ in 1 2 3 4 5; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
      -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' -H "Origin: $1" "$WEB/ws")
    [ "$code" != 429 ] && break; sleep 2
  done
  echo "$code"
}
check "handshake from the app's origin is accepted (101)" "[ \"\$(ws $WEB)\" = 101 ]" "$(ws $WEB)"
check "handshake from another site is refused (403)" "[ \"\$(ws https://evil.example)\" = 403 ]" "$(ws https://evil.example)"

section "Object storage"
check "anonymous bucket listing is refused" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' $S3/tilechat)\" = 403 ]" "$(curl -s -o /dev/null -w '%{http_code}' $S3/tilechat)"
check "anonymous object read is refused" "[ \"\$(curl -s -o /dev/null -w '%{http_code}' $S3/tilechat/avatars/x.png)\" = 403 ]" ""
check "storage is not published on its own port (same-origin /storage only)" "! nc -z -w 1 localhost 8333 2>/dev/null" "port 8333 is open"

section "Infrastructure"
check "Redis refuses commands without the password" "docker exec $P-redis-1 redis-cli ping 2>&1 | grep -q NOAUTH" ""
for s in user-service chat-service notification-service api-gateway; do
  c=$P-$s-1
  check "$s: read-only root fs, no capabilities, no-new-privileges, non-root" \
    "[ \"\$(docker inspect -f '{{.HostConfig.ReadonlyRootfs}} {{.HostConfig.CapDrop}} {{.HostConfig.SecurityOpt}}' $c)\" = 'true [ALL] [no-new-privileges:true]' ] && [ \"\$(docker exec $c id -u)\" != 0 ]" \
    "$(docker inspect -f '{{.HostConfig.ReadonlyRootfs}} {{.HostConfig.CapDrop}} {{.HostConfig.SecurityOpt}}' $c) uid=$(docker exec $c id -u)"
done

section "Fail-fast configuration"
OUT=$(docker run --rm --network ${P}_default -e SPRING_PROFILES_ACTIVE=prod -e JWT_SECRET=local-dev-secret-change-me-please-0123456789 \
  -e DB_HOST=postgres -e DB_PASSWORD=app -e RABBIT_HOST=rabbitmq -e RABBIT_PASSWORD=x -e REDIS_HOST=redis -e REDIS_PASSWORD=x \
  -e WS_ALLOWED_ORIGINS='*' $P-user-service 2>&1 | grep -o 'Refusing to start[^"]*' | head -1)
check "a service with dev secrets refuses to start" "[ -n \"\$OUT\" ]" "it started anyway"
[ -n "$OUT" ] && printf '      %s\n' "${OUT:0:160}…"

printf '\n%s%d passed, %d failed\033[0m\n' "$([ $fail = 0 ] && echo '\033[32m' || echo '\033[31m')" $pass $fail
exit $([ $fail = 0 ])
