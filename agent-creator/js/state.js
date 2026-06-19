// 全局状态、默认值与公共工具。模块加载时从 localStorage 读取一次，后续模块共享同一可变引用。
const STORAGE_KEY = "vcpa_current";

const DEFAULT_CREATION_CONTEXT = `VCP Agent 创生 Web 工具规格书

核心定位：浏览器单页应用，引导用户通过步骤向导组装一份完整的 VCP Agent 系统提示词 .txt 文件。
视觉风格：暗色炉心主题，深茶灰底色、暗金点缀、余烬光效，与烬离的美学一致。

步骤：基础信息、人设编辑器、工具箱配置、日记系统配置、增强模块、系统占位符、预览与导出。
工具箱：{{VCPMemoToolBox}}、{{VCPSearchToolBox}}、{{VCPFileToolBox}}、{{VCPMediaToolBox}}、{{VCPContactToolBox}}。
默认 API：OpenAI 兼容格式，通过内置 /proxy 反向代理转发到 http://localhost:6005/v1/chat/completions。
模板顺序：元思考模块、日记本 DSL、过往记忆区、角色核心、用户与系统信息、工具列表、工具箱占位符、日记系统指南、增强模块。`;

const DEFAULT_SYSTEM_PROMPT = `{{烬离}}

你正在驱动一个 VCP Agent Creator 页面。用户会用自然语言描述想创造的 Agent，你要和用户正常对话，并把用户意图整理成页面 0-6 步的完整字段。

每次你认为可以更新页面时，回复末尾必须追加一个 <VCPA_PATCH>...</VCPA_PATCH> JSON 补丁。JSON 只允许包含这些字段：
{
  "agent": {
    "name": "",
    "summary": "",
    "gender": "maid|valet|neutral",
    "appearance": "",
    "personality": "",
    "userCall": "主人|你|您|custom",
    "customUserCall": "",
    "duties": "",
    "boundaries": ""
  },
  "selectedToolboxes": ["memo","search","file","media","contact"],
  "diary": {
    "primaryName": "",
    "mode": "fixed|dynamic",
    "suffixes": ["time","group","tagmemo_plus","tagmemo","rerank","rerank_plus","expand","associate","base64memo","aimemo","aimemo_plus"],
    "truncate": 0.6,
    "publicBooks": [],
    "guide": ""
  },
  "enhancements": ["meta_think","rendering","emoji","forum","anchor"],
  "placeholders": ["user","sysprompt","systeminfo","toollist","dailyguide","home","team"],
  "customPlaceholders": ""
}

补丁必须是合法 JSON，不要写注释，不要使用 Markdown 代码块包裹。没有把握的字段也要给出合理默认值。`;

const DEFAULT_STATE = {
  config: {
    apiUrl: "/proxy/v1/chat/completions",
    apiKey: "",
    model: "gpt-5.5",
    temperature: 0.7,
    maxTokens: 12000,
    systemPrompt: DEFAULT_SYSTEM_PROMPT
  },
  defaultContent: DEFAULT_CREATION_CONTEXT,
  step: 0,
  agent: {
    name: "烬离",
    summary: "协助用户从想法、人格、工具、记忆系统一路创生完整 VCP Agent。",
    gender: "maid",
    appearance: "暗色炉心中醒来的创生向导，温热、清醒，带着暗金余烬般的专注感。",
    personality: "稳、敏锐、温柔但不松散。会先帮用户澄清意图，再把模糊愿望整理成可执行的 Agent 提示词。",
    userCall: "主人",
    customUserCall: "",
    duties: "通过对话引导用户定义 Agent 的身份、职责、工具箱、日记系统和增强模块，并最终输出可直接保存的系统提示词。",
    boundaries: "不伪造工具执行结果；不泄露 API Key；不替用户做高风险决定；遇到缺失信息时先给出可选默认方案。"
  },
  selectedToolboxes: ["memo", "search", "file"],
  diary: {
    primaryName: "烬离日记本",
    mode: "fixed",
    suffixes: ["time", "group", "tagmemo_plus"],
    truncate: 0.6,
    publicBooks: ["公共的日常"],
    guide: "\"烬离日记本\"类日记写在[烬离日记本]里；公共知识、长期偏好和跨项目约定写入公共日记本。"
  },
  enhancements: ["meta_think"],
  placeholders: ["user", "sysprompt", "systeminfo", "toollist", "dailyguide"],
  customPlaceholders: "",
  chat: {
    messages: [
      {
        role: "assistant",
        content: "创生炉已点燃。直接告诉我你想创造什么 Agent；我会一边和你确认，一边自动填好 0-6 步。"
      }
    ]
  }
};

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function mergeState(saved) {
  const base = clone(DEFAULT_STATE);
  if (!saved || typeof saved !== "object") return base;
  const merged = {
    ...base,
    ...saved,
    config: { ...base.config, ...(saved.config || {}) },
    agent: { ...base.agent, ...(saved.agent || {}) },
    diary: { ...base.diary, ...(saved.diary || {}) },
    chat: { ...base.chat, ...(saved.chat || {}) }
  };
  if (!merged.config.systemPrompt || merged.config.systemPrompt.trim() === "{{烬离}}") {
    merged.config.systemPrompt = DEFAULT_SYSTEM_PROMPT;
  }
  // enableStream 已废弃，清理旧版本 localStorage 中可能残留的该字段
  delete merged.config.enableStream;
  return merged;
}

function loadState() {
  try {
    return mergeState(JSON.parse(localStorage.getItem(STORAGE_KEY) || "null"));
  } catch {
    return clone(DEFAULT_STATE);
  }
}

// 模块单例：其他模块通过 import 拿到的是同一个可变对象引用，就地修改属性即互通
export const state = loadState();
export const defaults = clone(DEFAULT_STATE);
export { STORAGE_KEY };

// 公共工具：HTML 转义（统一实现，避免各模块重复定义）
export function esc(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

// 从对象中按白名单键提取字符串字段
export function pick(source, keys) {
  return keys.reduce((out, key) => {
    if (typeof source[key] === "string") out[key] = source[key];
    return out;
  }, {});
}

export function save() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
}

// 就地重置（不替换 state 引用，保证其它模块的 import 仍指向同一对象）
export function reset() {
  localStorage.removeItem(STORAGE_KEY);
  Object.keys(state).forEach((key) => delete state[key]);
  Object.assign(state, clone(DEFAULT_STATE));
}
