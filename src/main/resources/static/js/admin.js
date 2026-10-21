"use strict";
/* ---------------- 管理台（ADMIN） ---------------- */

let adminTab = "sources";
let srcList = [];
let toolList = [];
let srcEditing = null;      // 正在编辑的 sourceKey；null 表示新增
let srcRetireArmed = null;  // 已点过一次「下线」、等待二次确认的来源

const SRC_STATUS = {
  OK: "正常", FAILED: "失败", CONFLICT: "冲突",
  BUSY: "对账中", PENDING: "待对账", RETIRED: "已下线"
};
const KB_MODE = {
  vector: "向量检索", keyword: "关键词兜底", empty: "无命中", error: "失败"
};

/** 管理台 tab 与所需权限：入口可见 ≠ 每个 tab 可用，按权限逐个显隐。 */
const ADMIN_TABS = [
  { key: "sources", perm: "admin:source" },
  { key: "tools", perm: "admin:tool" },
  { key: "knowledge", perm: "admin:knowledge" },
  { key: "rbac", perm: "admin:rbac" }
];

function applyAdminTabs() {
  document.querySelectorAll(".tab").forEach(t => {
    const meta = ADMIN_TABS.find(x => x.key === t.dataset.tab);
    t.hidden = !!meta && !hasPerm(meta.perm);
  });
}

function setAdminTab(name) {
  adminTab = name;
  document.querySelectorAll(".tab").forEach(t => {
    t.dataset.on = t.dataset.tab === name ? "1" : "";
  });
  ["sources", "tools", "knowledge", "rbac"].forEach(k => { $("pane-" + k).hidden = k !== name; });
  if (name === "tools" && !toolList.length) loadTools();
  if (name === "rbac") loadRbac();
}

function openSources() {
  if (!canOpenAdmin()) { addError("当前账号没有管理权限"); return; }
  $("sources").hidden = false;
  applyAdminTabs();
  srcHint("");
  closeSourceForm();
  const first = ADMIN_TABS.find(t => hasPerm(t.perm));
  setAdminTab(first ? first.key : "sources");
  loadSources();
}
function closeSources() {
  $("sources").hidden = true;
  srcRetireArmed = null;
  closeSourceForm();
}
function closeSourceForm() {
  srcEditing = null;
  $("src-form").hidden = true;
  $("src-err").hidden = true;
  $("src-key-val").value = "";
}

function srcHint(text, danger) {
  const el = $("sources-hint");
  el.textContent = text || "";
  el.dataset.bad = danger ? "1" : "";
}

/** 管理端统一请求：401 走登录过期，403 明说权限，业务错误码一并抛出。 */
async function srcFetch(url, opts) {
  const resp = await fetch(url, Object.assign({ headers: authHeaders() }, opts || {}));
  if (resp.status === 401) { expire(); return null; }
  if (resp.status === 403) throw new Error("权限不足：当前账号不能执行该操作");
  const body = await resp.json().catch(() => null);
  if (!resp.ok || (body && typeof body.code === "number" && body.code !== 0)) {
    throw new Error((body && body.message) || "请求失败（HTTP " + resp.status + "）");
  }
  return body;
}

/* --- 契约来源 --- */

async function loadSources() {
  try {
    const body = await srcFetch("/admin/sources");
    if (!body) return;
    srcList = body.data || [];
    renderSourceStats();
    renderSources();
  } catch (e) {
    srcHint(e.message, true);
  }
}

function renderSourceStats() {
  const box = $("src-stats");
  box.textContent = "";
  const ok = srcList.filter(s => s.status === "OK").length;
  const bad = srcList.filter(s => s.status === "FAILED" || s.status === "CONFLICT").length;
  const tools = srcList.reduce((n, s) => n + (s.toolCount || 0), 0);
  const add = (value, label, kind) => {
    const el = document.createElement("span");
    el.className = "stat";
    if (kind) el.dataset.k = kind;
    const b = document.createElement("b");
    b.textContent = String(value);
    el.append(b, document.createTextNode(label));
    box.appendChild(el);
  };
  add(srcList.length, "个来源");
  add(ok, "正常");
  if (bad) add(bad, "异常", "bad");
  add(tools, "个工具");
}

