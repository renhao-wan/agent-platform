"use strict";
/* ---------------- 登录 / 会话 ---------------- */

function showLogin() {
  $("login").hidden = false;
  $("console").hidden = true;
  $("confirm").hidden = true;
  closeSources();
}
function showConsole() {
  $("login").hidden = true;
  $("console").hidden = false;
  $("who-name").textContent = myName;
  const rb = $("who-role");
  rb.textContent = myRole || "—";
  rb.dataset.r = myRole;
  $("ident-role").textContent = myRole || "—";
  $("sources-open").hidden = !canOpenAdmin();
  $("ident-session").textContent = sessionKey ? sessionKey.slice(0, 8) : "待建立";
  $("hint-session").textContent = sessionKey ? "会话 " + sessionKey.slice(0, 8) : "";
  setStatus("idle", "空闲");
  renderGates();
  setComposerEnabled(!busy);
  $("message").focus();
  loadSessions();
  refreshUsage();
}
function logout() {
  ["token", "sessionKey", "myName", "myRole", "myPermissions"].forEach(k => sessionStorage.removeItem(k));
  location.reload();
}

/** 清空轨迹回到空态（新建对话与切换会话共用） */
function resetTrace() {
  [...traceEl.querySelectorAll(".node")].forEach(n => n.remove());
  traceEl.classList.add("empty");
  const hint = $("empty-hint");
  if (hint) hint.hidden = false;
  toolSteps = 0;
  pendingToolAt = null;
  $("ident-steps").textContent = "0";
  $("ident-tokens").textContent = "—";
  setBusy(false);
  closeConfirm();
}

/** 新建对话：丢掉 sessionKey，清空轨迹，回到空态。
 *  没有这一步，会话一旦建立就再也换不掉——聊过一次就被钉在同一个上下文里。 */
function newChat() {
  if (busy) return;
  sessionKey = null;
  sessionStorage.removeItem("sessionKey");
  resetTrace();
  $("ident-session").textContent = "待建立";
  $("hint-session").textContent = "";
  setStatus("idle", "空闲");
  $("message").value = "";
  setComposerEnabled(true);
  renderSessions(sessionList);
  $("message").focus();
}

/* ---------------- 历史会话 ---------------- */

/** 服务端 LocalDateTime 序列化为 ISO 字符串；DB 与容器时区是 UTC，补 Z 后按浏览器本地时区显示。
 *  只取「月-日 时:分」。 */
function fmtWhen(iso) {
  if (!iso) return "";
  const s = String(iso);
  const d = new Date(s.endsWith("Z") ? s : s + "Z");
  if (Number.isNaN(d.getTime())) return s.slice(5, 16).replace("T", " ");
  const p = n => String(n).padStart(2, "0");
  return p(d.getMonth() + 1) + "-" + p(d.getDate()) + " " + p(d.getHours()) + ":" + p(d.getMinutes());
}

function renderSessions(list) {
  const box = $("session-list");
  box.textContent = "";
  if (!list.length) {
    const empty = document.createElement("p");
    empty.className = "sess-empty";
    empty.textContent = "暂无历史会话";
    box.appendChild(empty);
    return;
  }
  list.forEach(item => {
    const row = document.createElement("div");
    row.className = "sess-row";

    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "sess";
    if (item.sessionKey === sessionKey) btn.dataset.cur = "1";
    const title = document.createElement("span");
    title.className = "t";
    title.textContent = item.title || "新对话";
    const when = document.createElement("span");
    when.className = "d";
    when.textContent = fmtWhen(item.createdAt);
    btn.append(title, when);
    btn.addEventListener("click", () => openSession(item.sessionKey));

    const del = document.createElement("button");
    del.type = "button";
    del.className = "sess-del";
    del.title = "删除会话";
    del.setAttribute("aria-label", "删除会话");
    del.textContent = "×";
    del.addEventListener("click", e => {
      e.stopPropagation();
      deleteSession(item.sessionKey, del);
    });

    row.append(btn, del);
    box.appendChild(row);
  });
}

async function loadSessions() {
  try {
    const resp = await fetch("/api/v1/sessions", { headers: authHeaders() });
    if (resp.status === 401) { expire(); return; }
    if (!resp.ok) return;
    sessionList = await resp.json();
    renderSessions(sessionList);
  } catch (e) {
    // 列表拉取失败不影响对话本身，历史入口静默缺席
  }
}

