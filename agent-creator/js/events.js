// 对话与补丁：调用模型、解析 <VCPA_PATCH>、把补丁回填到页面状态。
import { state, esc, pick, save } from "./state.js";
import {
  VCPA_STEPS,
  TOOLBOXES,
  DSL_SUFFIXES,
  ENHANCEMENTS,
  SYSTEM_PLACEHOLDERS,
  GENDER_OPTIONS,
  USER_CALL_OPTIONS
} from "./registry.js";
import { buildChatSeed } from "./templates.js";
import { renderStepNav, renderPreview, showToast } from "./preview.js";
import { renderWizard } from "./wizard.js";

let aborter = null;

function renderChat() {
  const log = document.querySelector("#chatLog");
  log.innerHTML = state.chat.messages.map((msg) => {
    const text = esc(stripPatch(msg.content));
    if (msg.role === "assistant") {
      return `<div class="message assistant"><img class="avatar" src="assets/jinli.png" alt="烬离" loading="lazy"><div class="bubble">${text}</div></div>`;
    }
    return `<div class="message ${msg.role}"><div class="bubble">${text}</div></div>`;
  }).join("");
  log.scrollTop = log.scrollHeight;
}

function setBusy(busy) {
  document.querySelector("#sendButton").disabled = busy;
  document.querySelector("#stopButton").disabled = !busy;
  document.querySelector("#askStepButton").disabled = busy;
}

function stripPatch(value) {
  return String(value || "").replace(/<VCPA_PATCH>[\s\S]*?<\/VCPA_PATCH>/g, "").trim();
}

function extractPatch(value) {
  const match = String(value || "").match(/<VCPA_PATCH>([\s\S]*?)<\/VCPA_PATCH>/);
  if (!match) return null;
  try {
    return JSON.parse(match[1].trim());
  } catch {
    return null;
  }
}

function filterIds(values, registry, always = []) {
  const allowed = new Set(registry.map((item) => item.id));
  return Array.from(new Set([...(always || []), ...(Array.isArray(values) ? values : [])]))
    .filter((id) => allowed.has(id));
}

function applyAssistantPatch(answer) {
  const patch = extractPatch(answer);
  if (!patch || typeof patch !== "object") return false;

  if (patch.agent && typeof patch.agent === "object") {
    Object.assign(state.agent, pick(patch.agent, [
      "name",
      "summary",
      "gender",
      "appearance",
      "personality",
      "userCall",
      "customUserCall",
      "duties",
      "boundaries"
    ]));
    if (!GENDER_OPTIONS.some((o) => o.id === state.agent.gender)) state.agent.gender = "neutral";
    if (!USER_CALL_OPTIONS.some((o) => o.id === state.agent.userCall)) state.agent.userCall = "主人";
  }

  if (Array.isArray(patch.selectedToolboxes)) {
    state.selectedToolboxes = filterIds(patch.selectedToolboxes, TOOLBOXES);
  }

  if (patch.diary && typeof patch.diary === "object") {
    Object.assign(state.diary, pick(patch.diary, ["primaryName", "mode", "guide"]));
    state.diary.mode = state.diary.mode === "dynamic" ? "dynamic" : "fixed";
    if (Array.isArray(patch.diary.suffixes)) {
      const suffixes = filterIds(patch.diary.suffixes, DSL_SUFFIXES);
      if (suffixes.includes("rerank") && suffixes.includes("rerank_plus")) {
        suffixes.splice(suffixes.indexOf("rerank"), 1);
      }
      state.diary.suffixes = suffixes;
    }
    if (Array.isArray(patch.diary.publicBooks)) {
      state.diary.publicBooks = patch.diary.publicBooks.map((item) => String(item).trim()).filter(Boolean);
    }
    if (Number.isFinite(Number(patch.diary.truncate))) {
      state.diary.truncate = Math.min(0.8, Math.max(0.1, Number(patch.diary.truncate)));
    }
  }

  if (Array.isArray(patch.enhancements)) {
    state.enhancements = filterIds(patch.enhancements, ENHANCEMENTS, ["meta_think"]);
  }

  if (Array.isArray(patch.placeholders)) {
    const required = SYSTEM_PLACEHOLDERS.filter((item) => item.required).map((item) => item.id);
    state.placeholders = filterIds(patch.placeholders, SYSTEM_PLACEHOLDERS, required);
  }

  if (typeof patch.customPlaceholders === "string") {
    state.customPlaceholders = patch.customPlaceholders;
  }

  state.step = 6;
  save();
  renderStepNav();
  renderWizard();
  renderPreview();
  return true;
}