function renderSources() {
  const box = $("src-list");
  box.textContent = "";
  if (!srcList.length) {
    const p = document.createElement("p");
    p.className = "pane-empty";
    p.textContent = "还没有登记来源。新增一个来源，平台会拉取它的契约并在对账后生成工具。";
    box.appendChild(p);
    return;
  }
  srcList.forEach(s => box.appendChild(sourceCard(s)));
}

function sourceCard(s) {
  const card = document.createElement("div");
  card.className = "src";
  card.dataset.s = s.status || "";

  const top = document.createElement("div");
  top.className = "src-top";
  const key = document.createElement("span");
  key.className = "src-key";
  key.textContent = s.sourceKey;
  const origin = document.createElement("span");
  origin.className = "src-origin";
  origin.dataset.o = s.origin;
  origin.textContent = s.origin === "ADMIN" ? "管理端" : "配置文件";
  const pill = document.createElement("span");
  pill.className = "pill";
  pill.dataset.s = s.status;
  pill.textContent = SRC_STATUS[s.status] || s.status || "—";
  top.append(key, origin, pill);

  const url = document.createElement("div");
  url.className = "src-url";
  url.textContent = s.contractUrl;

  const meta = document.createElement("div");
  meta.className = "src-meta";
  const addMeta = (label, value) => {
    const span = document.createElement("span");
    span.append(document.createTextNode(label + " "));
    const b = document.createElement("b");
    b.textContent = value;
    span.appendChild(b);
    meta.appendChild(span);
  };
  addMeta("工具", String(s.toolCount == null ? "—" : s.toolCount));
  addMeta("上次成功", fmtWhen(s.lastSuccessAt) || "—");
  addMeta("凭证", s.serviceKeyConfigured ? "已配置" : "未配置");
  if (!s.enabled) {
    const off = document.createElement("span");
    off.textContent = "已停用";
    meta.appendChild(off);
  }

  card.append(top, url, meta);

  const stale = Array.isArray(s.staleTools) ? s.staleTools : [];
  const breaking = Array.isArray(s.breakingChanges) ? s.breakingChanges : [];
  if (stale.length || breaking.length) {
    const det = document.createElement("details");
    det.className = "src-detail";
    const sum = document.createElement("summary");
    sum.textContent = "上次对账明细：下线 " + stale.length + " 条 · 破坏性变更 " + breaking.length + " 条";
    det.appendChild(sum);
    const items = document.createElement("div");
    items.className = "items";
    stale.forEach(name => {
      const d = document.createElement("div");
      d.className = "item";
      d.textContent = "下线 " + name;
      items.appendChild(d);
    });
    breaking.forEach(b => {
      const d = document.createElement("div");
      d.className = "item";
      const name = document.createElement("b");
      name.textContent = b.toolName || "（未知工具）";
      const why = document.createElement("span");
      why.className = "why";
      why.textContent = " · " + (b.type || "变更") + (b.detail ? "：" + b.detail : "");
      d.append(name, why);
      items.appendChild(d);
    });
    det.appendChild(items);
    card.appendChild(det);
  }

  if (s.lastError) {
    const err = document.createElement("div");
    err.className = "src-err";
    err.textContent = s.lastError.length > 180 ? s.lastError.slice(0, 180) + "…" : s.lastError;
    err.title = s.lastError;
    card.appendChild(err);
  }

  const acts = document.createElement("div");
  acts.className = "src-acts";
  if (s.status !== "RETIRED") {
    const syncBtn = document.createElement("button");
    syncBtn.type = "button";
    syncBtn.className = "btn-ghost";
    syncBtn.textContent = "立即对账";
    syncBtn.addEventListener("click", () => syncSource(s.sourceKey, syncBtn));
    acts.appendChild(syncBtn);

    const editBtn = document.createElement("button");
    editBtn.type = "button";
    editBtn.className = "btn-ghost";
    editBtn.textContent = "编辑";
    editBtn.addEventListener("click", () => openSourceForm(s));
    acts.appendChild(editBtn);

    if (s.origin === "ADMIN") {
      const delBtn = document.createElement("button");
      delBtn.type = "button";
      delBtn.className = "btn-ghost danger";
      delBtn.textContent = srcRetireArmed === s.sourceKey ? "确认下线" : "下线";
      delBtn.addEventListener("click", () => retireSource(s.sourceKey, delBtn));
      acts.appendChild(delBtn);
    } else {
      const note = document.createElement("span");
      note.className = "src-meta";
      note.textContent = "由配置文件管理，移除配置项即可下线";
      acts.appendChild(note);
    }
  }
  card.appendChild(acts);
  return card;
}

