// 步骤向导：根据当前 step 渲染表单、收集输入、绑定步骤按钮。
import { state, esc, save } from "./state.js";
import {
  VCPA_STEPS,
  TOOLBOXES,
  PRESETS,
  DSL_SUFFIXES,
  ENHANCEMENTS,
  SYSTEM_PLACEHOLDERS,
  GENDER_OPTIONS,
  USER_CALL_OPTIONS
} from "./registry.js";
import { buildAgentPrompt } from "./templates.js";
import { renderStepNav, renderPreview } from "./preview.js";

const form = () => document.querySelector("#creatorForm");

function field(name, label, value, type = "text", cls = "") {
  return `<label class="${cls}"><span>${label}</span><input name="${name}" type="${type}" value="${esc(value)}"></label>`;
}

function textarea(name, label, value, rows = 5, cls = "wide") {
  return `<label class="${cls}"><span>${label}</span><textarea name="${name}" rows="${rows}">${esc(value)}</textarea></label>`;
}

function renderBasic() {
  const a = state.agent;
  form().innerHTML = `<div class="form-grid">
      ${field("name", "Agent 名称", a.name)}
      ${field("summary", "一句话定位", a.summary)}
      <label>
        <span>身份字段</span>
        <select name="gender">
          ${GENDER_OPTIONS.map((o) => `<option value="${o.id}"${a.gender === o.id ? " selected" : ""}>${o.name}</option>`).join("")}
        </select>
      </label>
      <label>
        <span>称呼用户</span>
        <select name="userCall">
          ${USER_CALL_OPTIONS.map((o) => `<option value="${o.id}"${a.userCall === o.id ? " selected" : ""}>${o.name}</option>`).join("")}
        </select>
      </label>
      ${field("customUserCall", "自定义称呼", a.customUserCall, "text", "wide")}
    </div>`;
}

function renderPersona() {
  const a = state.agent;
  form().innerHTML = `
      ${textarea("appearance", "形象描写", a.appearance)}
      ${textarea("personality", "人格与语气", a.personality)}
      ${textarea("duties", "核心职责", a.duties)}
      ${textarea("boundaries", "行为边界/禁令", a.boundaries)}`;
}

function renderTools() {
  form().innerHTML = `<div class="preset-row">
      ${PRESETS.map((preset) => `<button class="subtle-button" type="button" data-preset="${preset.id}">${preset.name}</button>`).join("")}
    </div>
    <div class="tool-grid">
      ${TOOLBOXES.map((tool, i) => `<article class="option-card reveal" style="--i:${i}">
        <header><strong>${tool.name}</strong><label class="switch"><input type="checkbox" name="toolbox" value="${tool.id}"${state.selectedToolboxes.includes(tool.id) ? " checked" : ""}>启用</label></header>
        <p>${tool.description}</p>
        <div class="tag-row">${tool.tags.map((tag) => `<span class="tag">${tag}</span>`).join("")}</div>
        <code>${tool.placeholder}</code>
      </article>`).join("")}
    </div>`;
}

function renderDiary() {
  const d = state.diary;
  form().innerHTML = `<div class="form-grid">
      ${field("primaryName", "核心日记本", d.primaryName)}
      <label><span>注入模式</span><select name="mode">
        <option value="fixed"${d.mode === "fixed" ? " selected" : ""}>固定 [[ ]]</option>
        <option value="dynamic"${d.mode === "dynamic" ? " selected" : ""}>动态 《《》》</option>
      </select></label>
      <label class="wide"><span>Truncate 阈值</span><input name="truncate" type="number" min="0.1" max="0.8" step="0.1" value="${esc(d.truncate)}"></label>
    </div>
    <div class="check-grid">
      ${DSL_SUFFIXES.map((item, i) => `<label class="option-card switch reveal" style="--i:${i}"><input type="checkbox" name="suffix" value="${item.id}"${d.suffixes.includes(item.id) ? " checked" : ""}><span><strong>${item.name}</strong><br>${item.syntax}<br>${item.description}</span></label>`).join("")}
    </div>
    ${textarea("publicBooks", "公共日记本，每行一个", d.publicBooks.join("\n"), 4)}
    ${textarea("guide", "日记分类指引", d.guide, 4)}
    <p class="field-label">当前 DSL：${esc(buildAgentPrompt().split("\n").find((line) => line.includes(d.primaryName)) || "")}</p>`;
}

function renderEnhance() {
  form().innerHTML = `<div class="check-grid">
      ${ENHANCEMENTS.map((item, i) => `<label class="option-card switch reveal" style="--i:${i}"><input type="checkbox" name="enhancement" value="${item.id}"${state.enhancements.includes(item.id) ? " checked" : ""}${item.required ? " disabled" : ""}><span><strong>${item.name}</strong><br>${esc(item.template)}</span></label>`).join("")}
    </div>`;
}

