#!/usr/bin/env bash
# SSE 并发压测（bash 版，无 JMeter 环境时的等价手段）
# 用法：bash docs/load/sse-bash-load.sh [并发数=50] [每人轮次=2]
# 前置：应用已启动（中间件容器 + GATEWAY_API_KEY 已配置；无 LLM key 时会快速失败，同样能压到线程池与连接层）
CONCURRENCY=${1:-50}
ROUNDS=${2:-2}
OUT=$(mktemp -d)
echo "并发 $CONCURRENCY × $ROUNDS 轮 → $OUT"

send_one() {
  local id=$1
  for r in $(seq 1 $ROUNDS); do
    local t0=$(date +%s%3N)
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 30 -X POST http://localhost:8000/api/v1/chat \
      -H "Content-Type: application/json" -d "{\"message\":\"压测消息$id-$r\"}")
    local t1=$(date +%s%3N)
    echo "$code $((t1-t0))" >> "$OUT/results.txt"
  done
}
for i in $(seq 1 $CONCURRENCY); do send_one $i & done
wait

echo "--- 状态码分布 ---"
awk '{print $1}' "$OUT/results.txt" | sort | uniq -c
echo "--- 延迟(ms) P50/P95/P99 ---"
awk '{print $2}' "$OUT/results.txt" | sort -n > "$OUT/lat.sorted"
n=$(wc -l < "$OUT/lat.sorted")
awk -v n="$n" 'NR==int(n*0.5){p50=$1} NR==int(n*0.95){p95=$1} NR==int(n*0.99){p99=$1} END{print p50, p95, p99}' "$OUT/lat.sorted" | awk '{printf "P50=%d P95=%d P99=%d\n",$1,$2,$3}'
echo "--- 容器业务线程池水位参考（http-nio-8000-exec-* 活跃数）---"
jps -l 2>/dev/null | grep agent-platform || true
echo "（更精确的线程池水位见 JMeter 运行时的 VisualVM / 应用日志）"