/** 会话累计 token：与历史同一套归属校验，失败只让该格显示「—」，不打断对话。 */
async function refreshUsage() {
  if (!sessionKey) { $("ident-tokens").textContent = "—"; return; }
  try {
    const resp = await fetch("/api/v1/sessions/" + encodeURIComponent(sessionKey) + "/usage",
                             { headers: authHeaders() });
    if (resp.status === 401) { expire(); return; }
    if (!resp.ok) { $("ident-tokens").textContent = "—"; return; }
    const u = await resp.json();
    const el = $("ident-tokens");
    el.textContent = Number(u.totalTokens || 0).toLocaleString("zh-CN");
    el.title = "prompt " + u.promptTokens + " · completion " + u.completionTokens +
               " · " + u.calls + " 次调用";
  } catch (e) {
    $("ident-tokens").textContent = "—";
  }
}

let deleteArmed = null;

/** 删除会话需要点两次：第一次把 × 变成「确认」，8 秒内再点才发请求。 */
async function deleteSession(key, btn) {
  if (busy) return;
  if (deleteArmed !== key) {
    deleteArmed = key;
    btn.dataset.armed = "1";
    btn.textContent = "确认";
    setTimeout(() => {
      if (deleteArmed === key) {
        deleteArmed = null;
        btn.dataset.armed = "";
        btn.textContent = "×";
      }
    }, 8000);
    return;
  }
  deleteArmed = null;
  btn.disabled = true;
  try {
    const resp = await fetch("/api/v1/sessions/" + encodeURIComponent(key),
                             { method: "DELETE", headers: authHeaders() });
    if (resp.status === 401) { expire(); return; }
    if (resp.status === 403) { addError("无权删除该会话"); return; }
    if (!resp.ok && resp.status !== 404) {
      addError("删除会话失败（HTTP " + resp.status + "）");
      return;
    }
    if (key === sessionKey) newChat();
    await loadSessions();
  } catch (e) {
    addError("删除会话失败：" + e.message);
  }
}

/** 打开一个历史会话：恢复消息并把它设为后续续聊的 sessionKey。
 *  归属由后端按 user_id 校验——不属于当前用户时返回 403。 */
async function openSession(key) {
  if (busy || key === sessionKey) return;
  try {
    const resp = await fetch("/api/v1/sessions/" + encodeURIComponent(key) + "/messages",
                             { headers: authHeaders() });
    if (resp.status === 401) { expire(); return; }
    if (!resp.ok) { addError("无法打开该会话（HTTP " + resp.status + "）"); return; }
    const messages = await resp.json();

    resetTrace();
    sessionKey = key;
    sessionStorage.setItem("sessionKey", key);
    $("ident-session").textContent = key.slice(0, 8);
    $("hint-session").textContent = "会话 " + key.slice(0, 8);
    messages.forEach(m => {
      if (m.role === "USER") addUserLine(m.content, fmtWhen(m.createdAt));
      else if (m.role === "ASSISTANT") node("answer", fmtWhen(m.createdAt)).innerHTML = mdToHtml(m.content || "");
    });
    renderSessions(sessionList);
    setStatus("idle", "空闲");
    scrollDown();
    $("message").focus();
    refreshUsage();
  } catch (e) {
    addError("打开会话失败：" + e.message);
  }
}

$("new-chat").addEventListener("click", newChat);

$("login-form").addEventListener("submit", async e => {
  e.preventDefault();
  const btn = $("login-btn"), err = $("login-err");
  err.textContent = "";
  btn.disabled = true; btn.textContent = "验证中…";
  try {
    const resp = await fetch("/api/v1/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: $("login-user").value.trim(), password: $("login-pass").value })
    });
    if (!resp.ok) {
      const j = await resp.json().catch(() => ({}));
      err.textContent = j.message || ("登录失败 " + resp.status);
      return;
    }
    const j = await resp.json();
    token = j.token;
    myName = $("login-user").value.trim();
    myRole = j.role || "";
    myPermissions = Array.isArray(j.permissions) ? j.permissions : [];
    sessionStorage.setItem("token", token);
    sessionStorage.setItem("myName", myName);
    sessionStorage.setItem("myRole", myRole);
    sessionStorage.setItem("myPermissions", JSON.stringify(myPermissions));
    showConsole();
  } catch (ex) {
    err.textContent = "网络异常：" + ex.message;
  } finally {
    btn.disabled = false; btn.textContent = "进入控制台";
  }
});

$("login").addEventListener("click", e => {
  const c = e.target.closest("code[data-u]");
  if (!c) return;
  $("login-user").value = c.dataset.u;
  $("login-pass").value = c.dataset.p;
  $("login-user").focus();
});

$("logout").addEventListener("click", logout);

/* ---------------- 确认流 ---------------- */

