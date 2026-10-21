"use strict";
const $ = id => document.getElementById(id);
const traceEl = $("trace"), stageEl = $("stage");
const ZWSP = String.fromCharCode(0x200b);   // 零宽空格：给长 JSON 造可折行点

let sessionKey = sessionStorage.getItem("sessionKey") || null;
let token      = sessionStorage.getItem("token") || null;
let myName     = sessionStorage.getItem("myName") || "";
let myRole     = sessionStorage.getItem("myRole") || "";
let myPermissions = [];
try {
  const saved = JSON.parse(sessionStorage.getItem("myPermissions") || "[]");
  myPermissions = Array.isArray(saved) ? saved : [];
} catch (_) { myPermissions = []; }

/** 当前身份是否拥有某权限码；* 为通配。 */
function hasPerm(p) {
  return myPermissions.includes("*") || myPermissions.includes(p);
}
/** 管理台入口：任一管理权限即可进入，具体 tab 再按权限显隐。 */
function canOpenAdmin() {
  return ["admin:console", "admin:source", "admin:tool", "admin:knowledge", "admin:rbac"].some(hasPerm);
}
let pendingToken = null;
let busy = false;
let toolSteps = 0;     // 本轮工具调用次数——这才是「步数」
let pendingToolAt = null;   // 工具调用开始时间：结果到达时算耗时
let sessionList = [];  // 历史会话缓存：切换会话后只重绘高亮，不重新请求

/* ---------------- 工具函数 ---------------- */

function authHeaders(extra) {
  const h = Object.assign({ "Content-Type": "application/json" }, extra || {});
  if (token) h["Authorization"] = "Bearer " + token;
  return h;
}

/** 极简 markdown 渲染器。刻意不引 marked / DOMPurify 之类的 CDN——
 *  现场演示不能赌网络，而威胁模型只是自家 LLM 的输出。
 *  做法：先把整段文本的 HTML 转义掉，再只插我们自己的标签，
 *  因此模型输出在结构上不可能注入标签或事件属性。 */
function mdToHtml(src) {
  const esc = s => s.replace(/&/g, "&amp;").replace(/</g, "&lt;")
                    .replace(/>/g, "&gt;").replace(/"/g, "&quot;");

  /** 行内：代码 → 粗体 → 斜体 → 链接。代码先用 NUL 哨兵抽出，避免其中的 * 被当成强调。
   *  哨兵不能用「空格+数字+空格」这类可见串——正文里的「可坐 6 人」会被误认为占位符。 */
  const inline = s => {
    const codes = [];
    s = s.replace(/`([^`]+)`/g, (_, c) => "\u0000" + (codes.push(c) - 1) + "\u0000");
    s = s.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    s = s.replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>");
    s = s.replace(/\[([^\]]+)\]\((https?:[^)\s]+)\)/g,
                  '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
    s = s.replace(/\u0000(\d+)\u0000/g, (_, i) => "<code>" + codes[+i] + "</code>");
    return s;
  };

  const isRow   = r => r.indexOf("|") >= 0;
  const isAlign = r => /^[\s|:-]+$/.test(r) && r.indexOf("-") >= 0;
  const split   = r => r.replace(/^\s*\|/, "").replace(/\|\s*$/, "").split("|").map(c => c.trim());

  const lines = esc(src).replace(/\r\n?/g, "\n").split("\n");
  const out = [];
  let i = 0, para = [], list = null;

  const flushPara = () => { if (para.length) { out.push("<p>" + para.map(inline).join("<br>") + "</p>"); para = []; } };
  const flushList = () => {
    if (!list) return;
    out.push("<" + list.tag + ">" + list.items.map(t => "<li>" + inline(t) + "</li>").join("") + "</" + list.tag + ">");
    list = null;
  };
  const flushAll = () => { flushPara(); flushList(); };

  while (i < lines.length) {
    const ln = lines[i];

    // 围栏代码块
    if (/^\s*```/.test(ln)) {
      flushAll();
      const lang = ln.replace(/^\s*```/, "").trim().replace(/[^\w-]/g, "");
      const buf = [];
      while (++i < lines.length && !/^\s*```/.test(lines[i])) buf.push(lines[i]);
      i++;
      out.push("<pre><code" + (lang ? ' class="lang-' + lang + '"' : "") + ">" + buf.join("\n") + "</code></pre>");
      continue;
    }

    // 表格：本行含 | 且下一行是分隔行
    if (isRow(ln) && i + 1 < lines.length && isAlign(lines[i + 1])) {
      flushAll();
      const head = split(ln);
      i += 2;
      const body = [];
      while (i < lines.length && isRow(lines[i]) && lines[i].trim()) body.push(split(lines[i++]));
      out.push("<table><thead><tr>" + head.map(h => "<th>" + inline(h) + "</th>").join("") + "</tr></thead><tbody>" +
               body.map(r => "<tr>" + head.map((_, k) => "<td>" + inline(r[k] || "") + "</td>").join("") + "</tr>").join("") +
               "</tbody></table>");
      continue;
    }

    // 分隔线
    if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(ln)) { flushAll(); out.push("<hr>"); i++; continue; }

    // 标题（只到 h3：轨迹里的回复不需要更深的层级）
    const h = ln.match(/^\s*(#{1,6})\s+(.*)$/);
    if (h) { flushAll(); const n = Math.min(h[1].length, 3); out.push("<h" + n + ">" + inline(h[2]) + "</h" + n + ">"); i++; continue; }

    // 引用（注意：走到这里 > 已经被 esc 转义成 &gt;，匹配的必须是转义后的形式）
    if (/^\s*&gt;\s?/.test(ln)) {
      flushAll();
      const buf = [];
      while (i < lines.length && /^\s*&gt;\s?/.test(lines[i])) buf.push(lines[i++].replace(/^\s*&gt;\s?/, ""));
      out.push("<blockquote>" + buf.map(inline).join("<br>") + "</blockquote>");
      continue;
    }

    // 列表
    const ul = ln.match(/^\s*[-*+]\s+(.*)$/);
    const ol = ln.match(/^\s*\d+\.\s+(.*)$/);
    if (ul || ol) {
      flushPara();
      const tag = ul ? "ul" : "ol";
      if (!list || list.tag !== tag) { flushList(); list = { tag: tag, items: [] }; }
      list.items.push(ul ? ul[1] : ol[1]);
      i++;
      continue;
    }

    if (!ln.trim()) { flushAll(); i++; continue; }
    para.push(ln);
    i++;
  }
  flushAll();
  return out.join("");
}

