// 复制与导出系统提示词。
import { state } from "./state.js";
import { buildAgentPrompt } from "./templates.js";
import { showToast } from "./preview.js";

async function copyPrompt() {
  await navigator.clipboard.writeText(buildAgentPrompt());
  showToast("已复制完整系统提示词");
}

function exportPrompt() {
  const name = (state.agent.name || "VCP-Agent").replace(/[\\/:*?"<>|]+/g, "_");
  const blob = new Blob([buildAgentPrompt()], { type: "text/plain;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `${name}.txt`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
  showToast("TXT 已导出");
}

export function bindExport() {
  document.querySelector("#copyButton").addEventListener("click", copyPrompt);
  document.querySelector("#exportButton").addEventListener("click", exportPrompt);
}
