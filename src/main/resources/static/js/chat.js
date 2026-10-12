/* 群聊页逻辑（多群版 E1）：群列表、进群、发起群聊、群成员管理、
   SSE 实时流（按群过滤）、@提及、决策透视、停止开关、历史分页（C1~C5, D1, D2） */
(function () {
  "use strict";

  var TOKEN = new URLSearchParams(location.search).get("token") || "";
  var el = {
    list: document.getElementById("msgList"),
    input: document.getElementById("input"),
    sendBtn: document.getElementById("sendBtn"),
    stopBtn: document.getElementById("stopBtn"),
    membersBtn: document.getElementById("membersBtn"),
    typingLine: document.getElementById("typingLine"),
    groupName: document.getElementById("groupName"),
    tokenFoot: document.getElementById("tokenFoot"),
    banner: document.getElementById("banner"),
    mentionBar: document.getElementById("mentionBar"),
    groupList: document.getElementById("groupList"),
    newGroupBtn: document.getElementById("newGroupBtn"),
    newGroupModal: document.getElementById("newGroupModal"),
    ngName: document.getElementById("ngName"),
    ngAvatar: document.getElementById("ngAvatar"),
    ngAgents: document.getElementById("ngAgents"),
    adminLink: document.getElementById("adminLink"),
    side: document.getElementById("side"),
    sideMask: document.getElementById("sideMask"),
    sideToggle: document.getElementById("sideToggle"),
    infoDrawer: document.getElementById("infoDrawer"),
    infoMask: document.getElementById("infoMask"),
    infoBody: document.getElementById("infoBody"),
    drawer: document.getElementById("drawer"),
    drawerMask: document.getElementById("drawerMask"),
    drawerBody: document.getElementById("drawerBody"),
    drawerTitle: document.getElementById("drawerTitle")
  };

  var PAGE = 30;
  var state = {
    groupId: null,
    groups: [],
    agents: [],
    members: [],
    renderedIds: new Set(),   // 去重：SSE 回显 + 本地乐观渲染
    decisionsByMsg: {},       // messageId -> decisions[]
    oldestId: null,
    noMoreHistory: false,
    loadingHistory: false,
    stopped: false,
    typing: {},              // agentId -> { name, avatar, phase }
    thinking: null           // 门控阶段：{ names:[], count } 或 null
  };

  if (TOKEN) {
    el.adminLink.href = "/admin.html?token=" + encodeURIComponent(TOKEN);
  }

  // ---------- API ----------
  function api(path, options) {
    options = options || {};
    options.headers = options.headers || {};
    if (TOKEN) options.headers["X-Access-Token"] = TOKEN;
    if (options.body) options.headers["Content-Type"] = "application/json";
    return fetch(path, options).then(function (r) {
      var isJson = (r.headers.get("content-type") || "").indexOf("json") >= 0;
      var body = isJson ? r.json().catch(function () { return null; }) : r.text();
      return body.then(function (data) {
        if (!r.ok) {
          var msg = data && data.error ? data.error : ("HTTP " + r.status);
          throw new Error(msg);
        }
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

  function escapeHtml(s) {
    return (s == null ? "" : String(s)).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  function fmtTime(s) {
    if (!s) return "";
    return s.length >= 16 ? s.substring(11, 16) : s;
  }

  function short(s, n) {
    s = String(s == null ? "" : s).replace(/\s+/g, " ");
    return s.length > n ? s.substring(0, n) + "…" : s;
  }

  // ---------- 会话列表 ----------
  function renderGroups() {
    if (!state.groups.length) {
      el.groupList.innerHTML = '<div class="hint" style="padding:12px">还没有群聊，点上面「发起群聊」。</div>';
      return;
    }
    el.groupList.innerHTML = state.groups.map(function (g) {
      var active = g.id === state.groupId;
      var preview = g.lastMessage
        ? (g.lastSender ? g.lastSender + "：" : "") + g.lastMessage
        : "还没有人说话";
      return '<div class="group-item' + (active ? " active" : "") + '" data-id="' + escapeHtml(g.id) + '">' +
        '<span class="gi-avatar">' + escapeHtml(g.avatar || "💬") + "</span>" +
        '<span class="gi-main">' +
          '<span class="gi-top"><span class="gi-name">' + escapeHtml(g.name) + "</span>" +
            '<span class="gi-time">' + escapeHtml(fmtTime(g.lastMessageAt)) + "</span></span>" +
          '<span class="gi-preview">' + escapeHtml(short(preview, 34)) + "</span>" +
        "</span>" +
        '<span class="gi-tags">' +
          (g.sample ? '<span class="gi-badge">示例</span>' : "") +
          (g.stopped ? '<span class="gi-badge paused">暂停</span>' : "") +
          '<span class="gi-count">' + g.memberCount + "人</span>" +
        "</span>" +
      "</div>";
    }).join("");
  }

  var groupsTimer = null;
  function scheduleGroupsRefresh() {
    if (groupsTimer) return;
    groupsTimer = setTimeout(function () { groupsTimer = null; loadGroups(false); }, 1200);
  }

  function loadGroups(reselect) {
    return api("/api/groups").then(function (list) {
      state.groups = list || [];
      renderGroups();
      if (reselect === false) return;
      var want = new URLSearchParams(location.search).get("g") || state.groupId;
      var exists = state.groups.some(function (g) { return g.id === want; });
      state.groupId = exists ? want : (state.groups[0] ? state.groups[0].id : null);
      if (state.groupId) enterGroup(state.groupId);
    }).catch(function (e) { toast("群列表加载失败：" + e.message); });
  }

  // ---------- 进群 ----------
  function enterGroup(id) {
    state.groupId = id;
    state.renderedIds = new Set();
    state.decisionsByMsg = {};
    state.oldestId = null;
    state.noMoreHistory = false;
    state.typing = {};
    state.thinking = null;
    el.list.innerHTML = '<div class="load-more" id="loadMore">向上滚动加载更早的消息</div>';
    el.loadMore = document.getElementById("loadMore");
    refreshStatus();
    el.mentionBar.classList.add("hidden");
    renderGroups();

    var url = new URL(location.href);
    url.searchParams.set("g", id);
    history.replaceState(null, "", url.toString());
    closeSideOnMobile();

    loadMeta();
    loadMembers();
    loadHistory(null);
  }

  function currentGroup() {
    return state.groups.filter(function (g) { return g.id === state.groupId; })[0] || null;
  }

  // ---------- 渲染 ----------
  function nearBottom() {
    return el.list.scrollHeight - el.list.scrollTop - el.list.clientHeight < 120;
  }

  function renderMessage(m) {
    if (m == null || m.id == null || state.renderedIds.has(m.id)) return;
    state.renderedIds.add(m.id);
    if (m.senderType === "SYSTEM") {
      var sys = document.createElement("div");
      sys.className = "msg-system";
      sys.setAttribute("data-id", m.id);
      sys.innerHTML = '<div class="sys-pill">' + escapeHtml(m.content) + "</div>";
      el.list.appendChild(sys);
      keepTypingRowsLast();
      afterAppend(m.id);
      return;
    }

    // 该 agent 的真实消息到了：撤掉它之前那条「正在输入」占位气泡
    if (m.senderId && state.typing[m.senderId]) {
      delete state.typing[m.senderId];
      refreshStatus();
      renderTypingRows();
    }

    var right = m.senderType === "HUMAN";
    var row = document.createElement("div");
    row.className = "msg" + (right ? " msg-right" : "") + (m.recalled ? " recalled" : "");
    row.setAttribute("data-id", m.id);

    var content = m.recalled ? "[消息已撤回]" : m.content;
    var avatarBg = right ? "#a9e88b" : "#d6e4ff";
    row.innerHTML =
      '<div class="avatar" style="background:' + avatarBg + '">' + escapeHtml(m.avatar || "🧑") + "</div>" +
      '<div class="col">' +
        '<div class="sender">' + escapeHtml(m.senderName || "") + " · " + fmtTime(m.createdAt) + "</div>" +
        '<div class="bubble">' + escapeHtml(content) + "</div>" +
        '<div class="msg-tools">' +
          (right && !m.recalled ? '<span class="tool-link" data-act="recall">撤回</span>' : "") +
          (!right ? '<span class="tool-link" data-act="decisions">决策透视</span>' : "") +
        "</div>" +
      "</div>";
    el.list.appendChild(row);
    keepTypingRowsLast();
    afterAppend(m.id);
  }

  function afterAppend(id) {
    if (state.oldestId === null || id < state.oldestId) state.oldestId = id;
  }

  function scrollBottom() { el.list.scrollTop = el.list.scrollHeight; }

  // ---------- 历史 ----------
  function loadHistory(before) {
    if (state.loadingHistory || !state.groupId) return;
    state.loadingHistory = true;
    var url = "/api/messages?groupId=" + encodeURIComponent(state.groupId) + "&limit=" + PAGE +
      (before ? "&before=" + before : "");
    api(url).then(function (list) {
      state.loadingHistory = false;
      if (before) {
        if (!list || list.length === 0) {
          state.noMoreHistory = true;
          el.loadMore.textContent = "没有更早的消息了";
        }
        var prevTop = el.list.scrollHeight;
        (list || []).forEach(renderMessage);
        el.list.scrollTop = el.list.scrollHeight - prevTop + el.list.scrollTop;
      } else {
        el.loadMore.textContent = "向上滚动加载更早的消息";
        (list || []).forEach(renderMessage);
        scrollBottom();
      }
    }).catch(function (e) {
      state.loadingHistory = false;
      el.loadMore.textContent = "历史加载失败：" + e.message;
    });
  }

  // ---------- 发送 ----------
  function send() {
    var text = el.input.value.trim();
    if (!text || !state.groupId) return;
    el.input.value = "";
    el.input.style.height = "auto";
    el.mentionBar.classList.add("hidden");
    api("/api/messages", {
      method: "POST",
      body: JSON.stringify({ content: text, groupId: state.groupId })
    }).catch(function (e) { toast("发送失败：" + e.message); });
  }

  // ---------- 决策透视（D1） ----------
  function openDecisions(messageId) {
    el.drawerTitle.textContent = "决策透视 · 消息 #" + messageId;
    el.drawerBody.innerHTML = "<div class='hint'>加载中…</div>";
    el.drawer.classList.remove("hidden");
    el.drawerMask.classList.remove("hidden");
    if (state.decisionsByMsg[messageId]) {
      renderDecisions(state.decisionsByMsg[messageId]);
      return;
    }
    api("/api/decisions?messageId=" + messageId).then(renderDecisions).catch(function (e) {
      el.drawerBody.innerHTML = "<div class='hint'>加载失败：" + escapeHtml(e.message) + "</div>";
    });
  }

  function renderDecisions(list) {
    if (!list || list.length === 0) {
      el.drawerBody.innerHTML = "<div class='hint'>这条消息没有触发决策记录（例如聊天已停止）。</div>";
      return;
    }
    var html = "";
    list.forEach(function (d) {
      html += '<div class="decision-item">' +
        '<div class="decision-top">' +
          '<span class="d-avatar">' + escapeHtml(d.avatar || "🤖") + "</span>" +
          '<span class="d-name">' + escapeHtml(d.agentName || d.agentId) + "</span>" +
          '<span class="d-tag ' + escapeHtml(d.outcome || "SILENT") + '">' + tagText(d.outcome) + "</span>" +
        "</div>" +
        '<div class="decision-meta">' +
          "想发言：" + (d.wantToSpeak ? "是" : "否") + " ｜ 表达欲：" + d.eagerness + "/10" +
          (d.reason ? " ｜ " + escapeHtml(d.reason) : "") +
        "</div>" +
        '<div class="eager-bar"><span style="width:' + (d.eagerness * 10) + '%"></span></div>' +
        (d.gist ? '<div class="decision-meta">想说：' + escapeHtml(d.gist) + "</div>" : "") +
      "</div>";
    });
    el.drawerBody.innerHTML = html;
  }

  function tagText(o) {
    return o === "SELECTED" ? "入选发言" : o === "SKIPPED" ? "被配额刷掉" : "保持沉默";
  }

  function closeDrawer() {
    el.drawer.classList.add("hidden");
    el.drawerMask.classList.add("hidden");
  }

  // ---------- 状态行：谁在看消息 / 谁在酝酿 / 谁在输入（C2） ----------
  function abbrevNames(names) {
    if (!names || !names.length) return "";
    if (names.length <= 3) return names.join("、");
    return names.slice(0, 2).join("、") + " 等 " + names.length + " 人";
  }

  function refreshStatus() {
    var composing = [], waiting = [];
    Object.keys(state.typing).forEach(function (k) {
      var t = state.typing[k];
      if (t.phase === "composing") composing.push(t.name); else waiting.push(t.name);
    });
    var text = "", cls = "";
    if (composing.length) {
      text = abbrevNames(composing) + " 正在输入…";
      cls = "compose";
    } else if (waiting.length) {
      text = abbrevNames(waiting) + " 正在想怎么接话…";
      cls = "wait";
    } else if (state.thinking && state.thinking.names && state.thinking.names.length) {
      text = abbrevNames(state.thinking.names) + " 正在看这条消息…";
      cls = "gate";
    }
    el.typingLine.textContent = text;
    el.typingLine.className = "typing-line" + (cls ? " " + cls : "");
  }

  // 底部「提前长出来的消息」占位：某个 agent 已确定要发言、正在酝酿/打字时，
  // 先把它那条气泡显示出来（三点动画 + 正在输入…），让人一眼看到"有人要说话了"。
  function typingRowsEl() {
    var c = document.getElementById("typingRows");
    if (!c) {
      c = document.createElement("div");
      c.id = "typingRows";
      c.className = "typing-rows";
      el.list.appendChild(c);
    }
    return c;
  }

  // 占位气泡永远待在消息列表最底部（有新消息时把它挪到最后）
  function keepTypingRowsLast() {
    var c = document.getElementById("typingRows");
    if (c) el.list.appendChild(c);
  }

  function renderTypingRows() {
    var stick = nearBottom();
    var ids = Object.keys(state.typing);
    if (!ids.length) {
      var old = document.getElementById("typingRows");
      if (old) old.remove();
      return;
    }
    var c = typingRowsEl();
    c.innerHTML = ids.map(function (id) {
      var t = state.typing[id] || {};
      var label = t.phase === "composing" ? "正在输入" : "正在酝酿";
      return '<div class="msg typing-ghost" data-agent="' + escapeHtml(id) + '">' +
        '<div class="avatar" style="background:#d6e4ff">' + escapeHtml(t.avatar || "🤖") + "</div>" +
        '<div class="col">' +
          '<div class="sender">' + escapeHtml(t.name || "") + "</div>" +
          '<div class="bubble typing-bubble"><span class="typing-text">' + label + "</span><i></i><i></i><i></i></div>" +
        "</div>" +
      "</div>";
    }).join("");
    keepTypingRowsLast();
    if (stick) scrollBottom();
  }

  // 实时提示（不落库）：如"对方好像没理睬你"。带 agentId 的（被 @ 不理睬）用气泡样式贴在
  // 那个人的位置上；没有 agent 的（全员已读不回）仍用居中灰条。
  function appendNotice(n) {
    if (typeof n === "string") n = { text: n };
    if (!n || !n.text) return;
    var stick = nearBottom();
    var d = document.createElement("div");
    if (n.agentId) {
      var label = n.text;
      if (n.name && label.indexOf(n.name) === 0) {
        label = label.slice(n.name.length).replace(/^[\s:：]+/, "");
      }
      d.className = "msg typing-ghost miss-ghost";
      d.innerHTML =
        '<div class="avatar" style="background:#f3e3d3">' + escapeHtml(n.avatar || "🙈") + "</div>" +
        '<div class="col">' +
          '<div class="sender">' + escapeHtml(n.name || "") + "</div>" +
          '<div class="bubble miss-bubble">' + escapeHtml(label) + "</div>" +
        "</div>";
    } else {
      d.className = "msg-system sys-notice";
      d.innerHTML = '<div class="sys-pill">' + escapeHtml(n.text) + "</div>";
    }
    el.list.appendChild(d);
    keepTypingRowsLast();
    if (stick) scrollBottom();
  }

  // ---------- SSE（按群过滤） ----------
  function connectSse() {
    var url = "/api/stream" + (TOKEN ? "?token=" + encodeURIComponent(TOKEN) : "");
    var es = new EventSource(url);

    function sameGroup(payload) {
      return payload && (!payload.groupId || payload.groupId === state.groupId);
    }

    es.addEventListener("message", function (e) {
      var m = JSON.parse(e.data);
      if (m.groupId !== state.groupId) { scheduleGroupsRefresh(); return; }
      var stick = nearBottom() || m.senderType === "HUMAN";
      renderMessage(m);
      if (stick) scrollBottom();
      scheduleGroupsRefresh();
    });
    es.addEventListener("system", function (e) {
      var payload = JSON.parse(e.data);
      if (!sameGroup(payload)) { scheduleGroupsRefresh(); return; }
      if (payload && payload.id) {
        renderMessage(payload);
      } else if (payload && payload.content) {
        var d = document.createElement("div");
        d.className = "msg-system";
        d.innerHTML = '<div class="sys-pill">' + escapeHtml(payload.content) + "</div>";
        el.list.appendChild(d);
        keepTypingRowsLast();
      } else {
        toast(payload.content || "系统消息");
        return;
      }
      if (nearBottom()) scrollBottom();
      scheduleGroupsRefresh();
    });
    es.addEventListener("recall", function (e) {
      var m = JSON.parse(e.data);
      if (m.groupId !== state.groupId) return;
      var row = el.list.querySelector('[data-id="' + m.id + '"]');
      if (row && !el.list.querySelector('.msg-system[data-id="' + m.id + '"]')) {
        row.classList.add("recalled");
        var b = row.querySelector(".bubble");
        if (b) b.textContent = "[消息已撤回]";
      } else if (row) {
        row.querySelector(".sys-pill").textContent = "[消息已撤回]";
      }
    });
    es.addEventListener("typing", function (e) {
      var t = JSON.parse(e.data);
      if (!sameGroup(t)) return;
      if (t.active) {
        state.typing[t.agentId] = {
          name: t.name,
          avatar: t.avatar,
          phase: t.phase === "composing" ? "composing" : "waiting"
        };
      } else {
        delete state.typing[t.agentId];
      }
      refreshStatus();
      renderTypingRows();
    });
    // 门控阶段：全员正在琢磨要不要接话（这几秒最容易让人以为卡了）
    es.addEventListener("thinking", function (e) {
      var t = JSON.parse(e.data);
      if (!sameGroup(t)) return;
      state.thinking = t.active ? t : null;
      refreshStatus();
    });
    // 实时提示：被 @ 没理睬 / 全员已读不回
    es.addEventListener("notice", function (e) {
      var n = JSON.parse(e.data);
      if (!sameGroup(n)) return;
      appendNotice(n);
    });
    es.addEventListener("decision", function (e) {
      var d = JSON.parse(e.data);
      if (!sameGroup(d)) return;
      state.decisionsByMsg[d.messageId] = d.decisions;
      var row = el.list.querySelector('[data-id="' + d.messageId + '"]');
      if (row) {
        var link = row.querySelector('[data-act="decisions"]');
        if (link) link.textContent = "决策透视 (" + d.decisions.length + ")";
      }
    });
    es.addEventListener("usage", function (e) {
      var u = JSON.parse(e.data);
      if (!sameGroup(u)) return;
      if (typeof u.groupTokens === "number") {
        el.tokenFoot.textContent = "本群 tokens：" + num(u.groupTokens);
      }
    });
    es.addEventListener("state", function (e) {
      var s = JSON.parse(e.data);
      if (s.groupId && s.groupId !== state.groupId) { scheduleGroupsRefresh(); return; }
      applyStopped(s.stopped);
    });
    es.addEventListener("groups", function () { scheduleGroupsRefresh(); });
    // 管理台刚保存了模型配置：立刻撤掉"模型未配置"横幅（热更新，无需重启）
    es.addEventListener("model", function (e) {
      var m = JSON.parse(e.data);
      if (!m || !m.chatModelConfigured) return;
      delete el.banner.dataset.modelWarn;
      if (!state.stopped) el.banner.classList.add("hidden");
      toast("模型已配置，Agent 可以说话了");
    });
    es.onerror = function () {
      // 浏览器会自动重连，并携带 Last-Event-ID 补拉
      state.typing = {};
      state.thinking = null;
      refreshStatus();
      renderTypingRows();
    };
  }

  // ---------- 停止开关（C4） ----------
  function applyStopped(s) {
    state.stopped = !!s;
    el.stopBtn.classList.toggle("on", state.stopped);
    el.stopBtn.textContent = state.stopped ? "▶" : "⏸";
    el.stopBtn.title = state.stopped ? "恢复聊天" : "停止聊天";
    if (state.stopped) {
      el.banner.textContent = "⏸ 这个群已停止聊天：消息仍会记录，但 Agent 不再响应。点击右上角 ▶ 恢复。";
      el.banner.classList.remove("hidden");
    } else if (el.banner.dataset.modelWarn !== "1") {
      el.banner.classList.add("hidden");
    }
  }

  function toggleStop() {
    if (!state.groupId) return;
    api("/api/config/chat-state", {
      method: "POST",
      body: JSON.stringify({ groupId: state.groupId, stopped: !state.stopped })
    }).then(function (dto) { applyStopped(dto.stopped); scheduleGroupsRefresh(); })
      .catch(function (e) { toast("操作失败：" + e.message); });
  }

  // ---------- @提及 ----------
  function renderMentionBar() {
    var chips = state.members.map(function (a) {
      return '<span class="mention-chip" data-name="' + escapeHtml(a.name) + '">' +
        escapeHtml(a.avatar || "🤖") + " " + escapeHtml(a.name) + "</span>";
    }).join("");
    el.mentionBar.innerHTML = chips;
    el.mentionBar.classList.toggle("hidden", chips.length === 0);
  }

  // ---------- 元信息 / 成员 ----------
  function loadMeta() {
    api("/api/config/meta?groupId=" + encodeURIComponent(state.groupId)).then(function (meta) {
      el.groupName.textContent = meta.groupName || "群聊";
      document.title = (meta.groupName || "群聊") + " · AI 群聊";
      if (!meta.chatModelConfigured) {
        el.banner.dataset.modelWarn = "1";
        el.banner.innerHTML = '⚠️ 模型还没配置：去 <a href="/admin.html#model">管理台 → 模型配置</a> '
            + "填一下（保存后立即生效，不用重启）。现在只能存消息，Agent 不会说话。";
        el.banner.classList.remove("hidden");
      } else if (!state.stopped) {
        delete el.banner.dataset.modelWarn;
      }
      if (meta.groupNotice) {
        el.groupName.title = meta.groupNotice;
      }
    }).catch(function () {});
    api("/api/config/chat-state?groupId=" + encodeURIComponent(state.groupId))
      .then(function (s) { applyStopped(s.stopped); }).catch(function () {});
    api("/api/stats/tokens?groupId=" + encodeURIComponent(state.groupId)).then(function (t) {
      el.tokenFoot.textContent = "本群 tokens：" + num(t.totalTokens || 0);
    }).catch(function () {});
  }

  function loadMembers() {
    if (!state.groupId) return;
    api("/api/groups/" + encodeURIComponent(state.groupId) + "/members").then(function (list) {
      state.members = list || [];
      renderMentionBar();
    }).catch(function () {});
  }

  function loadAllAgents() {
    return api("/api/agents").then(function (list) {
      state.agents = (list || []).filter(function (a) { return a.active; });
    }).catch(function () { state.agents = []; });
  }

  function num(n) {
    if (n == null) return "0";
    return Number(n).toLocaleString("en-US");
  }

  // ---------- 发起群聊 ----------
  function openNewGroup() {
    el.ngName.value = "";
    el.ngAvatar.value = "";
    loadAllAgents().then(function () {
      if (!state.agents.length) {
        el.ngAgents.innerHTML = '<div class="hint">还没有可用人设，请先去管理台新增成员。</div>';
      } else {
        el.ngAgents.innerHTML = state.agents.map(function (a) {
          return '<label class="pick-item"><input type="checkbox" value="' + escapeHtml(a.id) + '">' +
            "<span>" + escapeHtml(a.avatar || "🤖") + "</span>" +
            "<span class='pick-name'>" + escapeHtml(a.name) + "</span>" +
            "<span class='pick-persona'>" + escapeHtml(short(a.persona, 40)) + "</span></label>";
        }).join("");
      }
      el.newGroupModal.classList.remove("hidden");
    });
  }

  function createGroup() {
    var name = el.ngName.value.trim();
    if (!name) { toast("请填写群名称"); return; }
    var ids = Array.prototype.slice.call(el.ngAgents.querySelectorAll("input:checked"))
      .map(function (i) { return i.value; });
    if (!ids.length) { toast("请至少选择一个成员"); return; }
    api("/api/groups", {
      method: "POST",
      body: JSON.stringify({ name: name, avatar: el.ngAvatar.value.trim(), agentIds: ids })
    }).then(function (g) {
      el.newGroupModal.classList.add("hidden");
      toast("群「" + g.name + "」已创建");
      loadGroups(false).then(function () { enterGroup(g.id); });
    }).catch(function (e) { toast("创建失败：" + e.message); });
  }

  // ---------- 群聊信息 / 成员管理 ----------
  function openInfo() {
    var g = currentGroup();
    if (!g) return;
    el.infoDrawer.classList.remove("hidden");
    el.infoMask.classList.remove("hidden");
    renderInfo(g);
  }

  function renderInfo(g) {
    var memberHtml = state.members.length
      ? state.members.map(function (a) {
          return '<div class="member-row">' +
            '<span class="m-avatar">' + escapeHtml(a.avatar || "🤖") + "</span>" +
            '<span class="m-name">' + escapeHtml(a.name) + "</span>" +
            '<span class="tool-link" data-act="kick" data-id="' + escapeHtml(a.id) + '">移出群</span>' +
          "</div>";
        }).join("")
      : '<div class="hint">这个群还没有成员。</div>';

    var outsider = state.agents.filter(function (a) {
      return !state.members.some(function (m) { return m.id === a.id; });
    });
    var addHtml = outsider.length
      ? outsider.map(function (a) {
          return '<label class="pick-item compact"><input type="checkbox" value="' + escapeHtml(a.id) + '">' +
            "<span>" + escapeHtml(a.avatar || "🤖") + "</span>" +
            "<span class='pick-name'>" + escapeHtml(a.name) + "</span></label>";
        }).join("")
      : '<div class="hint">全部人设都已经在这个群里了。</div>';

    el.infoBody.innerHTML =
      '<div class="field"><label>群名称</label><input id="giName" maxlength="30" value="' + escapeHtml(g.name) + '"></div>' +
      '<div class="field"><label>群头像（emoji）</label><input id="giAvatar" value="' + escapeHtml(g.avatar || "") + '"></div>' +
      '<div class="field"><label>群公告（会写进成员视野）</label>' +
        '<textarea id="giNotice" placeholder="例如：本群只聊摸鱼，禁止内卷">' + escapeHtml(g.notice || "") + "</textarea></div>" +
      '<div class="btn-row"><button class="btn" id="giSaveBtn">保存群资料</button>' +
        (g.sample ? "" : '<button class="btn danger" id="giDeleteBtn">解散本群</button>') +
        (g.sample ? '<span class="hint" style="align-self:center">示例群不允许解散</span>' : "") +
      "</div>" +
      '<h3>群成员（' + state.members.length + "人）</h3>" + memberHtml +
      "<h3 style='margin-top:18px'>拉人进群</h3>" + addHtml +
      '<div class="btn-row" style="margin-top:10px"><button class="btn ghost" id="giAddBtn">把勾选的人拉进来</button></div>' +
      '<div class="hint">人设是全局的：移出群只是不在这个群里说话，个人印象与其它群不受影响。</div>';

    document.getElementById("giSaveBtn").addEventListener("click", function () {
      api("/api/groups/" + encodeURIComponent(g.id), {
        method: "PUT",
        body: JSON.stringify({
          name: document.getElementById("giName").value.trim(),
          avatar: document.getElementById("giAvatar").value.trim(),
          notice: document.getElementById("giNotice").value
        })
      }).then(function (ng) {
        toast("已保存");
        loadGroups(false).then(function () { loadMeta(); renderInfo(ng); });
      }).catch(function (e) { toast("保存失败：" + e.message); });
    });
    var delBtn = document.getElementById("giDeleteBtn");
    if (delBtn) {
      delBtn.addEventListener("click", function () {
        if (!confirm("解散「" + g.name + "」？\n该群的消息、群共识、记忆进度与 token 流水都会一起删除，不可恢复。")) return;
        api("/api/groups/" + encodeURIComponent(g.id), { method: "DELETE" }).then(function () {
          toast("已解散");
          closeInfo();
          state.groupId = null;
          loadGroups(true);
        }).catch(function (e) { toast("解散失败：" + e.message); });
      });
    }
    document.getElementById("giAddBtn").addEventListener("click", function () {
      var ids = Array.prototype.slice.call(el.infoBody.querySelectorAll(".pick-item input:checked"))
        .map(function (i) { return i.value; });
      if (!ids.length) { toast("先勾选要拉进来的人"); return; }
      api("/api/groups/" + encodeURIComponent(g.id) + "/members", {
        method: "POST",
        body: JSON.stringify({ agentIds: ids })
      }).then(function () {
        toast("已拉进群");
        loadMembers();
        loadGroups(false);
        setTimeout(function () { renderInfo(currentGroup() || g); }, 300);
      }).catch(function (e) { toast("操作失败：" + e.message); });
    });
  }

  function closeInfo() {
    el.infoDrawer.classList.add("hidden");
    el.infoMask.classList.add("hidden");
  }

  // ---------- 移动端侧栏 ----------
  function openSideOnMobile() {
    el.side.classList.add("open");
    el.sideMask.classList.remove("hidden");
  }
  function closeSideOnMobile() {
    el.side.classList.remove("open");
    el.sideMask.classList.add("hidden");
  }

  // ---------- 初始化 ----------
  function init() {
    loadAllAgents();
    loadGroups(true);
    connectSse();

    el.sendBtn.addEventListener("click", send);
    el.input.addEventListener("keydown", function (e) {
      if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
    });
    el.input.addEventListener("input", function () {
      el.input.style.height = "auto";
      el.input.style.height = Math.min(120, el.input.scrollHeight) + "px";
      var v = el.input.value;
      if (v.endsWith("@") || /@[^\s]*$/.test(v)) renderMentionBar();
      else el.mentionBar.classList.add("hidden");
    });
    // 点 @ 快捷栏：输入框里已经有半截 "@xx" 就替换掉，否则直接追加（进群时这栏就是展开的，
    // 用户会直接点，所以不能只处理"正在输入 @"的情况）。
    el.mentionBar.addEventListener("click", function (e) {
      var chip = e.target.closest(".mention-chip");
      if (!chip) return;
      var name = chip.getAttribute("data-name");
      var v = el.input.value;
      if (/@[^\s]*$/.test(v)) {
        v = v.replace(/@[^\s]*$/, "@" + name + " ");
      } else {
        v = v + (v && !/\s$/.test(v) ? " " : "") + "@" + name + " ";
      }
      el.input.value = v;
      el.input.style.height = "auto";
      el.input.style.height = Math.min(120, el.input.scrollHeight) + "px";
      el.input.focus();
      el.mentionBar.classList.add("hidden");
    });
    el.stopBtn.addEventListener("click", toggleStop);
    el.membersBtn.addEventListener("click", openInfo);
    document.getElementById("infoClose").addEventListener("click", closeInfo);
    el.infoMask.addEventListener("click", closeInfo);
    el.groupList.addEventListener("click", function (e) {
      var item = e.target.closest(".group-item");
      if (!item) return;
      var id = item.getAttribute("data-id");
      if (id && id !== state.groupId) enterGroup(id);
      else closeSideOnMobile();
    });
    el.newGroupBtn.addEventListener("click", openNewGroup);
    document.getElementById("ngCancelBtn").addEventListener("click", function () {
      el.newGroupModal.classList.add("hidden");
    });
    document.getElementById("ngCreateBtn").addEventListener("click", createGroup);
    el.newGroupModal.addEventListener("click", function (e) {
      if (e.target === el.newGroupModal) el.newGroupModal.classList.add("hidden");
    });
    document.getElementById("sideToggle").addEventListener("click", openSideOnMobile);
    el.sideMask.addEventListener("click", closeSideOnMobile);
    el.list.addEventListener("click", function (e) {
      var link = e.target.closest(".tool-link");
      if (link) {
        var row = link.closest(".msg");
        var id = row && row.getAttribute("data-id");
        if (!id) return;
        if (link.getAttribute("data-act") === "decisions") openDecisions(id);
        else if (link.getAttribute("data-act") === "recall") {
          api("/api/messages/" + id + "/recall", { method: "POST" })
            .catch(function (er) { toast("撤回失败：" + er.message); });
        }
        return;
      }
      var row2 = e.target.closest(".msg");
      if (row2) openDecisions(row2.getAttribute("data-id"));
    });
    // 注意：进群时 #msgList 的 innerHTML 会被整体重建，所以"加载更早"用事件委托挂在
    // msgList 上，而不是直接绑在 #loadMore 元素上（否则切过群之后点击就失效了）。
    el.list.addEventListener("click", function (e) {
      if (!e.target.closest(".load-more")) return;
      if (!state.noMoreHistory && state.oldestId !== null) loadHistory(state.oldestId);
    });
    el.list.addEventListener("scroll", function () {
      if (el.list.scrollTop <= 2 && !state.noMoreHistory && state.oldestId !== null && !state.loadingHistory) {
        loadHistory(state.oldestId);
      }
    });
    document.getElementById("drawerClose").addEventListener("click", closeDrawer);
    el.drawerMask.addEventListener("click", closeDrawer);
  }

  init();
})();