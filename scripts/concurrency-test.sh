#!/usr/bin/env bash
# Proves the two concurrency guarantees of the Message Service:
#   1. 100 parallel sends to one conversation -> seq numbers are exactly 1..100 (no gaps, no duplicates)
#   2. 20 parallel sends with the SAME Idempotency-Key -> exactly one message is created
# Requires: curl, jq, xargs.  Run after `docker compose up`.
set -euo pipefail

BASE="${BASE:-http://localhost:8081}"
NOTIF="${NOTIF:-http://localhost:8082}"
N="${N:-100}"
PARALLEL="${PARALLEL:-50}"

echo "Creating conversation between users 1, 2, 3..."
CONV_ID=$(curl -sf -X POST "$BASE/conversations" \
  -H "Content-Type: application/json" -H "X-User-Id: 1" \
  -d '{"name":"load-test","memberIds":[2,3]}' | jq -r '.id')
echo "Conversation id: $CONV_ID"

echo
echo "Test 1: firing $N concurrent messages ($PARALLEL at a time)..."
seq 1 "$N" | xargs -P "$PARALLEL" -I{} \
  curl -sf -o /dev/null -X POST "$BASE/conversations/$CONV_ID/messages" \
    -H "Content-Type: application/json" -H "X-User-Id: 1" \
    -d '{"content":"message {}"}'

SEQS_OK=$(curl -sf "$BASE/conversations/$CONV_ID/messages?limit=200" -H "X-User-Id: 1" \
  | jq --argjson n "$N" '[.[].seq] | sort == [range(1; $n + 1)]')
if [ "$SEQS_OK" = "true" ]; then
  echo "PASS: seq numbers are exactly 1..$N"
else
  echo "FAIL: seq numbers have gaps or duplicates"; exit 1
fi

echo
echo "Test 2: firing 20 concurrent retries with the same Idempotency-Key..."
KEY="retry-$(date +%s)"
seq 1 20 | xargs -P 20 -I{} \
  curl -sf -o /dev/null -X POST "$BASE/conversations/$CONV_ID/messages" \
    -H "Content-Type: application/json" -H "X-User-Id: 1" -H "Idempotency-Key: $KEY" \
    -d '{"content":"hello, sent once"}'

COUNT=$(curl -sf "$BASE/conversations/$CONV_ID/messages?limit=200" -H "X-User-Id: 1" \
  | jq '[.[] | select(.content == "hello, sent once")] | length')
if [ "$COUNT" = "1" ]; then
  echo "PASS: exactly one message created"
else
  echo "FAIL: $COUNT messages created"; exit 1
fi

echo
echo "Waiting 3s for the outbox + queue to deliver notifications..."
sleep 3
NOTIFS=$(curl -sf "$NOTIF/notifications?userId=2&limit=200" \
  | jq --argjson c "$CONV_ID" '[.[] | select(.conversationId == $c)] | length')
echo "User 2 has $NOTIFS notifications for this conversation (expected $((N + 1)))"
