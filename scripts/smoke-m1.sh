#!/usr/bin/env bash
# M1 全链路冒烟：导入 OpenAPI → Streamable initialize → tools/list → tools/call（网关自环转发）
# 前置：docker compose -f docker-compose.dev.yml up -d；应用已用 3307/6380 配置启动
set -e
# 统一切到仓库根再执行：脚本内的 docs/ 相对路径（如 smoke-import.json）与目录位置解耦
cd "$(dirname "$0")/.."
BASE=http://localhost:8000

KEY=$(curl -s -X POST $BASE/admin/tenants -H "Content-Type: application/json" \
  -d "{\"name\":\"smoke-$(date +%s)\"}" | sed 's/.*"apiKey":"\([^"]*\)".*/\1/')
echo "tenant apiKey: $KEY"

echo "--- 1. 导入 OpenAPI（描述平台自身 /ping）---"
curl -s -X POST $BASE/admin/protocols/import -H "X-Api-Key: $KEY" \
  -H "Content-Type: application/json" -d @docs/smoke-import.json
echo

echo "--- 2. Streamable initialize ---"
curl -s -D /tmp/mcp-headers.txt -X POST $BASE/gw1/mcp -H "X-Api-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}'
SID=$(grep -i "Mcp-Session-Id" /tmp/mcp-headers.txt | sed 's/.*: *//' | tr -d '\r')
echo; echo "session: $SID"

echo "--- 3. tools/list（应发现 service_ping）---"
curl -s -X POST $BASE/gw1/mcp -H "X-Api-Key: $KEY" -H "Mcp-Session-Id: $SID" \
  -H "Content-Type: application/json" -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
echo

echo "--- 4. tools/call service_ping（泛化转发 → 平台自身 /ping）---"
curl -s -X POST $BASE/gw1/mcp -H "X-Api-Key: $KEY" -H "Mcp-Session-Id: $SID" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"service_ping","arguments":{}}}'
echo
echo "--- 完成：tools/call 返回的 content.text 应包含 agent-platform 健康信息 ---"
