// 步骤导航、实时预览、Toast 提示。
import { state, esc } from "./state.js";
import { VCPA_STEPS } from "./registry.js";
import { buildAgentPrompt } from "./templates.js";

export function renderStepNav() {
  const nav = document.querySelector("#stepNav");
  nav.innerHTML = VCPA_STEPS.map((step) => {
    const complete = step.id < state.step || (step.id === 0 && state.agent.name);
    return `<button class="step-button${step.id === state.step ? " active" : ""}${complete ? " complete" : ""}" type="button" data-step="${step.id}">
        <span class="step-index">${step.id}</span>
        <span>${step.name}</span>
      </button>`;
  }).join("");
}

export function renderPreview() {
  const prompt = buildAgentPrompt();
  document.querySelector("#promptPreview").innerHTML = esc(prompt)
    .replace(/(\{\{[^}]+\}\})/g, "<mark>$1</mark>")
    .replace(/(\[\[[^\]]+\]\]|《《[^》]+》》)/g, "<mark>$1</mark>");
  const toolboxCount = state.selectedToolboxes.length;
  document.querySelector("#previewMeta").textContent = `${prompt.length} 字符 · ${toolboxCount} 个工具箱`;
}

export function showToast(message) {
  const toast = document.querySelector("#toast");
  toast.textContent = message;
  toast.classList.add("show");
  clearTimeout(window.__vcpaToastTimer);
  window.__vcpaToastTimer = setTimeout(() => toast.classList.remove("show"), 2400);
}