/** 来源变了，工具目录必然要重新拉——清掉缓存，当前在工具页就立即刷新。 */
function invalidateTools() {
  toolList = [];
  if (adminTab === "tools") loadTools();
}

async function syncSource(key, btn) {
  const label = btn.textContent;
  btn.disabled = true;
  btn.textContent = "对账中…";
  srcHint("");
  try {
    const body = await srcFetch("/admin/sources/" + encodeURIComponent(key) + "/sync",
                                 { method: "POST" });
    if (!body) return;
    const r = body.data || {};
    const text = SRC_STATUS[r.status] || r.status;
    srcHint(key + " 对账" + (r.status === "OK" ? "完成：" + text : "未成功：" + text) +
            (r.error ? "　" + r.error : ""), r.status !== "OK");
    await loadSources();
    invalidateTools();
  } catch (e) {
    srcHint(e.message, true);
  } finally {
    btn.disabled = false;
    btn.textContent = label;
  }
}

async function syncAllSources(btn) {
  const label = btn.textContent;
  btn.disabled = true;
  btn.textContent = "对账中…";
  srcHint("正在对全部来源执行对账…");
  try {
    const body = await srcFetch("/admin/sources/sync", { method: "POST" });
    if (!body) return;
    const reports = body.data || [];
    const bad = reports.filter(r => r.status !== "OK");
    srcHint("对账完成：成功 " + (reports.length - bad.length) + " / " + reports.length +
            (bad.length
              ? "；未成功：" + bad.map(r => r.sourceKey + "（" + (SRC_STATUS[r.status] || r.status) + "）").join("、")
              : ""),
            bad.length > 0);
    await loadSources();
    invalidateTools();
  } catch (e) {
    srcHint(e.message, true);
  } finally {
    btn.disabled = false;
    btn.textContent = label;
  }
}

async function retireSource(key, btn) {
  if (srcRetireArmed !== key) {
    srcRetireArmed = key;
    btn.textContent = "确认下线";
    setTimeout(() => {
      if (srcRetireArmed === key) {
        srcRetireArmed = null;
        btn.textContent = "下线";
      }
    }, 8000);
    return;
  }
  srcRetireArmed = null;
  btn.disabled = true;
  try {
    await srcFetch("/admin/sources/" + encodeURIComponent(key), { method: "DELETE" });
    srcHint(key + " 已下线，名下工具已软下线（不删行）");
    await loadSources();
    invalidateTools();
  } catch (e) {
    btn.disabled = false;
    btn.textContent = "下线";
    srcHint(e.message, true);
  }
}

function openSourceForm(s) {
  srcEditing = s ? s.sourceKey : null;
  $("src-form-title").textContent = s ? "编辑 " + s.sourceKey : "新增来源";
  $("src-key").value = s ? s.sourceKey : "";
  $("src-key").disabled = !!s;
  $("src-url").value = s ? s.contractUrl : "";
  $("src-base").value = (s && s.baseUrl) || "";
  $("src-key-name").value = "";
  $("src-key-val").value = "";
  $("src-enabled").checked = s ? s.enabled : true;
  $("src-err").hidden = true;
  $("src-form").hidden = false;
  $("pane-sources").querySelector(".pane-body").scrollTop = 0;
  if ($("src-key").disabled) $("src-url").focus();
  else $("src-key").focus();
}

/* --- 工具目录 --- */

async function loadTools() {
  const hint = $("tools-hint");
  hint.textContent = "加载中…";
  hint.dataset.bad = "";
  try {
    const body = await srcFetch("/admin/tools");
    if (!body) return;
    toolList = body.data || [];
    hint.textContent = "";
    renderTools();
  } catch (e) {
    hint.textContent = e.message;
    hint.dataset.bad = "1";
  }
}

