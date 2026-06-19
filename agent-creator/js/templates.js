// 提示词拼装：把页面状态渲染成最终系统提示词 .txt，以及给模型的对齐种子。
import { state } from "./state.js";
import { TOOLBOXES, ENHANCEMENTS, SYSTEM_PLACEHOLDERS, DSL_SUFFIXES } from "./registry.js";

function selectedItems(source, selected) {
  const set = new Set(selected || []);
  return source.filter((item) => set.has(item.id));
}

function userCall(agent) {
  return agent.userCall === "custom" ? agent.customUserCall || "{{VarUser}}" : agent.userCall;
}

function diaryDsl() {
  const { diary } = state;
  const suffixes = selectedItems(DSL_SUFFIXES, diary.suffixes).map((item) => item.syntax);
  const truncate = diary.truncate ? [`::Truncate${diary.truncate}`] : [];
  const body = `${diary.primaryName}${suffixes.join("")}${truncate.join("")}`;
  return diary.mode === "dynamic" ? `《《${body}》》` : `[[${body}]]`;
}

function customPlaceholderLines() {
  return String(state.customPlaceholders || "")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .join("\n");
}

export function buildAgentPrompt() {
  const { agent, diary } = state;
  const toolboxLines = selectedItems(TOOLBOXES, state.selectedToolboxes)
    .map((item) => item.placeholder)
    .join("\n");
  const enhancementLines = selectedItems(ENHANCEMENTS, state.enhancements)
    .map((item) => item.template)
    .join("\n\n");
  const optionalPlaceholders = selectedItems(SYSTEM_PLACEHOLDERS, state.placeholders)
    .filter((item) => !item.required)
    .map((item) => item.syntax)
    .join("\n");
  const custom = customPlaceholderLines();

  return `${enhancementLines}

${diaryDsl()}
${diary.publicBooks.map((name) => `[[${name}::Group::Time]]`).join("\n")}

————————以上是过往记忆区————————

${optionalPlaceholders}
${custom}

——————角色核心———————
你是「${agent.name}」。
定位：${agent.summary}
身份字段：${agent.gender}

形象描写：
${agent.appearance}

人格与语气：
${agent.personality}

核心职责：
${agent.duties}

行为边界/禁令：
${agent.boundaries}

我是你的${userCall(agent)}——{{VarUser}}。{{TarSysPrompt}}系统信息是{{VarSystemInfo}}。

系统工具列表与指南：{{VarToolList}}

${toolboxLines}

日记系统：
{{VarDailyNoteGuide}}
${diary.guide}
`.replace(/\n{3,}/g, "\n\n").trim();
}

export function buildChatSeed() {
  return `页面默认资料：
${state.defaultContent}

当前页面状态：
${JSON.stringify({
  agent: state.agent,
  selectedToolboxes: state.selectedToolboxes,
  diary: state.diary,
  enhancements: state.enhancements,
  placeholders: state.placeholders,
  customPlaceholders: state.customPlaceholders
}, null, 2)}

当前导出预览：
${buildAgentPrompt()}

你的任务：
1. 用户描述新 Agent 时，直接把 0-6 步补齐，不要只给建议。
2. 用户要求修改时，只修改相关字段，但保留其余已经成型的设计。
3. 正常回复给用户看得懂的话，然后在回复末尾追加 <VCPA_PATCH>合法 JSON</VCPA_PATCH>。
4. JSON 字段必须使用页面已有 id：工具箱 memo/search/file/media/contact；增强模块 meta_think/rendering/emoji/forum/anchor；占位符 user/sysprompt/systeminfo/toollist/dailyguide/home/team。`;
}
