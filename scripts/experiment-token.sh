#!/usr/bin/env bash
# Token 成本实验（M4.1）：同一长会话（默认 30 轮），对比"裁剪关闭 / 裁剪开启"的 LLM 用量
# 前置：LLM_API_KEY 已配置；dev 容器已启动；booking 已启动
# 用法：
#   步骤 A：TURNS=30 THRESHOLD=999999 bash scripts/experiment-token.sh   # 裁剪关闭
#   步骤 B：TURNS=30 THRESHOLD=800   bash scripts/experiment-token.sh   # 裁剪开启
#   每步先手动重启应用（阈值是启动配置），脚本输出该 session 的 token 总量与逐调用趋势
TURNS=${TURNS:-30}
THRESHOLD=${THRESHOLD:-6000}
BASE=http://localhost:8000

# 轮询模板：只含查询/创建（无取消，避免确认阻塞干扰计量）
first_message() {
  echo "帮我订下周三下午两点、能坐8人的会议室"
}
turn_message() {
  local n=$1
  case $((n % 6)) in
    0) echo "再帮我看看下周四下午两点到四点、能坐${n}人的房间" ;;
    1) echo "查一下现在 B305 的状态" ;;
    2) echo "再订一个下周四上午十点到十一点、坐6人的，标题写例会$n" ;;
    3) echo "看看我最近有哪些预订" ;;
    4) echo "帮我看下周三下午还有哪些房间可用" ;;
    5) echo "再订一个下周五上午九点到十点、坐10人的，标题写评审$n" ;;
  esac
}

# 第一轮建立会话
RESP=$(curl -s -N --max-time 120 -X POST $BASE/api/v1/chat -H "Content-Type: application/json" \
  -d "{\"message\":\"$(first_message)\"}")
SESSION=$(echo "$RESP" | grep -m1 "sessionKey" | sed 's/.*"sessionKey":"\([^"]*\)".*/\1/')
echo "session=$SESSION threshold=$THRESHOLD turns=$((TURNS))"
echo "turn 1: tool_call=$(echo "$RESP" | grep -c 'event:tool_call') error=$(echo "$RESP" | grep -c 'event:error')"
sleep 2

for i in $(seq 2 $TURNS); do
  MSG=$(turn_message $i)
  RESP=$(curl -s -N --max-time 120 -X POST $BASE/api/v1/chat -H "Content-Type: application/json" \
    -d "{\"sessionKey\":\"$SESSION\",\"message\":\"$MSG\"}")
  echo "turn $i: tool_call=$(echo "$RESP" | grep -c 'event:tool_call') error=$(echo "$RESP" | grep -c 'event:error')"
  sleep 2
done

echo "--- 该 session 的 LLM 用量汇总 ---"
docker exec agent-platform-mysql mysql -uroot -proot agent_platform -e \
  "SELECT COUNT(*) AS calls, SUM(prompt_tokens) AS prompt_sum, SUM(completion_tokens) AS completion_sum, \
          SUM(prompt_tokens+completion_tokens) AS total \
    FROM llm_usage WHERE session_key='$SESSION';" 2>/dev/null
echo "--- 逐调用 prompt_tokens 趋势（前 5 + 后 5）---"
docker exec agent-platform-mysql mysql -uroot -proot agent_platform -e \
  "(SELECT id, prompt_tokens FROM llm_usage WHERE session_key='$SESSION' ORDER BY id ASC LIMIT 5) \
   UNION ALL \
   (SELECT id, prompt_tokens FROM llm_usage WHERE session_key='$SESSION' ORDER BY id DESC LIMIT 5);" 2>/dev/null
echo "（对比两轮：total 降幅 = 1 - 开启/关闭；末次调用 prompt 降幅 = 单次上下文规模对比）"
