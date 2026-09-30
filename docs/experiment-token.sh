#!/usr/bin/env bash
# Token 成本实验（M4.1）：同一 12 轮多轮会话，对比"裁剪关闭 / 裁剪开启"的 LLM 用量
# 前置：LLM_API_KEY 已配置；dev 容器已启动；booking 已启动
# 用法：
#   步骤 A：THRESHOLD=999999 bash docs/experiment-token.sh    # 裁剪关闭（阈值拉满）
#   步骤 B：THRESHOLD=800  bash docs/experiment-token.sh      # 裁剪开启
#   每步先手动重启应用（阈值是启动配置），脚本输出该 session 的 token 总量与每轮成败
THRESHOLD=${THRESHOLD:-6000}
BASE=http://localhost:8000

SAY=("帮我订下周三下午两点八人的会议室" "换成能投屏的房间" "再订周五上午十点四人的" "取消周五那条" \
     "重新订周四下午三点六人的" "最近三天我有哪些预订" "查一下 B305 的详情" \
     "还有别的 8 人房间吗" "给我看所有预订的状态" "把投屏那间的标题改成评审会" \
     "算了我只保留最早的一条" "总结一下我现在的全部预订")

# 第一轮建立会话：捕获完整流，取 sessionKey 并汇报事件
RESP=$(curl -s -N --max-time 90 -X POST $BASE/api/v1/chat -H "Content-Type: application/json" \
  -d "{\"message\":\"${SAY[0]}\"}")
SESSION=$(echo "$RESP" | grep -m1 "sessionKey" | sed 's/.*"sessionKey":"\([^"]*\)".*/\1/')
echo "session=$SESSION threshold=$THRESHOLD"
echo "turn 1: tool_call=$(echo "$RESP" | grep -c 'event:tool_call') error=$(echo "$RESP" | grep -c 'event:error') answer=$(echo "$RESP" | grep -c 'event:answer')"
sleep 2

for i in $(seq 1 11); do
  RESP=$(curl -s -N --max-time 90 -X POST $BASE/api/v1/chat -H "Content-Type: application/json" \
    -d "{\"sessionKey\":\"$SESSION\",\"message\":\"${SAY[$i]}\"}")
  echo "turn $((i+1)): tool_call=$(echo "$RESP" | grep -c 'event:tool_call') error=$(echo "$RESP" | grep -c 'event:error') confirm=$(echo "$RESP" | grep -c 'event:confirm_request') answer=$(echo "$RESP" | grep -c 'event:answer')"
  sleep 2
done

echo "--- 该 session 的 LLM 用量（llm_usage 汇总）---"
docker exec agent-platform-mysql mysql -uroot -proot agent_platform -N -e \
  "SELECT COUNT(*) AS calls, SUM(prompt_tokens) AS prompt_sum, SUM(completion_tokens) AS completion_sum, \
          SUM(prompt_tokens+completion_tokens) AS total \
    FROM llm_usage WHERE session_key='$SESSION';" 2>/dev/null
echo "（两轮实验分别执行后，对比 total：降幅 = 1 - 开启/关闭）"