function renderTools() {
  const box = $("tool-list");
  box.textContent = "";
  const q = $("tool-search").value.trim().toLowerCase();
  const filter = $("tool-filter").value;

  const filtered = toolList.filter(t => {
    if (filter === "enabled" && !t.enabled) return false;
    if (filter === "disabled" && t.enabled) return false;
    if (filter === "stale" && t.present) return false;
    if (!q) return true;
    return [t.name, t.description, t.sourceKey, t.requiredPermission]
      .filter(Boolean).some(v => String(v).toLowerCase().includes(q));
  });

  if (!filtered.length) {
    const p = document.createElement("p");
    p.className = "pane-empty";
    p.textContent = toolList.length
      ? "没有匹配的工具，换个关键词或过滤条件。"
      : "还没有工具。先登记契约来源，对账成功后即可在这里启停。";
    box.appendChild(p);
    return;
  }

  const groups = new Map();
  filtered.forEach(t => {
    const k = t.sourceKey || "（无来源）";
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(t);
  });

  for (const [src, items] of groups) {
    const group = document.createElement("div");
    group.className = "tool-group";
    const head = document.createElement("div");
    head.className = "tool-group-h";
    const b = document.createElement("b");
    b.textContent = src;
    const n = document.createElement("span");
    n.className = "n";
    n.textContent = items.filter(t => t.enabled).length + " / " + items.length + " 启用";
    head.append(b, n);
    group.appendChild(head);
    items.forEach(t => group.appendChild(toolRow(t)));
    box.appendChild(group);
  }
}

function toolRow(t) {
  const row = document.createElement("div");
  row.className = "tool";
  if (!t.enabled) row.dataset.off = "1";

  const m = document.createElement("span");
  m.className = "mth";
  m.dataset.m = t.httpMethod || "";
  m.textContent = t.httpMethod || "HTTP";

  const main = document.createElement("div");
  main.className = "tool-main";

  const nameLine = document.createElement("div");
  nameLine.className = "tool-name";
  const nm = document.createElement("span");
  nm.textContent = t.name || "（未命名）";
  nameLine.appendChild(nm);
  const required = t.requiredPermission;
  if (required) {
    const tag = document.createElement("span");
    tag.className = "tag admin";
    tag.textContent = "需 " + required;
    nameLine.appendChild(tag);
  }
  if (t.requireConfirm) {
    const tag = document.createElement("span");
    tag.className = "tag confirm";
    tag.textContent = "需确认";
    nameLine.appendChild(tag);
  }
  if (!t.present) {
    const tag = document.createElement("span");
    tag.className = "tag stale";
    tag.textContent = "契约已移除";
    nameLine.appendChild(tag);
  }

  const desc = document.createElement("div");
  desc.className = "tool-desc";
  desc.textContent = t.description || "（契约未提供描述）";
  desc.title = t.description || "";

  const url = document.createElement("div");
  url.className = "tool-url";
  url.textContent = (t.httpMethod || "") + " " + (t.urlTemplate || "");
  url.title = url.textContent;

  main.append(nameLine, desc, url);

  const sw = document.createElement("label");
  sw.className = "switch";
  sw.title = t.enabled ? "点击停用（对账不会覆盖人工启停）" : "点击启用";
  const input = document.createElement("input");
  input.type = "checkbox";
  input.checked = !!t.enabled;
  input.addEventListener("change", () => toggleTool(t, input));
  const tr = document.createElement("span");
  tr.className = "tr";
  sw.append(input, tr);

  row.append(m, main, sw);
  return row;
}

async function toggleTool(tool, input) {
  const next = input.checked;
  input.disabled = true;
  const hint = $("tools-hint");
  hint.textContent = "";
  hint.dataset.bad = "";
  try {
    await srcFetch("/admin/tools/" + tool.id + "/enabled?enabled=" + next, { method: "PUT" });
    tool.enabled = next;
    renderTools();
  } catch (e) {
    input.checked = !next;
    input.disabled = false;
    hint.textContent = (next ? "启用" : "停用") + "失败：" + e.message;
    hint.dataset.bad = "1";
  }
}

/* --- 知识检索沙盒 --- */

