/* 管理页逻辑：Agent CRUD + 热重载 + Token 统计（D2, D3, E4） */
(function () {
  "use strict";

  var TOKEN = new URLSearchParams(location.search).get("token") || "";
  if (TOKEN) {
    var chatLink = document.getElementById("chatLink");
    chatLink.href = "/?token=" + encodeURIComponent(TOKEN);
  }

  function api(path, options) {
    options = options || {};
    options.headers = options.headers || {};
    if (TOKEN) options.headers["X-Access-Token"] = TOKEN;
    if (options.body) options.headers["Content-Type"] = "application/json";
    return fetch(path, options).then(function (r) {
      var isJson = (r.headers.get("content-type") || "").indexOf("json") >= 0;
      var body = isJson ? r.json().catch(function () { return null; }) : r.text();
      return body.then(function (data) {
        if (!r.ok) throw new Error(data && data.error ? data.error : ("HTTP " + r.status));
        return data;
      });
    });
  }

  function toast(msg) {
    var t = document.createElement("div");
    t.className = "toast show";
    t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(function () { t.classList.remove("show"); }, 1800);
    setTimeout(function () { t.remove(); }, 2200);
  }

  function esc(s) {
    return (s == null ? "" : String(s)).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  // ---------- Tabs ----------
  document.querySelectorAll(".tab").forEach(function (tab) {
    tab.addEventListener("click", function () {
      document.querySelectorAll(".tab").forEach(function (t) { t.classList.remove("active"); });
      document.querySelectorAll(".panel").forEach(function (p) { p.classList.remove("active"); });
      tab.classList.add("active");
      var name = tab.getAttribute("data-tab");
      document.getElementById("panel-" + name).classList.add("active");
      if (name === "tokens") loadStats();
      if (name === "groups") loadGroups();
      if (name === "model") loadModel();
    });
  });

  // ---------- Agents ----------
  var editingId = null;
  var modal = document.getElementById("agentModal");

  function loadAgents() {
    api("/api/agents").then(function (list) {
      var body = document.getElementById("agentsBody");
      if (!list || list.length === 0) {
        body.innerHTML = '<tr><td colspan="9" class="hint">暂无成员，点击"新增成员"。</td></tr>';
        return;
      }
      body.innerHTML = list.map(function (a) {
        return '<tr class="' + (a.active ? "" : "inactive") + '">' +
          '<td class="avatar-cell" style="font-size:20px">' + esc(a.avatar || "🤖") + "</td>" +
          "<td>" + esc(a.name) + "</td>" +
          '<td style="max-width:320px">' + esc(short(a.persona, 90)) + "</td>" +
          "<td>" + a.verbosity + "</td><td>" + a.emojiTendency + "</td>" +
          "<td>" + a.responseSpeed + "</td><td>" + a.eagernessBias + "</td>" +
          "<td>" + (a.active ? "启用" : "已停用") + "</td>" +
          '<td><span class="tool-link" data-act="edit" data-id="' + esc(a.id) + '">编辑</span> ' +
            (a.active
              ? '<span class="tool-link" data-act="del" data-id="' + esc(a.id) + '">停用</span>'
              : '<span class="tool-link" data-act="enable" data-id="' + esc(a.id) + '">启用</span>') +
          "</td></tr>";
      }).join("");
      window.__agents = list;
      populatePeerList(list);
    }).catch(function (e) { toast("加载失败：" + e.message); });
  }

  function short(s, n) {
    if (!s) return "";
    s = s.replace(/\s+/g, " ");
    return s.length > n ? s.substring(0, n) + "…" : s;
  }

  function openModal(agent) {
    editingId = agent ? agent.id : null;
    document.getElementById("modalTitle").textContent = agent ? "编辑成员 · " + agent.name : "新增成员";
    document.getElementById("f_name").value = agent ? agent.name : "";
    document.getElementById("f_avatar").value = agent ? (agent.avatar || "") : "";
    document.getElementById("f_persona").value = agent ? (agent.persona || "") : "";
    document.getElementById("f_verbosity").value = agent ? agent.verbosity : 5;
    document.getElementById("f_emoji").value = agent ? agent.emojiTendency : 5;
    document.getElementById("f_speed").value = agent ? agent.responseSpeed : 5;
    document.getElementById("f_eager").value = agent ? agent.eagernessBias : 5;
    fillRelations(agent ? agent.relationships : "");
    document.getElementById("f_active").value = agent ? String(!!agent.active) : "true";
    modal.classList.remove("hidden");
  }

  function closeModal() { modal.classList.add("hidden"); editingId = null; }

  function collect() {
    return {
      name: document.getElementById("f_name").value.trim(),
      avatar: document.getElementById("f_avatar").value.trim(),
      persona: document.getElementById("f_persona").value,
      verbosity: parseInt(document.getElementById("f_verbosity").value, 10) || 5,
      emojiTendency: parseInt(document.getElementById("f_emoji").value, 10) || 5,
      responseSpeed: parseInt(document.getElementById("f_speed").value, 10) || 5,
      eagernessBias: parseInt(document.getElementById("f_eager").value, 10) || 5,
      relationships: readRelations(),
      active: document.getElementById("f_active").value === "true"
    };
  }

  function saveAgent() {
    var body = collect();
    if (!body.name) { toast("群昵称不能为空"); return; }
    if (advMode && body.relationships && !isJson(body.relationships)) {
      if (!confirm("这段内容不是合法 JSON，将作为纯文本关系描述保存（模型照样能读懂）。继续？")) return;
    }
    var req = editingId
      ? api("/api/agents/" + encodeURIComponent(editingId), { method: "PUT", body: JSON.stringify(body) })
      : api("/api/agents", { method: "POST", body: JSON.stringify(body) });
    req.then(function () { toast("已保存，热重载生效"); closeModal(); loadAgents(); })
       .catch(function (e) { toast("保存失败：" + e.message); });
  }

  // ---------- 群内关系编辑器 ----------
  // 存储仍是「对方昵称 → {熟悉度/好感/内部梗}」的 JSON 结构：{"对方":{"familiarity":8,"affinity":9,"meme":"..."}}
  // 这里只是把它换成一个中文可视化编辑器，用户不用碰 JSON 和英文字段名。
  var advMode = false;

  function isJson(s) {
    try { JSON.parse(s); return true; } catch (e) { return false; }
  }

  /** 把昵称填进 datalist：既能下拉选现有成员，也能手动输入新名字。 */
  function populatePeerList(list) {
    var dl = document.getElementById("peerList");
    dl.innerHTML = (list || []).map(function (a) {
      return '<option value="' + esc(a.name) + '">' +
        esc(a.avatar || "") + (a.active ? "" : "（已停用）") + "</option>";
    }).join("");
  }

  function relRow(peer, fam, aff, meme) {
    var row = document.createElement("div");
    row.className = "rel-row";
    row.innerHTML =
      '<input class="rel-peer" list="peerList" placeholder="对方群昵称" value="' + esc(peer) + '">' +
      '<span class="rel-num"><label>熟悉度</label>' +
        '<input class="rel-fam" type="number" min="0" max="10" placeholder="0-10" value="' + esc(fam) + '"></span>' +
      '<span class="rel-num"><label>好感</label>' +
        '<input class="rel-aff" type="number" min="0" max="10" placeholder="0-10" value="' + esc(aff) + '"></span>' +
      '<input class="rel-meme" placeholder="内部梗 / 态度，例如：总爱逗她" value="' + esc(meme) + '">' +
      '<span class="tool-link rel-del" title="删除这条关系">删除</span>';
    row.querySelector(".rel-del").addEventListener("click", function () {
      row.remove();
      syncRelEmpty();
    });
    return row;
  }

  function syncRelEmpty() {
    var has = document.getElementById("relRows").children.length > 0;
    document.getElementById("relEmpty").style.display = has ? "none" : "";
  }

  function addRelRow(peer, fam, aff, meme) {
    document.getElementById("relRows").appendChild(
      relRow(peer || "", fam == null ? "" : fam, aff == null ? "" : aff, meme || ""));
    syncRelEmpty();
  }

  /** 打开弹层时回填：能解析成 JSON 就填进可视化行，否则退回高级模式显示原文。 */
  function fillRelations(raw) {
    document.getElementById("relRows").innerHTML = "";
    setAdvMode(false);
    document.getElementById("f_rel").value = "";
    raw = (raw || "").trim();
    if (!raw) { syncRelEmpty(); return; }
    if (!isJson(raw)) { document.getElementById("f_rel").value = raw; setAdvMode(true); return; }
    var obj = JSON.parse(raw);
    Object.keys(obj).forEach(function (peer) {
      var v = obj[peer];
      if (v && typeof v === "object") {
        addRelRow(peer, v.familiarity, v.affinity, v.meme != null ? v.meme : (v.tone || ""));
      } else {
        // 值不是对象（例如 {"老王":"关系一般"}）：塞进"内部梗"一栏，不丢信息
        addRelRow(peer, "", "", v == null ? "" : String(v));
      }
    });
    syncRelEmpty();
  }

  /** 读表单 → 关系 JSON 字符串（空则返回 null）。 */
  function readRelations() {
    if (advMode) return document.getElementById("f_rel").value.trim() || null;
    var out = {};
    Array.prototype.forEach.call(document.querySelectorAll("#relRows .rel-row"), function (row) {
      var peer = row.querySelector(".rel-peer").value.trim();
      if (!peer) return;
      var e = {};
      var fam = row.querySelector(".rel-fam").value.trim();
      var aff = row.querySelector(".rel-aff").value.trim();
      var meme = row.querySelector(".rel-meme").value.trim();
      if (fam !== "") e.familiarity = parseInt(fam, 10) || 0;
      if (aff !== "") e.affinity = parseInt(aff, 10) || 0;
      if (meme !== "") e.meme = meme;
      // 只有昵称、没有任何维度时，也保留（存成空对象），避免用户白填
      out[peer] = e;
    });
    return Object.keys(out).length ? JSON.stringify(out) : null;
  }

  function setAdvMode(on) {
    advMode = on;
    document.getElementById("relEditor").classList.toggle("hidden", on);
    document.getElementById("relRaw").classList.toggle("hidden", !on);
    document.getElementById("relAdvToggle").textContent =
      on ? "返回简化的编辑方式" : "高级（直接改 JSON）";
  }

  /** 切换模式时双向同步，避免用户在一种模式里的输入丢失。 */
  function toggleAdvMode() {
    if (!advMode) {
      var json = readRelations();
      document.getElementById("f_rel").value = json || "";
      setAdvMode(true);
    } else {
      var raw = document.getElementById("f_rel").value.trim();
      if (raw && !isJson(raw)) {
        if (!confirm("这段内容不是 JSON，无法用简化编辑器展示。仍要切换吗？（内容会丢失）")) return;
        raw = "";
      }
      setAdvMode(false);
      document.getElementById("relRows").innerHTML = "";
      if (raw) fillRelations(raw);
      syncRelEmpty();
    }
  }

  function agentAction(act, id) {
    if (act === "edit") {
      var a = (window.__agents || []).find(function (x) { return x.id === id; });
      if (a) openModal(a);
    } else if (act === "del") {
      if (!confirm("确认停用该成员？（逻辑删除，历史消息保留）")) return;
      api("/api/agents/" + encodeURIComponent(id), { method: "DELETE" })
        .then(function () { toast("已停用"); loadAgents(); })
        .catch(function (e) { toast("操作失败：" + e.message); });
    } else if (act === "enable") {
      api("/api/agents/" + encodeURIComponent(id), { method: "PUT", body: JSON.stringify({ active: true }) })
        .then(function () { toast("已启用"); loadAgents(); })
        .catch(function (e) { toast("操作失败：" + e.message); });
    }
  }

  // ---------- 群聊管理（E1 多群） ----------
  var groups = [];

  function chatUrl(groupId) {
    var u = "/?g=" + encodeURIComponent(groupId);
    if (TOKEN) u += "&token=" + encodeURIComponent(TOKEN);
    return u;
  }

  function loadGroups() {
    return api("/api/groups").then(function (list) {
      groups = list || [];
      renderGroups();
    }).catch(function (e) { toast("群列表加载失败：" + e.message); });
  }

  function renderGroups() {
    var box = document.getElementById("groupCards");
    if (!groups.length) {
      box.innerHTML = '<div class="hint">还没有群聊，点「新建群聊」或在群聊页发起。</div>';
      return;
    }
    box.innerHTML = groups.map(function (g) {
      return '<div class="group-card">' +
        '<div class="gc-avatar">' + esc(g.avatar || "💬") + "</div>" +
        '<div class="gc-main">' +
          '<div class="gc-name">' + esc(g.name) +
            (g.sample ? '<span class="gi-badge">示例</span>' : "") +
            (g.stopped ? '<span class="gi-badge paused">已停止</span>' : "") + "</div>" +
          '<div class="gc-meta">' + g.memberCount + " 名成员 ｜ " + g.messageCount + " 条消息" +
            (g.lastMessageAt ? " ｜ 最后消息 " + esc(short(g.lastMessageAt, 16)) : " ｜ 还没有消息") +
            " ｜ id: " + esc(g.id) + "</div>" +
        "</div>" +
        '<div class="gc-actions">' +
          '<a class="gc-link" href="' + chatUrl(g.id) + '" target="_blank">进群</a>' +
          '<span class="gc-link" data-act="edit" data-id="' + esc(g.id) + '">信息 / 成员</span>' +
          '<span class="gc-link" data-act="stop" data-id="' + esc(g.id) + '">' + (g.stopped ? "恢复聊天" : "停止聊天") + "</span>" +
          (g.sample ? "" : '<span class="gc-link" data-act="del" data-id="' + esc(g.id) + '">解散</span>') +
        "</div>" +
      "</div>";
    }).join("");
  }

  function groupAction(act, id) {
    var g = groups.find(function (x) { return x.id === id; });
    if (!g) return;
    if (act === "edit") {
      openGroupModal(g);
    } else if (act === "stop") {
      api("/api/config/chat-state", {
        method: "POST",
        body: JSON.stringify({ groupId: id, stopped: !g.stopped })
      }).then(function () { toast(g.stopped ? "已恢复聊天" : "已停止聊天"); loadGroups(); })
        .catch(function (e) { toast("操作失败：" + e.message); });
    } else if (act === "del") {
      if (!confirm("解散「" + g.name + "」？\n该群的消息、群共识、记忆进度与 token 流水都会一起删除，不可恢复。")) return;
      api("/api/groups/" + encodeURIComponent(id), { method: "DELETE" })
        .then(function () { toast("已解散"); loadGroups(); loadStats(); })
        .catch(function (e) { toast("解散失败：" + e.message); });
    }
  }

  var groupModal = document.getElementById("groupModal");
  var editingGroupId = null;
  var groupMemberIds = [];

  function openGroupModal(g) {
    editingGroupId = g ? g.id : null;
    document.getElementById("groupModalTitle").textContent = g ? ("群聊信息 · " + g.name) : "新建群聊";
    document.getElementById("g_name").value = g ? g.name : "";
    document.getElementById("g_avatar").value = g ? (g.avatar || "") : "";
    document.getElementById("g_notice").value = g ? (g.notice || "") : "";
    groupMemberIds = [];

    var render = function () {
      var box = document.getElementById("g_members");
      if (!(window.__agents || []).length) {
        box.innerHTML = '<div class="hint">还没有人设，请先到「Agent 管理」新增成员。</div>';
        return;
      }
      box.innerHTML = (window.__agents || []).filter(function (a) { return a.active; }).map(function (a) {
        var checked = groupMemberIds.indexOf(a.id) >= 0 ? " checked" : "";
        return '<label class="pick-item"><input type="checkbox" value="' + esc(a.id) + '"' + checked + ">" +
          "<span>" + esc(a.avatar || "🤖") + "</span>" +
          "<span class='pick-name'>" + esc(a.name) + "</span>" +
          "<span class='pick-persona'>" + esc(short(a.persona, 40)) + "</span></label>";
      }).join("");
    };

    if (g) {
      api("/api/groups/" + encodeURIComponent(g.id) + "/members").then(function (list) {
        groupMemberIds = (list || []).map(function (a) { return a.id; });
        render();
      }).catch(render);
    } else {
      render();
    }
    groupModal.classList.remove("hidden");
  }

  function closeGroupModal() {
    groupModal.classList.add("hidden");
    editingGroupId = null;
    groupMemberIds = [];
  }

  function checkedMemberIds() {
    return Array.prototype.slice.call(document.querySelectorAll("#g_members input:checked"))
      .map(function (i) { return i.value; });
  }

  function saveGroup() {
    var name = document.getElementById("g_name").value.trim();
    if (!name) { toast("群名称不能为空"); return; }
    var ids = checkedMemberIds();
    if (!ids.length) { toast("请至少选择一个成员"); return; }
    var payload = {
      name: name,
      avatar: document.getElementById("g_avatar").value.trim(),
      notice: document.getElementById("g_notice").value,
      agentIds: ids
    };
    if (editingGroupId) {
      // 改名 + 差量同步成员（新勾选的拉进来，取消勾选的移出）
      var id = editingGroupId;
      api("/api/groups/" + encodeURIComponent(id), { method: "PUT", body: JSON.stringify(payload) })
        .then(function () {
          var toAdd = ids.filter(function (x) { return groupMemberIds.indexOf(x) < 0; });
          var toRemove = groupMemberIds.filter(function (x) { return ids.indexOf(x) < 0; });
          var jobs = [];
          if (toAdd.length) {
            jobs.push(api("/api/groups/" + encodeURIComponent(id) + "/members", {
              method: "POST", body: JSON.stringify({ agentIds: toAdd })
            }));
          }
          toRemove.forEach(function (agentId) {
            jobs.push(api("/api/groups/" + encodeURIComponent(id) + "/members/" + encodeURIComponent(agentId),
              { method: "DELETE" }));
          });
          Promise.all(jobs).then(function () {
            toast("已保存" + (toAdd.length ? "，新成员已进群打招呼" : ""));
            closeGroupModal();
            loadGroups();
          });
        })
        .catch(function (e) { toast("保存失败：" + e.message); });
    } else {
      api("/api/groups", { method: "POST", body: JSON.stringify(payload) })
        .then(function (g) { toast("群「" + g.name + "」已创建"); closeGroupModal(); loadGroups(); })
        .catch(function (e) { toast("创建失败：" + e.message); });
    }
  }

  // ---------- Token 统计 ----------
  var scopeGroupId = null;

  function loadStats() {
    var q = scopeGroupId ? "?groupId=" + encodeURIComponent(scopeGroupId) : "";
    api("/api/stats/tokens" + q).then(function (t) {
      document.getElementById("statTotal").textContent = num(t.totalTokens);
      document.getElementById("statToday").textContent = num(t.todayTokens);
      document.getElementById("statIO").textContent = num(t.totalInput) + " / " + num(t.totalOutput);

      var scoped = !!t.scopeGroupId;
      document.getElementById("statByGroupWrap").classList.toggle("hidden", scoped);
      document.getElementById("statByAgentWrap").classList.toggle("hidden", !scoped);
      document.getElementById("statScope").classList.toggle("hidden", !scoped);
      document.getElementById("backToAllBtn").classList.toggle("hidden", !scoped);

      if (scoped) {
        document.getElementById("scopeName").textContent = t.scopeGroupName || t.scopeGroupId;
        document.getElementById("scopeHint").textContent = "（只统计这个群里的调用）";
        renderBars("barsAgent", t.byAgent);
      } else {
        renderDrillGroups(t.byGroup);
      }
      renderBars("barsPurpose", t.byPurpose);
    }).catch(function (e) { toast("统计加载失败：" + e.message); });
  }

  /** 群聊下钻列表：点一行进入该群的成员消耗明细。 */
  function renderDrillGroups(list) {
    var box = document.getElementById("drillGroups");
    if (!list || list.length === 0) {
      box.innerHTML = '<div class="hint">暂无数据</div>';
      return;
    }
    var max = list.reduce(function (m, x) { return Math.max(m, x.total); }, 1);
    box.innerHTML = list.map(function (x) {
      var pct = Math.max(2, Math.round(x.total / max * 100));
      var known = groups.some(function (g) { return g.id === x.key; });
      return '<div class="drill-row"' + (known ? ' data-id="' + esc(x.key) + '"' : "") + ">" +
        '<span class="dr-avatar">💬</span>' +
        '<span class="dr-main">' +
          '<span class="dr-name">' + esc(x.name) + "</span>" +
          '<span class="bar-track" style="display:block;margin-top:5px">' +
            '<span class="bar-fill" style="width:' + pct + '%"></span></span>' +
        "</span>" +
        '<span class="dr-val">' + num(x.total) + " tokens</span>" +
      "</div>";
    }).join("");
  }

  function renderBars(targetId, list) {
    var box = document.getElementById(targetId);
    if (!list || list.length === 0) {
      box.innerHTML = '<div class="hint">暂无数据</div>';
      return;
    }
    var max = list.reduce(function (m, x) { return Math.max(m, x.total); }, 1);
    box.innerHTML = list.map(function (x) {
      var pct = Math.max(2, Math.round(x.total / max * 100));
      return '<div class="bar-row">' +
        '<span class="label" title="' + esc(x.name) + '">' + esc(x.name) + "</span>" +
        '<span class="bar-track"><span class="bar-fill" style="width:' + pct + '%"></span></span>' +
        '<span class="val">' + num(x.total) + "</span>" +
      "</div>";
    }).join("");
  }

  function num(n) {
    if (n == null) return "0";
    return Number(n).toLocaleString("en-US");
  }

  // ---------- 模型配置（写 .env + 就地热更新） ----------
  var MODEL_GROUPS = ["chat", "gate", "embedding", "rerank"];
  var clearKeys = {};

  /**
   * 常用服务商预设：只帮用户填「接口地址 + 模型名 + 向量维度」，
   * API Key 永远要用户自己去控制台创建后粘进来。
   * 约定的键含义：chat=主力发言，gate=门控，embedding=向量，rerank=重排；空串表示"这一组不单独配"。
   */
  var MODEL_PRESETS = [
    {
      name: "阿里云百炼 / 通义千问（国内推荐）", dim: 1024, disableThinking: true,
      chat: ["https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"],
      gate: ["https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash"],
      embedding: ["https://dashscope.aliyuncs.com/compatible-mode/v1", "text-embedding-v4"],
      rerank: ["https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank", "gte-rerank-v2"]
    },
    {
      name: "DeepSeek 官方", dim: 1024, disableThinking: false,
      chat: ["https://api.deepseek.com/v1", "deepseek-chat"],
      gate: ["", ""], embedding: ["", ""], rerank: ["", ""]
    },
    {
      name: "硅基流动 SiliconFlow", dim: 1024, disableThinking: false,
      chat: ["https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"],
      gate: ["", ""],
      embedding: ["https://api.siliconflow.cn/v1", "BAAI/bge-m3"],
      rerank: ["https://api.siliconflow.cn/v1/rerank", "BAAI/bge-reranker-v2-m3"]
    },
    {
      name: "智谱 GLM", dim: 2048, disableThinking: false,
      chat: ["https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"],
      gate: ["", ""],
      embedding: ["https://open.bigmodel.cn/api/paas/v4", "embedding-3"],
      rerank: ["https://open.bigmodel.cn/api/paas/v4/rerank", "rerank"]
    },
    {
      name: "OpenAI", dim: 1536, disableThinking: false,
      chat: ["https://api.openai.com/v1", "gpt-4o-mini"],
      gate: ["", ""],
      embedding: ["https://api.openai.com/v1", "text-embedding-3-small"],
      rerank: ["", ""]
    },
    {
      name: "本地 Ollama（不需要 Key）", dim: 768, disableThinking: false,
      chat: ["http://localhost:11434/v1", "qwen2.5:7b"],
      gate: ["", ""],
      embedding: ["http://localhost:11434/v1", "nomic-embed-text"],
      rerank: ["", ""]
    }
  ];

  function modelEl(group, field) {
    return document.getElementById("m_" + group + "_" + field);
  }

  function initPresets() {
    var sel = document.getElementById("m_preset");
    sel.innerHTML = '<option value="-1">— 常用服务商：自动填「地址 + 模型」，Key 仍要你填 —</option>' +
      MODEL_PRESETS.map(function (p, i) { return '<option value="' + i + '">' + esc(p.name) + "</option>"; }).join("");
    sel.addEventListener("change", function () {
      var i = Number(sel.value);
      if (i >= 0) applyPreset(MODEL_PRESETS[i]);
    });
  }

  function applyPreset(p) {
    MODEL_GROUPS.forEach(function (g) {
      modelEl(g, "baseUrl").value = p[g][0];
      modelEl(g, "model").value = p[g][1];
    });
    if (p.dim) document.getElementById("m_vecDim").value = p.dim;
    document.getElementById("m_disableThinking").checked = !!p.disableThinking;
    toast("已填入「" + p.name + "」的地址与模型，请补 API Key");
  }

  function loadModel() {
    api("/api/config/model").then(function (m) {
      MODEL_GROUPS.forEach(function (g) {
        var d = m[g] || {};
        modelEl(g, "baseUrl").value = d.baseUrl || "";
        modelEl(g, "model").value = d.model || "";
        var keyInput = modelEl(g, "apiKey");
        keyInput.value = "";
        keyInput.placeholder = d.apiKeySet ? "已设置：" + d.apiKeyMasked + "（留空 = 不修改）" : "sk-...";
      });
      document.getElementById("m_vecDim").value = m.vecDimension;
      document.getElementById("m_disableThinking").checked = !!(m.chat && m.chat.disableThinking);
      clearKeys = {};
      renderModelStatus(m);
    }).catch(function (e) { toast("读取模型配置失败：" + e.message); });
  }

  function renderModelStatus(m) {
    var flags = [
      (m.chat && m.chat.configured) ? "主力模型 ✅" : "主力模型 ❌ 未配置（Agent 不会说话）",
      (m.gate && m.gate.configured) ? "门控模型：已单独配置" : "门控模型：复用主力模型",
      (m.embedding && m.embedding.configured) ? "向量模型：已启用" : "向量模型：未配置（记忆退化为最近若干条）",
      (m.rerank && m.rerank.configured) ? "重排模型：已启用" : "重排模型：未配置（直接按向量分排序）"
    ];
    var html = '<div class="status-line">配置文件：<code>' + esc(m.envPath) + "</code>" +
      (m.envFileExists ? "" : "（文件还不存在，保存后自动创建）") + "</div>" +
      '<div class="status-line">' + esc(flags.join("｜")) + "</div>";
    if (m.envOverrideKeys && m.envOverrideKeys.length) {
      html += '<div class="status-line warn">⚠️ 这些项被「系统环境变量」占住了，'
        + "写 .env 重启后也会被顶掉，要改请改系统环境变量：<code>"
        + esc(m.envOverrideKeys.join(", ")) + "</code></div>";
    }
    (m.warnings || []).forEach(function (w) {
      html += '<div class="status-line warn">⚠️ ' + esc(w) + "</div>";
    });
    if (!(m.chat && m.chat.configured)) {
      html += '<div class="status-line warn">提示：至少填「主力发言模型」的 baseUrl / API Key / 模型名，Agent 才能说话。</div>';
    }
    document.getElementById("modelStatus").innerHTML = html;
  }

  function saveModel() {
    var disableThinking = document.getElementById("m_disableThinking").checked;
    var body = {};
    MODEL_GROUPS.forEach(function (g) {
      var input = {
        baseUrl: modelEl(g, "baseUrl").value.trim(),
        model: modelEl(g, "model").value.trim(),
        disableThinking: disableThinking
      };
      var typed = modelEl(g, "apiKey").value.trim();
      if (clearKeys[g]) {
        input.clearApiKey = true;
      } else if (typed) {
        input.apiKey = typed;
      }
      body[g] = input;
    });
    var dim = parseInt(document.getElementById("m_vecDim").value, 10);
    if (dim > 0) body.vecDimension = dim;

    api("/api/config/model", { method: "PUT", body: JSON.stringify(body) })
      .then(function (m) {
        toast("已保存，立即生效（无需重启）");
        loadModel();
        renderModelStatus(m);
      })
      .catch(function (e) { toast("保存失败：" + e.message); });
  }

  // ---------- 事件 ----------
  document.getElementById("newAgentBtn").addEventListener("click", function () { openModal(null); });
  document.getElementById("saveAgentBtn").addEventListener("click", saveAgent);
  document.getElementById("cancelAgentBtn").addEventListener("click", closeModal);
  document.getElementById("refreshAgentsBtn").addEventListener("click", loadAgents);
  document.getElementById("refreshStatsBtn").addEventListener("click", loadStats);
  document.getElementById("backToAllBtn").addEventListener("click", function () {
    scopeGroupId = null;
    loadStats();
  });
  document.getElementById("drillGroups").addEventListener("click", function (e) {
    var row = e.target.closest(".drill-row");
    if (!row || !row.getAttribute("data-id")) return;
    scopeGroupId = row.getAttribute("data-id");
    loadStats();
  });
  document.getElementById("groupCards").addEventListener("click", function (e) {
    var link = e.target.closest("[data-act]");
    if (link) groupAction(link.getAttribute("data-act"), link.getAttribute("data-id"));
  });
  document.getElementById("newGroupBtnAdmin").addEventListener("click", function () { openGroupModal(null); });
  document.getElementById("refreshGroupsBtn").addEventListener("click", loadGroups);
  document.getElementById("saveGroupBtn").addEventListener("click", saveGroup);
  document.getElementById("cancelGroupBtn").addEventListener("click", closeGroupModal);
  groupModal.addEventListener("click", function (e) { if (e.target === groupModal) closeGroupModal(); });
  document.getElementById("reloadBtn").addEventListener("click", function () {
    api("/api/agents/reload", { method: "POST" })
      .then(function (r) { toast(r.message || "已热重载"); })
      .catch(function (e) { toast("热重载失败：" + e.message); });
  });
  document.getElementById("agentsBody").addEventListener("click", function (e) {
    var link = e.target.closest("[data-act]");
    if (link) agentAction(link.getAttribute("data-act"), link.getAttribute("data-id"));
  });
  modal.addEventListener("click", function (e) { if (e.target === modal) closeModal(); });
  document.getElementById("relAddBtn").addEventListener("click", function () {
    addRelRow();
    var rows = document.querySelectorAll("#relRows .rel-peer");
    if (rows.length) rows[rows.length - 1].focus();
  });
  document.getElementById("relAdvToggle").addEventListener("click", toggleAdvMode);

  // 模型配置：保存 / 重读 / 清除某组 Key
  initPresets();
  document.getElementById("saveModelBtn").addEventListener("click", saveModel);
  document.getElementById("reloadModelBtn").addEventListener("click", loadModel);
  document.getElementById("panel-model").addEventListener("click", function (e) {
    var link = e.target.closest("[data-clear]");
    if (!link) return;
    var g = link.getAttribute("data-clear");
    clearKeys[g] = true;
    var input = modelEl(g, "apiKey");
    input.value = "";
    input.placeholder = "（保存后将清空）";
    toast("已标记清空该组 Key，点「保存并立即生效」生效");
  });

  loadAgents();
  loadGroups();
  loadStats();

  // 支持 /admin.html#model 直达模型配置（群聊页的"模型未配置"横幅就指向这里）
  if (location.hash === "#model") {
    document.querySelector('.tab[data-tab="model"]').click();
  }
})();