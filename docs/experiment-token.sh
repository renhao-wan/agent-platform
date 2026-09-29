#!/usr/bin/env bash
# Token 成本实验（M4.1）：同一 12 轮多轮会话，对比"裁剪关闭 / 裁剪开启"的 LLM 用量
# 前置：DASHSCOPE_API_KEY 已配置；dev 容器已启动
# 用法：
#   步骤 A：THRESHOLD=999999 bash docs/experiment-token.sh    # 裁剪关闭（阈值拉满）
#   步骤 B：THRESHOLD=6000  bash docs/experiment-token.sh     # 裁剪开启（默认）
#   每步先手动重启应用（阈值是启动配置），脚本输出该轮 session 的 token 总量
THRESHOLD=${THRESHOLD:-6000}
BASE=http://localhost:8000

SAY=("帮我订周三下午两点八人的会议室" "换成能投屏的房间" "订周五上午十点四人的" "取消周五那条" \
     "再订周四下午三点六人的" "最近三天我有哪些预订" "把周三那条改到周四同一时间" "查一下 B305 的情况" \
     "还有别的 8 人房间吗" "都订上试试" "算了，只保留最早的一条" "总结一下我现在的全部预订")

# 第一轮建立会话并取 sessionKey
RESP=$(curl -s -N --max-time 60 -X POST $BASE/api/v1/chat -H "Content-Type: application/json" \
  -d "{\"message\":\"${SAY[0]}\"}" | grep -m1 "sessionKey" | sed 's/.*"sessionKey":"\([^"]*\)".*/\1/')
SESSION=$RESP
echo "session=$SESSION threshold=$THRESHOLD"

for i in $(seq 1 11); do
  IDX=$((i+1 < ${#SAY[@]} ? i+1 : ${#SAY[@]}-1))
  curl -s -o /dev/null --max-time 60 -X POST $BASE/api/v1/chat -H "Content-Type: application/json" \
    -d "{\"sessionKey\":\"$SESSION\",\"message\":\"${SAY[$IDX]}\"}"
  echo "turn $((i+1)) done"
done

echo "--- 该 session 的 LLM 用量（llm_usage 汇总）---"
docker exec agent-platform-mysql mysql -uroot -proot agent_platform -N -e \
  "SELECT COUNT(*) AS calls, SUM(prompt_tokens) AS prompt_sum, SUM(completion_tokens) AS completion_sum, \
          SUM(prompt_tokens+completion_tokens) AS total \
    FROM llm_usage WHERE session_key='$SESSION';"
echo "（两轮实验分别执行后，对比 total：降幅 = 1 - 开启/关闭）"
