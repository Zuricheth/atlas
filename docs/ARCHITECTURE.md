# Atlas 架构设计

> 这份文档讲清"为什么这么设计",适合面试讲解和全局理解。

## 1. 设计目标

Atlas 解决一个问题:**个人资料越来越多(PDF、笔记、网页、文档),但检索难、没法问、散落各处**。

目标不是做又一个 Notion,而是验证一套 **"导入 → 自动整理 → 语义检索 → RAG 问答 → 认知图谱"** 的完整 AI 知识库工程链路。设计时优先三件事:

1. **闭环可用**:从导入到问答端到端跑通,不是只做检索或只做问答
2. **工程扎实**:事务、缓存、降级、分页、异步,这些生产级问题都真实处理
3. **Provider 无关**:LLM/Embedding 走 OpenAI 兼容协议,换提供商不改代码

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                      浏览器 (Vue 3 SPA)                      │
│  Pinia store(auth/ui/notebook/trash) · UI Kit · 5 主题     │
└───────────────────────────┬─────────────────────────────────┘
                            │ HTTP
              ┌─────────────┴─────────────┐
              │   REST (JSON)   ·   SSE    │
              └─────────────┬─────────────┘
                            │ Bearer JWT
┌───────────────────────────▼─────────────────────────────────┐
│                  Spring Boot 后端 (15 域)                    │
│                                                              │
│  ┌─────────┐ ┌─────────┐ ┌──────────┐ ┌─────────────────┐  │
│  │  auth   │ │ notebook│ │   note   │ │     search      │  │
│  │ (JWT)   │ │  (树)   │ │ (版本史) │ │ (kw/语义/hybrid)│  │
│  └─────────┘ └─────────┘ └──────────┘ └────────┬────────┘  │
│  ┌─────────┐ ┌─────────┐ ┌──────────┐ ┌────────▼────────┐  │
│  │ library │ │  paper  │ │ document │ │  rag (向量化)   │  │
│  │ (资料)  │ │ (论文)  │ │ (MinerU) │ │ embed + 向量检索│  │
│  └─────────┘ └─────────┘ └──────────┘ └────────┬────────┘  │
│  ┌─────────┐ ┌─────────┐ ┌──────────┐ ┌────────▼────────┐  │
│  │  chat   │ │deepwiki │ │   vcp    │ │  ai (provider/  │  │
│  │ (RAG问答)│ │(认知图谱)│ │(记忆同步)│ │  model/agent)   │  │
│  └─────────┘ └─────────┘ └──────────┘ └─────────────────┘  │
│  ┌─────────┐ ┌─────────┐ ┌──────────┐                       │
│  │ inbox   │ │  asset  │ │  trash   │                       │
│  │ (投递)  │ │ (资产)  │ │ (回收站) │                       │
│  └─────────┘ └─────────┘ └──────────┘                       │
└──────┬──────────────┬──────────────┬───────────────┬────────┘
       │              │              │               │
       ▼              ▼              ▼               ▼
   MySQL/H2       Qdrant        MinerU API      LLM API
   16 张表        向量库         PDF/Word→MD     Chat/Embedding
```

## 3. 技术选型理由

| 选择 | 为什么 |
|---|---|
| **Spring Boot 3.5 + Java 17** | 成熟生态、强类型、适合中大型后端;Java 17 的 record/switch 表达式让 DTO 和分支更简洁 |
| **MyBatis-Plus** | 比 JPA 更贴近 SQL(复杂检索可手写),比裸 MyBatis 少写 CRUD;LambdaQueryWrapper 类型安全 |
| **Flyway** | 数据库版本化管理,迁移可追溯。注意:本地 H2 默认走 schema-h2.sql 跳过 Flyway(见已知债务) |
| **H2 + MySQL 双轨** | H2 本地零依赖跑通,MySQL 生产。同 schema 两份方言维护(见债务) |
| **Spring Security + JWT** | 无状态鉴权,水平扩展友好。JwtAuthenticationFilter 解析 token → 注入 CurrentUser |
| **Qdrant(可选)+ MySQL 兜底** | Qdrant 做向量检索(快),未启用时 MySQL 存 embedding + 内存算余弦(慢但可用)。**双轨保证没 Qdrant 也能跑** |
| **MinerU** | PDF/Word → Markdown 的工业级解析,支持公式表格。比 PDFBox 纯文本提取质量高一个量级 |
| **OpenAI 兼容协议** | 一套客户端代码适配 OpenAI/NewAPI/Ollama/vLLM 等所有兼容网关,换 provider 只改配置 |
| **Vue 3 + Pinia** | 组合式 API + 类型推导好;Pinia 比 Vuex 简洁,setup store 写法贴合 Composition API |
| **Vite** | 开发启动快、HMR 快;build 用 Rollup 产物标准 |
| **KaTeX** | 数学公式渲染,比 MathJax 快、体积小,Obsidian/Notion 同款 |

## 4. 核心数据流

### 4.1 导入一篇 PDF 的完整链路

```
用户上传 PDF
  │
  ▼