/** 收起确认面板并作废当前 token：挂起项要么已被消耗，要么随运行中止一并失效 */
function closeConfirm() {
  if ($("confirm").hidden) return;
  pendingToken = null;
  $("confirm").hidden = true;
}
function askConfirm(token, message) {
  pendingToken = token;
  $("confirm-text").textContent = message;
  $("confirm").hidden = false;
  setStatus("wait", "等待确认");
  setBusy(false);
  $("btn-ok").focus();
}
async function confirmAction(approved) {
  const t = pendingToken;
  if (!t) return;
  closeConfirm();
  $("message").focus();          // 输入框若仍 disabled（流未结束）会静默忽略，由 send() 的 finally 兜底
  addUserLine(approved ? "✓ 已授权执行" : "✕ 已拒绝");
  setStatus("live", "推理中");
  try {
    const resp = await fetch("/api/v1/confirm", {
      method: "POST", headers: authHeaders(),
      body: JSON.stringify({ confirmToken: t, approved })
    });
    if (resp.status === 401) return expire();
    setBusy(true, approved ? "正在执行…" : "正在收尾…");
  } catch (e) {
    addError("确认请求失败：" + e.message);
  }
}
$("btn-ok").addEventListener("click", () => confirmAction(true));
$("btn-no").addEventListener("click", () => confirmAction(false));

function expire() {
  ["token", "sessionKey", "myName", "myRole", "myPermissions"].forEach(k => sessionStorage.removeItem(k));
  token = null; sessionKey = null;
  showLogin();
  $("login-err").textContent = "登录已过期，请重新登录";
}

/* ---------------- 轨迹渲染 ---------------- */

function addUserLine(text, when) {
  node("user", when).textContent = text;
}
function addError(text) {
  node("error").textContent = text;
}

/** 打字机：先用去格式的纯文本打出来，打完再整体换成渲染后的 HTML——
 *  避免逐字插入标签导致中途布局反复跳动。长回复按目标总时长压缩步长。 */
