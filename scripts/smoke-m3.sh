#!/usr/bin/env bash
# M3.6 全链路联调：booking OpenAPI → 平台导入 → tools/list → tools/call（网关→booking 真实业务）
# 前置：dev 容器运行中；booking(8090) 与 platform(8000) 均已启动；PLATFORM_KEY 为平台租户 key
set -e
BASE=http://localhost:8000
PLATFORM_KEY=${PLATFORM_KEY:?need platform tenant api key}

echo "--- 1. 拉取 booking /v3/api-docs 并转义为导入载荷 ---"
OPENAPI=$(curl -s http://localhost:8090/v3/api-docs \
  | sed 's/\\/\\\\/g; s/"/\\"/g' \
  | awk '{printf "%s\\n", $0}' | sed 's/\\n$//')
printf '{"gatewayId":"gw1","baseUrl":"http://localhost:8090","authHeaderName":"X-Service-Key","authHeaderValue":"booking-service-key","openapi":"%s"}' \
  "$OPENAPI" > /tmp/import-booking.json
wc -c /tmp/import-booking.json

echo "--- 2. 导入平台 ---"
curl -s -X POST $BASE/admin/protocols/import -H "X-Api-Key: $PLATFORM_KEY" \
  -H "Content-Type: application/json" -d @/tmp/import-booking.json | head -c 900
echo

echo "--- 3. Streamable initialize ---"
curl -s -D /tmp/m3-headers.txt -X POST $BASE/gw1/mcp -H "X-Api-Key: $PLATFORM_KEY" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}' > /dev/null
SID=$(grep -i "Mcp-Session-Id" /tmp/m3-headers.txt | sed 's/.*: *//' | tr -d '\r')
echo "session=$SID"

echo "--- 4. tools/list（booking 工具 + 敏感标记）---"
curl -s -X POST $BASE/gw1/mcp -H "X-Api-Key: $PLATFORM_KEY" -H "Mcp-Session-Id: $SID" \
  -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
  | python -c "import json,sys; d=json.load(sys.stdin); [print(t['name'], '| require_confirm=', t['x-require-confirm']) for t in d['result']['tools']]" 2>/dev/null \
  || curl -s -X POST $BASE/gw1/mcp -H "X-Api-Key: $PLATFORM_KEY" -H "Mcp-Session-Id: $SID" \
     -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | head -c 800
echo

echo "--- 5. tools/call search_rooms（网关→booking，X-Service-Key 附带）---"
curl -s -X POST $BASE/gw1/mcp -H "X-Api-Key: $PLATFORM_KEY" -H "Mcp-Session-Id: $SID" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"searchAvailable","arguments":{"date":"2026-10-07","startTime":"14:00:00","endTime":"16:00:00"}}}' \
  | head -c 600
echo

echo "--- 6. tools/call 创建预订（真实业务写入）---"
curl -s -X POST $BASE/gw1/mcp -H "X-Api-Key: $PLATFORM_KEY" -H "Mcp-Session-Id: $SID" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"createBooking","arguments":{"body":{"roomId":2,"title":"AI 助手代订的周会","startTime":"2026-10-07T14:00:00","endTime":"2026-10-07T16:00:00","idempotentKey":"m36-smoke-001"}}}}' \
  | head -c 600
echo
echo "--- 7. booking 库验证真实落库 ---"
docker exec agent-platform-mysql mysql -uroot -proot meeting_booking -N -e \
  "SELECT id, room_id, title, status FROM booking ORDER BY id DESC LIMIT 2;" 2>/dev/null