PaperService.importPaperWithAi
  │ 1. 校验 + 落盘 (UUID 文件名,按 userId 分目录)
  │ 2. @Transactional 开启,registerFileRollback(失败时删孤儿文件)
  ▼
DocumentConversionService → MineruClient
  │ 3. 上传到 Mineru → 轮询解析结果 → 下载 zip → 提取 Markdown
  ▼
ChatClient.complete (LLM)
  │ 4. 用结构化 prompt 把原文整理成论文笔记(中英文标题/摘要/创新点/...)
  ▼
NoteService.createWithExtraIndexText
  │ 5. 写 note 表 + EmbeddingPipeline.rebuildChunks(切分 chunk 写 note_chunk)
  │ 6. scheduleEmbedAfterCommit ← 关键:事务提交后才 dispatch 异步 embed
  ▼
(事务提交) → @Async embedAsync
  │ 7. 异步线程读 chunks → 调 Embedding API → 写回 embedding → upsert 到 Qdrant
  ▼
搜索/问答可用了
```

**关键设计点**:
- **事务后异步**:笔记写库和向量化解耦。用户不用等 embedding(可能几秒)才得到响应。用 `TransactionSynchronizationManager.registerSynchronization` 在 `afterCommit` 触发,避免异步线程读到未提交的 chunks(经典脏读坑)
- **文件回滚**:`Files.transferTo` 后注册同步器,事务回滚时删物理文件,不留孤儿
- **降级**:MinerU 失败 → PDFBox 纯文本兜底;LLM 失败 → 抛 BizException 提示用户

### 4.2 RAG 问答的完整链路

```
用户提问 "这篇论文的创新点是什么"
  │
  ▼
GET /api/chat/rag/stream?question=...&notebookId=...  (SSE)
  │
  ▼
ChatService.ragStream
  │ 1. SearchService.retrieveHybridChunks(userId, query, topK, notebookId)
  │    ├─ 语义召回:embed(query) → Qdrant 向量检索(或 MySQL 内存余弦)
  │    ├─ keyword 命中加权:命中关键词的 chunk +0.15 分
  │    └─ notebookId scope:只保留该知识库子树的 chunk
  │ 2. 先发 SSE citations 事件(让前端边等边看来源)
  ▼
ChatClient.completeStream (LLM 流式)
  │ 3. system prompt: "只依据片段回答,引用编号"
  │    user prompt: [1] noteId=.. chunkIndex=.. score=.. + 片段正文
  │ 4. 每个 delta → SSE delta 事件
  ▼