async function runKnowledgeSearch(e) {
  e.preventDefault();
  const query = $("kb-query").value.trim();
  if (!query) return;
  const req = {
    query,
    role: $("kb-role").value || null,
    topK: Number($("kb-topk").value) || 4
  };
  const btn = $("kb-run");
  btn.disabled = true;
  btn.textContent = "检索中…";
  $("kb-meta").textContent = "";
  $("kb-body").textContent = "";
  try {
    const body = await srcFetch("/admin/knowledge/search",
                                 { method: "POST", body: JSON.stringify(req) });
    if (!body) return;
    renderKnowledgeResult(body.data || {}, req);
  } catch (err) {
    const p = document.createElement("p");
    p.className = "pane-empty";
    p.textContent = err.message;
    $("kb-body").appendChild(p);
  } finally {
    btn.disabled = false;
    btn.textContent = "检索";
  }
}

function renderKnowledgeResult(r, req) {
  const mode = r.mode || "empty";
  const meta = $("kb-meta");
  const chip = document.createElement("span");
  chip.className = "kb-mode";
  chip.dataset.m = mode;
  chip.textContent = KB_MODE[mode] || mode;
  const line = document.createElement("span");
  line.textContent = (r.hits || []).length + " 条命中 · " + (r.elapsedMs || 0) + " ms · 角色 " +
                     (req.role || "不过滤");
  meta.append(chip, line);

  const out = $("kb-body");
  const hits = r.hits || [];
  if (!hits.length) {
    const p = document.createElement("p");
    p.className = "pane-empty";
    p.textContent = req.role
      ? "没有命中。角色过滤写在检索 SQL 内，" + req.role + " 看不到的文档不会进入候选集。"
      : "没有命中。";
    out.appendChild(p);
    return;
  }
  hits.forEach(h => {
    const card = document.createElement("div");
    card.className = "kb-hit";
    const head = document.createElement("div");
    head.className = "kb-hit-h";
    const t = document.createElement("span");
    t.className = "t";
    t.textContent = h.title || "（无标题）";
    const s = document.createElement("span");
    s.className = "s";
    s.textContent = h.section || "";
    const score = document.createElement("span");
    score.className = "score";
    score.textContent = typeof h.score === "number" ? h.score.toFixed(3) : "—";
    head.append(t, s, score);
    const p = document.createElement("p");
    p.textContent = h.text || "";
    card.append(head, p);
    out.appendChild(card);
  });
}

/* --- 角色权限（轻量 RBAC：单角色 + 角色-权限映射） --- */

let rbacRoles = [];
let rbacPermissions = [];
let rbacDraft = {};

async function loadRbac() {
  const hint = $("rbac-hint");
  hint.textContent = "";
  hint.dataset.bad = "";
  try {
    const [rolesBody, permsBody] = await Promise.all([
      srcFetch("/admin/rbac/roles"),
      srcFetch("/admin/rbac/permissions")
    ]);
    if (!rolesBody || !permsBody) return;
    rbacRoles = rolesBody.data || [];
    rbacPermissions = permsBody.data || [];
    rbacDraft = {};
    rbacRoles.forEach(role => {
      rbacDraft[role.code] = new Set(role.permissions || []);
    });
    renderRbac();
  } catch (e) {
    hint.textContent = e.message;
    hint.dataset.bad = "1";
  }
}

/** 已知权限码 = 内置 + 契约声明 + 该角色当前持有（可能来自手工添加）。 */
function rbacKnownFor(roleCode) {
  const known = new Set(rbacPermissions);
  const draft = rbacDraft[roleCode] || new Set();
  draft.forEach(p => known.add(p));
  return [...known].sort();
}