function renderPlaceholders() {
  form().innerHTML = `<div class="check-grid">
      ${SYSTEM_PLACEHOLDERS.map((item, i) => `<label class="option-card switch reveal" style="--i:${i}"><input type="checkbox" name="placeholder" value="${item.id}"${state.placeholders.includes(item.id) ? " checked" : ""}${item.required ? " disabled" : ""}><span><strong>${item.name}</strong><br>${item.syntax}</span></label>`).join("")}
    </div>
    ${textarea("customPlaceholders", "自定义占位符，每行一个", state.customPlaceholders, 5)}`;
}

function renderExport() {
  form().innerHTML = `<section class="option-card">
      <h3>结构概览</h3>
      <p>Agent：${esc(state.agent.name)} / ${esc(state.agent.summary)}</p>
      <p>工具箱：${selectedNames(TOOLBOXES, state.selectedToolboxes)}</p>
      <p>日记：${esc(state.diary.primaryName)}，${state.diary.mode === "fixed" ? "固定注入" : "动态注入"}</p>
      <p>增强模块：${selectedNames(ENHANCEMENTS, state.enhancements)}</p>
    </section>
    <button id="resetButton" class="subtle-button" type="button">重置全部</button>`;
}

function selectedNames(source, selected) {
  const set = new Set(selected || []);
  return source.filter((item) => set.has(item.id)).map((item) => item.name).join("、") || "未选择";
}

// 仅绑定当前步骤特有的按钮：这些按钮随 innerHTML 重建，必须每次重新绑定
function bindStepButtons() {
  form().querySelectorAll("[data-preset]").forEach((button) => {
    button.addEventListener("click", () => {
      const preset = PRESETS.find((item) => item.id === button.dataset.preset);
      state.selectedToolboxes = preset.tools.slice();
      renderWizard();
      renderPreview();
      save();
    });
  });
  form().querySelector("#resetButton")?.addEventListener("click", () => {
    if (!confirm("确定重置创生炉内容？")) return;
    localStorage.removeItem("vcpa_current");
    location.reload();
  });
}

function handleInput() {
  const data = new FormData(form());
  const step = state.step;
  if (step === 0 || step === 1) Object.assign(state.agent, Object.fromEntries(data.entries()));
  if (step === 2) state.selectedToolboxes = data.getAll("toolbox");
  if (step === 3) {
    const suffixes = data.getAll("suffix");
    for (const suffix of suffixes.slice()) {
      const item = DSL_SUFFIXES.find((entry) => entry.id === suffix);
      if (item?.exclusive && suffixes.includes(item.exclusive)) {
        suffixes.splice(suffixes.indexOf(item.exclusive), 1);
      }
    }
    state.diary.primaryName = data.get("primaryName") || "";
    state.diary.mode = data.get("mode") || "fixed";
    state.diary.truncate = Number(data.get("truncate") || 0.6);
    state.diary.suffixes = suffixes;
    state.diary.publicBooks = String(data.get("publicBooks") || "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
    state.diary.guide = data.get("guide") || "";
  }
  if (step === 4) state.enhancements = Array.from(new Set(["meta_think", ...data.getAll("enhancement")]));
  if (step === 5) {
    const required = SYSTEM_PLACEHOLDERS.filter((item) => item.required).map((item) => item.id);
    state.placeholders = Array.from(new Set([...required, ...data.getAll("placeholder")]));
    state.customPlaceholders = data.get("customPlaceholders") || "";
  }
  save();
  renderStepNav();
  renderPreview();
}

// 事件委托：#creatorForm 元素持久存在、子控件动态重建，input/change 只在 bootstrap 绑一次，避免累积绑定
export function bindFormDelegation() {
  const el = form();
  el.addEventListener("input", handleInput);
  el.addEventListener("change", handleInput);
}

// 事件委托：#stepNav 按钮随 innerHTML 重建，点击委托只绑一次（顺带打破与 preview 的循环依赖）
export function bindStepNavDelegation() {
  document.querySelector("#stepNav").addEventListener("click", (event) => {
    const button = event.target.closest("[data-step]");
    if (!button) return;
    state.step = Number(button.dataset.step);
    save();
    renderStepNav();
    renderWizard();
  });
}

export function renderWizard() {
  const step = VCPA_STEPS[state.step] || VCPA_STEPS[0];
  document.querySelector("#stepKicker").textContent = `Step ${step.id}`;
  document.querySelector("#stepTitle").textContent = step.name;
  [renderBasic, renderPersona, renderTools, renderDiary, renderEnhance, renderPlaceholders, renderExport][step.id]();
  bindStepButtons();
}