/** 时间戳（本地时钟，仅供轨迹阅读） */
function stamp() {
  const d = new Date();
  const p = n => String(n).padStart(2, "0");
  return p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
}

/* 事件类型 → 中文标签。类型名本身就是信息，不需要再加一列英文大写。 */
const KIND = {
  user:            { cls: "user",        label: "你说"     },
  plan:            { cls: "plan",        label: "执行计划" },
  tool_call:       { cls: "tool_call",   label: "调用工具" },
  tool_result:     { cls: "tool_result", label: "工具结果" },
  confirm_request: { cls: "confirm",     label: "等待确认" },
  answer:          { cls: "answer",      label: "最终回复" },
  error:           { cls: "error",       label: "出错"     },
  done:            { cls: "done",        label: "本轮完成" }
};

/** 在轨迹上追加一个节点，返回其内容容器。when 用于回放历史消息时还原时间。 */
function node(type, when) {
  const k = KIND[type] || { cls: "done", label: type };
  traceEl.classList.remove("empty");
  const hint = $("empty-hint");
  if (hint) hint.hidden = true;      // 空态引导只在第一条事件到达之前有意义

  const el = document.createElement("div");
  el.className = "node " + k.cls;

  const ts = document.createElement("span");
  ts.className = "ts";
  ts.textContent = when || stamp();

  const body = document.createElement("div");

  const kd = document.createElement("span");
  kd.className = "kind";
  kd.dataset.k = k.cls;
  kd.textContent = k.label;

  const content = document.createElement("div");
  content.className = "content";

  body.append(kd, content);
  el.append(ts, body);
  traceEl.appendChild(el);
  scrollDown();
  return content;
}

function scrollDown() {
  requestAnimationFrame(() => { stageEl.scrollTop = stageEl.scrollHeight; });
}

/* ---------------- 状态与左栏 ---------------- */

function setStatus(s, text) {
  $("status").dataset.s = s;
  $("status-text").textContent = text;
}
function setBusy(on, text) {
  $("busy").dataset.on = on ? "1" : "0";
  if (text) $("busy-text").textContent = text;
}
function setComposerEnabled(on) {
  $("message").disabled = !on;
  $("send").disabled = !on || !$("message").value.trim();
  $("new-chat").disabled = busy;
}

/** 依据当前权限刷新闸门面板——让抽象的安全模型成为界面上看得见的东西 */
function renderGates() {
  const all = hasPerm("*");

  $("gate-1-live").dataset.k = "pass";
  $("gate-1-live").textContent = "全部已启用工具 + 所需权限";

  const g2 = $("gate-2-live");
  if (all) {
    g2.dataset.k = "pass";
    g2.textContent = "当前身份拥有全部权限";
  } else {
    // 还没发生拦截，只是"会拦"——用琥珀，不用红
    g2.dataset.k = "warn";
    g2.textContent = myPermissions.length
      ? "执行点按权限码放行（已授 " + myPermissions.length + " 项）"
      : "执行点按权限码放行（未授业务权限）";
  }

  const g3 = $("gate-3-live");
  g3.dataset.k = "n/a";
  g3.textContent = "随调用触发";
}

/** 收到闸门 2 的拒绝时，让左栏那格同步亮起——把"界面"与"后端校验"对上 */
function flagGate2Blocked() {
  const g2 = $("gate-2-live");
  g2.dataset.k = "block";
  g2.textContent = "刚刚拦截了一次越权调用";
  $("gate-2").animate(
    [{ background: "var(--danger-wash)" }, { background: "transparent" }],
    { duration: 1400, easing: "ease-out" }
  );
}