function renderRbac() {
  const box = $("rbac-body");
  box.textContent = "";
  if (!rbacRoles.length) {
    const p = document.createElement("p");
    p.className = "pane-empty";
    p.textContent = "没有角色。检查 app_role 表是否已初始化。";
    box.appendChild(p);
    return;
  }

  rbacRoles.forEach(role => {
    const card = document.createElement("div");
    card.className = "rbac-role";

    const head = document.createElement("div");
    head.className = "rbac-role-h";
    const title = document.createElement("b");
    title.textContent = role.name + " · " + role.code;
    head.appendChild(title);
    if (role.description) {
      const desc = document.createElement("span");
      desc.className = "n";
      desc.textContent = role.description;
      head.appendChild(desc);
    }
    card.appendChild(head);

    const grid = document.createElement("div");
    grid.className = "rbac-perms";
    rbacKnownFor(role.code).forEach(perm => {
      const label = document.createElement("label");
      const input = document.createElement("input");
      input.type = "checkbox";
      input.checked = rbacDraft[role.code].has(perm);
      input.addEventListener("change", () => {
        if (input.checked) rbacDraft[role.code].add(perm);
        else rbacDraft[role.code].delete(perm);
      });
      const text = document.createElement("span");
      text.textContent = perm === "*" ? "*（全部权限）" : perm;
      label.append(input, text);
      grid.appendChild(label);
    });
    card.appendChild(grid);

    const add = document.createElement("div");
    add.className = "rbac-add";
    const input = document.createElement("input");
    input.placeholder = "添加权限码，如 booking:cancel";
    input.autocomplete = "off";
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "btn-ghost";
    btn.textContent = "添加";
    const commit = () => {
      const value = input.value.trim();
      if (!value) return;
      rbacDraft[role.code].add(value);
      renderRbac();
    };
    btn.addEventListener("click", commit);
    input.addEventListener("keydown", e => { if (e.key === "Enter") { e.preventDefault(); commit(); } });
    add.append(input, btn);
    card.appendChild(add);

    box.appendChild(card);
  });
}

async function saveRbac() {
  const hint = $("rbac-hint");
  hint.textContent = "";
  hint.dataset.bad = "";

  const mine = rbacDraft[myRole];
  if (mine && ![...mine].some(p => p === "*" || p.startsWith("admin:"))) {
    if (!window.confirm("保存后当前角色将没有任何管理权限，可能被锁在管理台之外。仍要保存吗？")) return;
  }

  try {
    for (const role of rbacRoles) {
      await srcFetch("/admin/rbac/roles/" + encodeURIComponent(role.code) + "/permissions", {
        method: "PUT",
        headers: Object.assign({ "Content-Type": "application/json" }, authHeaders()),
        body: JSON.stringify({ permissions: [...(rbacDraft[role.code] || [])] })
      });
    }
    hint.textContent = "已保存。权限在下一个请求生效，不需要重新登录。";
    await loadRbac();
  } catch (e) {
    hint.textContent = e.message;
    hint.dataset.bad = "1";
  }
}

$("sources-open").addEventListener("click", openSources);
$("sources-close").addEventListener("click", closeSources);
$("sources-scrim").addEventListener("click", closeSources);
$("sources-refresh").addEventListener("click", () => { loadSources(); invalidateTools(); });
$("sources-sync-all").addEventListener("click", e => syncAllSources(e.currentTarget));
$("sources-new").addEventListener("click", () => openSourceForm(null));
$("src-cancel").addEventListener("click", closeSourceForm);
document.querySelectorAll(".tab").forEach(t => {
  t.addEventListener("click", () => setAdminTab(t.dataset.tab));
});
$("tool-search").addEventListener("input", renderTools);
$("tool-filter").addEventListener("change", renderTools);
$("kb-form").addEventListener("submit", runKnowledgeSearch);
$("rbac-refresh").addEventListener("click", loadRbac);
$("rbac-save").addEventListener("click", saveRbac);

$("src-form").addEventListener("submit", async e => {
  e.preventDefault();
  const payload = {
    contractUrl: $("src-url").value.trim(),
    baseUrl: $("src-base").value.trim() || null,
    serviceKeyName: $("src-key-name").value.trim() || null,
    enabled: $("src-enabled").checked
  };
  const secret = $("src-key-val").value;
  if (secret) payload.serviceKeyValue = secret;   // 留空表示不传，编辑时保持原值
  const editing = srcEditing;
  let url = "/admin/sources", method = "POST";
  if (editing) {
    url += "/" + encodeURIComponent(editing);
    method = "PUT";
  } else {
    payload.sourceKey = $("src-key").value.trim();
  }
  const errEl = $("src-err");
  errEl.hidden = true;
  try {
    await srcFetch(url, { method, body: JSON.stringify(payload) });
    srcHint(editing ? "已保存并重新对账" : "已新增并立即对账");
    closeSourceForm();
    await loadSources();
    invalidateTools();
  } catch (err) {
    errEl.textContent = err.message;
    errEl.hidden = false;
  }
});

document.addEventListener("keydown", e => {
  if (e.key === "Escape" && !$("sources").hidden) closeSources();
});