function typewriter(contentEl, md) {
  const plain = md.replace(/[#*`>|]/g, " ").replace(/\s+/g, " ").trim();
  const caret = document.createElement("span");
  caret.className = "caret";
  contentEl.appendChild(caret);

  let i = 0;
  const step = Math.max(2, Math.ceil(plain.length / 70));
  const t = setInterval(() => {
    i = Math.min(i + step, plain.length);
    contentEl.textContent = plain.slice(0, i);
    contentEl.appendChild(caret);
    scrollDown();
    if (i >= plain.length) {
      clearInterval(t);
      caret.remove();
      contentEl.innerHTML = mdToHtml(md);
      scrollDown();
    }
  }, 16);
}

function renderPlan(c) {
  const wrap = document.createElement("div");
  wrap.className = "steps";
  (c.steps || []).forEach(s => {
    const row = document.createElement("div");
    row.className = "step";
    const tx = document.createElement("span");
    tx.textContent = s;
    row.append(tx);
    wrap.appendChild(row);
  });
  const holder = node("plan");
  holder.appendChild(wrap);
}

function renderToolCall(c) {
  const holder = node("tool_call");
  const box = document.createElement("div");
  box.className = "call";
  const fn = document.createElement("span");
  fn.className = "fn";
  fn.textContent = c.tool;                       // textContent —— 不拼 innerHTML，避免工具名注入
  const arg = document.createElement("span");
  arg.className = "arg";
  let args = c.arguments || "{}";
  try { args = JSON.stringify(JSON.parse(args)); } catch (e) { /* 原样展示 */ }
  arg.textContent = "(" + args.replace(/,/g, "," + ZWSP) + ")";
  box.append(fn, arg);
  holder.appendChild(box);
}

function renderToolResult(c) {
  const holder = node("tool_result");
  const box = document.createElement("div");
  box.className = "result";
  const name = document.createElement("span");
  name.className = "rt";
  let label = c.tool || "工具";
  if (pendingToolAt !== null) {
    label += " · " + Math.max(0, Math.round(performance.now() - pendingToolAt)) + " ms";
    pendingToolAt = null;
  }
  name.textContent = label + " ";

  const raw = c.summary || "";
  // 先解析再截断：反过来做的话，超过截断长度的 JSON 永远解析失败，
  // 结果框里就只剩半截报文（曾经就是这样）。
  let summary = "", bad = false;
  try {
    const j = JSON.parse(raw);
    if (j && typeof j.code === "number") bad = j.code !== 0;   // 业务码非 0 即失败
    if (j && Array.isArray(j.data))           summary = "返回 " + j.data.length + " 条记录";
    else if (j && j.data && typeof j.data === "object") summary = JSON.stringify(j.data);
    else if (j && j.message)                  summary = j.message;
    else if (j && typeof j === "object")      summary = JSON.stringify(j);
  } catch (e) {
    // 非 JSON 的纯文本结果：闸门 2 的拒绝就是这种形态
    summary = raw;
    bad = /权限不足|失败|error/i.test(raw);
  }
  summary = summary.slice(0, 240);
  if (bad) box.dataset.err = "1";

  const body = document.createElement("span");
  body.textContent = "→ " + summary;
  box.append(name, body);
  holder.appendChild(box);
}

/* ---------------- SSE 事件处理 ---------------- */

function handle(event, data) {
  switch (event) {
    case "session":
      sessionKey = data.sessionKey;
      sessionStorage.setItem("sessionKey", sessionKey);
      $("ident-session").textContent = sessionKey.slice(0, 8);
      $("hint-session").textContent = "会话 " + sessionKey.slice(0, 8);
      loadSessions();     // 会话行已落库，历史列表立刻能看到（标题此时还是「新对话」）
      break;

    case "plan":
      setBusy(false);
      renderPlan(data);
      break;

    case "tool_call":
      $("ident-steps").textContent = ++toolSteps;
      pendingToolAt = performance.now();
      setBusy(true, "调用 " + (data.tool || "工具") + "…");
      renderToolCall(data);
      break;

    case "tool_result":
      renderToolResult(data);
      // 闸门 2 的拒绝在事件契约里只体现为这段文案（后端刻意保留的措辞），
      // 命中就把左栏那一格点亮——让界面与后端校验对上
      if (/权限不足/.test(data.summary || "")) flagGate2Blocked();
      setBusy(true, "推理中…");
      break;

    case "confirm_request":
      setBusy(false);
      node("confirm_request").textContent = data.message;   // 键必须是 KIND 里的键（事件名），否则落到兜底样式
      askConfirm(data.confirmToken, data.message);
      break;

    case "answer":
      setBusy(false);
      typewriter(node("answer"), data.content || "");
      break;

    case "error":
      setBusy(false);
      setStatus("idle", "空闲");
      closeConfirm();          // 运行已中止，挂起的确认项随之作废，面板不能留着
      addError(data.message || "未知错误");
      break;

    case "done": {
      setBusy(false);
      setStatus("idle", "空闲");
      closeConfirm();
      const parts = [];
      if (data.steps) parts.push(data.steps + " 步");
      if (data.elapsedMs) parts.push((data.elapsedMs / 1000).toFixed(1) + " 秒");
      if (data.totalTokens) {
        parts.push(Number(data.totalTokens).toLocaleString("zh-CN") + " tokens");
      }
      node("done").textContent = parts.length ? "本轮完成 · " + parts.join(" · ") : "本轮执行结束";
      refreshUsage();
      break;
    }
  }
}

/* ---------------- 发送 ---------------- */

$("message").addEventListener("input", () => {
  $("send").disabled = busy || !$("message").value.trim();
});

async function send(message) {
  if (busy || !message) return;
  busy = true;
  toolSteps = 0;
  $("ident-steps").textContent = "0";
  $("message").value = "";
  setComposerEnabled(false);

  addUserLine(message);
  setBusy(true, "推理中…");
  setStatus("live", "推理中");

  try {
    const resp = await fetch("/api/v1/chat", {
      method: "POST", headers: authHeaders(),
      body: JSON.stringify({ sessionKey, message })
    });
    if (resp.status === 401) { expire(); return; }
    if (!resp.ok) { addError("请求失败 HTTP " + resp.status); return; }

    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const blocks = buffer.split("\n\n");
      buffer = blocks.pop();
      for (const block of blocks) {
        let ev = "message", data = "";
        for (const line of block.split("\n")) {
          if (line.startsWith("event:")) ev = line.slice(6).trim();
          else if (line.startsWith("data:")) data += line.slice(5).trim();
        }
        if (!data) continue;
        try { handle(ev, JSON.parse(data)); } catch (e) { /* 忽略坏帧 */ }
      }
    }
  } catch (err) {
    setBusy(false);
    setStatus("idle", "空闲");
    addError("连接异常：" + err.message);
  } finally {
    busy = false;
    setBusy(false);
    // 挂起等确认时不改动状态灯——askConfirm 已把它置为等待确认
    if ($("confirm").hidden) setStatus("idle", "空闲");
    setComposerEnabled(true);
    loadSessions();       // 首条用户消息已落库：刷新会话标题与排序
    $("message").focus();
  }
}

$("chat-form").addEventListener("submit", e => {
  e.preventDefault();
  send($("message").value.trim());
});

