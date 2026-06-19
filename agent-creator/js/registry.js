// 静态注册表：步骤、工具箱、预设、DSL、增强模块、占位符、枚举选项。纯数据，无副作用。

export const VCPA_STEPS = [
  { id: 0, key: "basic", name: "基础信息" },
  { id: 1, key: "persona", name: "人设编辑" },
  { id: 2, key: "tools", name: "工具箱" },
  { id: 3, key: "diary", name: "日记系统" },
  { id: 4, key: "enhance", name: "增强模块" },
  { id: 5, key: "placeholders", name: "系统占位符" },
  { id: 6, key: "export", name: "预览导出" }
];

export const TOOLBOXES = [
  { id: "memo", name: "记忆工具", placeholder: "{{VCPMemoToolBox}}", description: "日记读写、知识检索、RAG 召回。几乎所有 Agent 都需要。", tags: ["核心", "记忆", "RAG"] },
  { id: "search", name: "搜索与爬虫", placeholder: "{{VCPSearchToolBox}}", description: "联网搜索、页面读取、信息查证，适合研究与资料整理。", tags: ["检索", "网页"] },
  { id: "file", name: "文件与开发", placeholder: "{{VCPFileToolBox}}", description: "读写文件、整理项目、辅助代码工作流。", tags: ["文件", "开发"] },
  { id: "media", name: "多媒体", placeholder: "{{VCPMediaToolBox}}", description: "图像、音频、视频等多媒体创作与处理。", tags: ["创意", "媒体"] },
  { id: "contact", name: "通讯与社交", placeholder: "{{VCPContactToolBox}}", description: "消息、联系人、社交协作类 Agent 的外部触达能力。", tags: ["通讯", "社交"] }
];

export const PRESETS = [
  { id: "lite", name: "精简", tools: ["memo"] },
  { id: "standard", name: "标准", tools: ["memo", "search", "file"] },
  { id: "full", name: "全能", tools: ["memo", "search", "file", "media", "contact"] },
  { id: "creative", name: "创意", tools: ["memo", "media", "search"] },
  { id: "dev", name: "开发", tools: ["memo", "file", "search"] }
];

export const DSL_SUFFIXES = [
  { id: "time", syntax: "::Time", name: "时间感知", description: "解析自然语言时间线索并融合召回。" },
  { id: "group", syntax: "::Group", name: "语义组", description: "增强语义分组和上下文聚合。" },
  { id: "tagmemo_plus", syntax: "::TagMemo+", name: "测地线拓扑", description: "使用更强的拓扑增强召回。" },
  { id: "tagmemo", syntax: "::TagMemo", name: "基础拓扑", description: "基础标签拓扑召回。" },
  { id: "rerank", syntax: "::Rerank", name: "精排", description: "超量召回后重排序。", exclusive: "rerank_plus" },
  { id: "rerank_plus", syntax: "::Rerank+", name: "双路精排", description: "双路融合精排。", exclusive: "rerank" },
  { id: "expand", syntax: "::Expand", name: "父文档展开", description: "召回后展开父文档上下文。" },
  { id: "associate", syntax: "::Associate", name: "联想共现", description: "按共现关系扩展记忆。" },
  { id: "base64memo", syntax: "::Base64Memo", name: "附件召回", description: "支持多模态附件召回。" },
  { id: "aimemo", syntax: "::AIMemo", name: "AI 记忆", description: "由 AI 辅助记忆提取。" },
  { id: "aimemo_plus", syntax: "::AIMemo+", name: "AI 记忆+", description: "更强的 AI 记忆提取。" }
];

export const ENHANCEMENTS = [
  { id: "meta_think", name: "元思考模块", required: true, template: "————VCP元思维模块————\n[[VCP元思考::Group]]\n————VCP元思考加载结束—————" },
  { id: "rendering", name: "视觉通感协议", template: "{{VarRendering}}\n{{VarDivRender}}" },
  { id: "emoji", name: "表情包系统", template: "{{TarEmojiPrompt}}" },
  { id: "forum", name: "论坛模块", template: "{{VarForum}}\n{{VCPForumLister}}" },
  { id: "anchor", name: "联想锚定说明", template: "联想锚定：当发现关键人物、长期偏好、反复任务或专有名词时，请主动记录可复用锚点。" }
];

export const SYSTEM_PLACEHOLDERS = [
  { id: "user", syntax: "{{VarUser}}", name: "用户称呼", required: true },
  { id: "sysprompt", syntax: "{{TarSysPrompt}}", name: "时间/地点/天气", required: true },
  { id: "systeminfo", syntax: "{{VarSystemInfo}}", name: "系统信息", required: true },
  { id: "toollist", syntax: "{{VarToolList}}", name: "工具调用总格式", required: true },
  { id: "dailyguide", syntax: "{{VarDailyNoteGuide}}", name: "日记系统指南", required: true },
  { id: "home", syntax: "{{VarHome}}", name: "家的描述" },
  { id: "team", syntax: "{{VarTeam}}", name: "团队成员" }
];

export const GENDER_OPTIONS = [
  { id: "maid", name: "maid" },
  { id: "valet", name: "valet" },
  { id: "neutral", name: "neutral" }
];

export const USER_CALL_OPTIONS = [
  { id: "主人", name: "主人" },
  { id: "你", name: "你" },
  { id: "您", name: "您" },
  { id: "custom", name: "自定义" }
];