前端 fetch ReadableStream 逐字渲染 + 引用列表
```

详见 [RAG.md](RAG.md)。

## 5. 关键设计决策

### 5.1 为什么笔记是"双轨"(人类轨 + AI 记忆轨)

笔记内容用特殊分隔符协议:
```
<<<ATLAS_HUMAN_NOTE>>>
给人看的 Markdown,有排版
<<<END_ATLAS_HUMAN_NOTE>>>
<<<VCP_AI_MEMORY>>>
给 RAG/VCP 用的高密度纯文本,末尾带 Tag:
<<<END_VCP_AI_MEMORY>>>
```
**理由**:人看的笔记要美观排版,但 RAG/向量检索要纯文本高密度。一份内容两种用途,用协议分隔,VCP 同步时只抽记忆轨。AI 生成时强制此协议,避免污染。

### 5.2 为什么 DeepWiki 的认知地图用 Mermaid + click 交互

生成 DeepWiki 时,prompt 要求 LLM 输出 Mermaid `graph TD`,关键节点带 `click X href "atlas://note/123"`。前端自写轻量 Mermaid 解析器,把节点渲染成可点击 DOM,点击跳转笔记。

**理由**:不引重型 mermaid.js(300KB+),自写 200 行解析器够用;LLM 生成的图本身就是"知识的抽象关系",可点击就让静态摘要变成**可导航的知识图谱雏形**。

### 5.3 为什么 embedding 要查询级缓存

一次 hybrid 搜索内部其实调 2 次 embedding(hits + tree 各一次)。加 LRU 缓存(200 条,5 分钟 TTL,Key = 模型+维度+查询文本)后:
- 首次查询:1 次 embed API 调用
- 5 分钟内重复查询:0 次(直接命中)
- 同一次搜索内部的 hits+tree:第 2 次命中缓存

这是把最坏 60 秒压到 30ms 的关键。详见 [RAG.md](RAG.md)。

### 5.4 为什么前端用 storeToRefs 别名而非直接重写

迁移到 Pinia 时,App.vue 有 4000+ 行、47 处引用 currentNotebookId。重写所有引用风险高,所以用 `const { currentNotebookId } = storeToRefs(notebookStore)` 别名,**代码体不动,只改声明**。增量迁移,每抽一个域都 build 验证。

### 5.5 为什么主题系统是 data-theme × data-mode 两个维度

`<html data-theme="nocturne" data-mode="dark">` —— 调色板(墨青/羊皮/黑曜石/晨雾/夜曲)和浅深模式独立组合。用户选"夜曲"+ 浅色也能跑(夜曲专为深色设计,但浅色有兼容兜底)。比"每套主题写 8 份 CSS"更可持续。详见 [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md)。

## 6. 数据模型(16 张表)

```
用户与权限     atlas_user
知识组织       notebook (树:parent_id + node_type[domain/project/collection])
笔记           note (含 search_text 全文) + note_history (版本) + note_tag
资料           library_item + paper_attachment
向量化         note_chunk (embedding 向量 + dim + model + status)
AI 配置        ai_provider + ai_model + ai_active + ai_agent
高级功能       deepwiki_page + vcp_memory_draft + inbox_request + inbox_file
分类           tag
```

关键关系:
- `notebook` 自引用 parent_id 构成三级树(domain → project → collection,只有 collection 能装笔记)
- `note_chunk` 是 RAG 核心:一篇笔记切成多个 chunk,每个 chunk 有自己的 embedding
- `note_history` 每次更新前存快照,note_version 自增

完整 schema 见 `atlas-backend/src/main/resources/db/migration/V2__create_current_schema.sql`。

## 7. 快速开始

### 前置准备
- Java 17+、Node 18+、Maven(用项目自带 `./mvnw`)
- 可选:Docker(起 Qdrant)、MySQL(生产,否则用 H2)

### 配置
```bash
cp config.env.example config.env
```
编辑 `config.env` 关键项:
```
ATLAS_JWT_SECRET=your-secret-at-least-32-bytes-long-random
MINERU_API_TOKEN=ey...           # mineru.net 注册获取
# 以下二选一(走 OpenAI 兼容网关即可):
ATLAS_OPENAI_BASE_URL=http://localhost:6005/v1
ATLAS_OPENAI_API_KEY=sk-...
```

### 启动
```bash
# 后端(默认 H2,零外部依赖)
cd atlas-backend && ./mvnw spring-boot:run
# 前端
cd atlas-frontend && npm install && npm run dev
# 打开 http://localhost:5173
```

### 第一次使用
1. 用"演示用户 / atlas123456"登录(或注册新账号)
2. 进 **AI 设置**:配渠道 + 拉模型 + 选 Chat/Embedding + 测试向量
3. 进 **工作台**:建知识库 → 导入资料 → 搜索/问答
4. 进 **DeepWiki**:选知识库 → 生成认知地图

## 8. 项目结构

```
项目/
├── atlas-backend/              后端
│   └── src/main/java/com/qianyu/atlas/
│       ├── auth/ notebook/ note/ library/ paper/    核心 CRUD
│       ├── rag/                检索 + 向量化核心
│       ├── chat/ deepwiki/ vcp/ inbox/              高级功能
│       ├── ai/ asset/ trash/ tag/                   支撑域
│       ├── common/ config/ security/ document/      基础设施
│       └── AtlasBackendApplication.java
├── atlas-frontend/             前端
│   └── src/
│       ├── App.vue             主应用(待继续拆分)
│       ├── components/ui/      自研 UI Kit(11 组件)
│       ├── components/art/     主题艺术层(SVG)
│       ├── stores/             Pinia(auth/ui/notebook/trash)
│       ├── lib/                api/apiClient/markdown/theme
│       ├── design/             tokens.css + themes.css
│       └── types/              共享类型
├── docs/                       本文档
└── config.env.example          配置模板
```

---

继续阅读:[BACKEND.md](BACKEND.md) · [FRONTEND.md](FRONTEND.md) · [RAG.md](RAG.md) · [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md) · [ROADMAP.md](ROADMAP.md)