async function callJinli(extraUser) {
  const cfg = state.config;
  aborter = new AbortController();
  const history = state.chat.messages
    .filter((msg) => (msg.role === "user" || msg.role === "assistant") && String(msg.content || "").trim())
    .map((msg) => ({ role: msg.role, content: msg.content }));
  // extraUser 仅用于本次请求（如补丁修复提示），不写入 chat.messages，避免污染历史
  const tail = extraUser ? [{ role: "user", content: extraUser }] : [];
  const payload = {
    model: cfg.model,
    temperature: cfg.temperature,
    max_tokens: cfg.maxTokens,
    stream: false,
    messages: [
      { role: "system", content: cfg.systemPrompt },
      { role: "user", content: buildChatSeed() },
      ...history,
      ...tail
    ]
  };

  const response = await fetch(cfg.apiUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${cfg.apiKey}`
    },
    body: JSON.stringify(payload),
    signal: aborter.signal
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${(await response.text()).slice(0, 180)}`);

  const json = await response.json();
  return json.choices?.[0]?.message?.content
    || json.choices?.[0]?.text
    || json.output_text
    || json.content
    || "";
}

async function sendMessage(text) {
  const content = String(text || "").trim();
  if (!content) return;
  state.chat.messages.push({ role: "user", content });
  save();
  renderChat();
  document.querySelector("#chatInput").value = "";
  setBusy(true);
  try {
    let answer = await callJinli();
    if (answer && !extractPatch(answer)) {
      const repair = await callJinli("你的上一条回复缺少 <VCPA_PATCH>。请根据用户最新需求和当前页面状态，只返回一个完整 <VCPA_PATCH>JSON</VCPA_PATCH>，不要写其他内容。");
      if (extractPatch(repair)) answer = `${answer}\n\n${repair}`;
    }
    // 存入历史的仅保留对话文本：<VCPA_PATCH> 已应用、不再回传给模型，避免污染下一轮上下文
    const display = stripPatch(answer);
    state.chat.messages.push({ role: "assistant", content: display || "烬离没有返回内容。" });
    if (applyAssistantPatch(answer)) {
      showToast("烬离已填好 0-6 步，可继续修改或导出");
    } else if (display) {
      showToast("烬离已回复；这次没有可应用的结构化补丁");
    }
    save();
    renderChat();
  } catch (error) {
    if (error.name !== "AbortError") {
      state.chat.messages.push({ role: "error", content: error.message });
      renderChat();
      showToast("烬离连接失败，请检查网关或代理");
    }
  } finally {
    aborter = null;
    setBusy(false);
    save();
  }
}

function bindConfig() {
  for (const key of ["apiUrl", "apiKey", "model"]) {
    const input = document.querySelector(`#${key}`);
    input.value = state.config[key];
    input.addEventListener("input", () => {
      state.config[key] = input.value.trim();
      save();
    });
  }
  const defaultContent = document.querySelector("#defaultContent");
  defaultContent.value = state.defaultContent;
  defaultContent.addEventListener("input", () => {
    state.defaultContent = defaultContent.value;
    save();
  });
}

export function bindChat() {
  bindConfig();
  renderChat();
  document.querySelector("#chatForm").addEventListener("submit", (event) => {
    event.preventDefault();
    sendMessage(document.querySelector("#chatInput").value);
  });
  document.querySelector("#askStepButton").addEventListener("click", () => {
    const step = VCPA_STEPS[state.step];
    sendMessage(`请根据当前草稿，补全并优化「${step.name}」这一步，同时返回完整 <VCPA_PATCH>，让页面自动更新 0-6 步。`);
  });
  document.querySelector("#clearChatButton").addEventListener("click", () => {
    state.chat.messages = [];
    save();
    renderChat();
  });
  document.querySelector("#stopButton").addEventListener("click", () => aborter?.abort());
}
