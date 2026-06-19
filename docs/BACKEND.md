# Atlas 后端模块说明

> 15 个业务域,逐个讲清"做什么、关键类、API、实现要点"。改某个域前先读这里。

包路径统一前缀:`com.qianyu.atlas.<域>`。每个域典型结构:`XxxController`(REST)→ `XxxService`(业务)→ `XxxMapper`(MyBatis-Plus)→ `Xxx`/`XxxDtos`(实体/DTO)。

---

## 🔐 auth · 认证

**职责**:用户注册、登录、JWT 签发与校验。

| 关键类 | 作用 |
|---|---|
| `AuthController` | `POST /auth/register`、`POST /auth/login` |
| `AuthService` | 注册(BCrypt 加密)、登录(校验后签 JWT) |
| `security/JwtService` | JWT 签发/解析(HS256),token 含 sub/user/iat/exp |
| `security/JwtAuthenticationFilter` | 每个请求解析 Authorization header → 注入 `CurrentUser` |
| `security/CurrentUser` | `@AuthenticationPrincipal` 拿当前用户 id |

**实现要点**:
- 密码用 BCrypt 存储,不存明文
- JWT 无状态,过期靠 exp 字段;无 refresh token / 黑名单(见已知债务)
- `SecurityConfig` 放行 `/auth/**`、`/health`,其余需认证

---

## 📒 notebook · 知识库树

**职责**:三级知识库树(domain 领域 → project 项目 → collection 资料库)。

| 关键类 | 作用 |
|---|---|
| `NotebookController` | CRUD + merge(合并子库)+ rename |
| `NotebookService` | `descendantNotebookIds`(递归拿子树,多处复用)、`ensureCollectionPath`(按路径自动建树) |
| `Notebook` | node_type 区分 domain/project/collection,**只有 collection 能装笔记** |

**实现要点**:
- `ensureCollectionPath` 是文件夹导入的核心:AI 规划出"领域/项目/资料库"路径后,这个方法自动把不存在的节点建出来,已有的复用
- 树查询用一次 `selectList` + 内存组装(避免递归 SQL)

---

## 📝 note · 笔记 + 版本历史

**职责**:笔记 CRUD、双轨协议解析、版本快照与回滚。

| 关键类 | 作用 |
|---|---|
| `NoteController` | CRUD + `GET /{id}/history` + `POST /{id}/history/{hid}/rollback` + SSE Agent 写笔记 |
| `NoteService` | `create/update`(@Transactional,更新前 `saveHistorySnapshot`)、`generateAgentNote`(调 AI 写双轨笔记)、`streamAgentNote`(SSE) |
| `NoteHistory` + `NoteHistoryMapper` | 版本快照表,每次 update 前存一条,note_version 自增 |

**实现要点**:
- 笔记写库 + 向量化在**同一事务**,embedding 用 `scheduleEmbedAfterCommit`(事务提交后才异步 dispatch,避免脏读)
- 版本历史:rollback 时先把当前内容存一条快照(保底),再把目标历史写回 note + 重建索引
- 双轨协议(`<<<ATLAS_HUMAN_NOTE>>>` / `<<<VCP_AI_MEMORY>>>`)由 Agent 生成,`VcpMemoryDraftService.captureFromAgentNote` 抽取记忆轨

---

## 🔍 search · 检索(三模式)

**职责**:关键词、语义、混合三种检索 + 命中明细 + 搜索树聚合。

| 关键类 | 作用 |
|---|---|
| `SearchController` | `/search/{keyword,semantic,hybrid,matches,tree}` |
| `SearchService` | 门面,委派到下面四个子服务 |
| `KeywordSearchService` | 关键词 + hybrid 融合(关键词候选 ∪ 语义候选,加权排序) |
| `SemanticRetrievalService` | 语义召回:embed(query) → Qdrant,失败 fallback MySQL 内存余弦;**带查询级 LRU 缓存** |
| `SearchMatchService` | 精确命中片段(matchStart/matchEnd,前端高亮用) |
| `SearchTreeService` | 按知识库树聚合搜索结果(哪些库命中) |

**三模式**:
- `keyword`:SQL LIKE + 全文索引,本地极速
- `semantic`:纯向量召回,理解语义但慢(要 embed)
- `hybrid`:关键词候选 + 语义候选加权融合,质量最好

详见 [RAG.md](RAG.md)。

---

## 📚 library · 资料库

**职责**:导入任意格式文件、保存原文件、提取正文、生成笔记、文件夹批量导入。

| 关键类 | 作用 |
|---|---|
| `LibraryController` | import / auto-import / folder-import / folder-plan / list / export(zip) / getFile |
| `LibraryService` | 导入 + AI 分类 + 文件夹规划(LLM 规划知识库树) |
| `LibraryItem` | 资料条目(关联 noteId、原文件路径、extracted_text) |

**实现要点**:
- **文件夹导入** 是最复杂的功能:批量落盘 → AI 规划(结构优先 + 不确定的标 uncertain) → 复判(读全文重新归类) → 按规划入库。每个文件走独立事务(self-injection + `@Lazy` 解决自调用事务失效),失败时清理孤儿物理文件
- **事务 + 文件回滚**:`importItem`/`autoImport` 标 `@Transactional`,落盘后注册 `TransactionSynchronization.afterCompletion`,回滚时删物理文件
- export 用 ZipOutputStream 流式打包,处理缺失文件和重名

---

## 📄 paper · 论文专用导入

**职责**:PDF 论文的两种导入路径(手动贴 Markdown / AI 解析生成)。

| 关键类 | 作用 |
|---|---|
| `PaperController` | import(手动)、importWithAi(AI)、getFile |
| `PaperService` | PDF 落盘 + MinerU 提取 + LLM 生成结构化论文笔记(中英文标题/摘要/创新点/方法实验/局限/...) |
| `PaperAttachment` | 论文附件(关联 note、原 PDF 路径、extracted_text) |

**实现要点**:
- AI 论文笔记 prompt 强制 8 段结构,便于长期检索
- 同样用事务 + 文件回滚 + 事务后异步 embed

---

## 📑 document · 文档解析

**职责**:把 PDF/Word/docx 转成 Markdown,是 library/paper 的底层依赖。

| 关键类 | 作用 |
|---|---|
| `DocumentConversionService` | 统一入口,判断是否需要转换(`shouldConvert`) |
| `MineruClient` | MinerU API 客户端:上传 → 轮询 → 下载 zip → 提取 Markdown |
| `DocxMarkdownExtractor` | docx 本地解析兜底(不调 API) |
| `MineruProperties` | 配置(base-url、token、超时、重试) |

**实现要点**:
- Mineru 有两套 API:batch(传统)和 agent(轻量),`canUseAgentLightweight` 按文件大小选
- 下载重试:仅 429/5xx 重试,指数退避 + 抖动
- MinerU 失败时,PDF 走 PDFBox 纯文本兜底(无 OCR,扫描件不可用)

---

## 💬 chat · RAG 问答

**职责**:基于知识库的问答,带来源引用,SSE 流式。

| 关键类 | 作用 |
|---|---|
| `ChatController` | `POST /chat/rag`(同步)、`GET /chat/rag/stream`(SSE 流式) |
| `ChatService` | retrieveHybridChunks → 拼 prompt → completeStream,SSE 发 citations/delta/done/error |
| `ChatClientFactory` | 按 active model 创建 `OpenAiCompatibleChatClient`;无配置时用 `LocalRagChatClient` 兜底(正则抽片段) |
| `OpenAiCompatibleChatClient` | OpenAI 兼容协议实现,SSE 流式解析,429/5xx 指数退避重试 |

**实现要点**:
- 流式用 `SseEmitter`(0L 永不超时)+ `agentExecutor` 异步
- citations 在 delta 之前发,前端边等边看来源
- notebookId scope:限定只在该知识库子树召回(见 RAG.md)

---

## 🗺 deepwiki · 认知地图

**职责**:一键生成知识库的"认知层"Wiki 页。

| 关键类 | 作用 |
|---|---|
| `DeepWikiController` | `POST /generate`、`GET /latest` |
| `DeepWikiService` | 聚合知识库子树笔记(MAX_NOTES=40)+ 文件 → 拼大 context → LLM 生成 7 段 Markdown(全景/认知地图/知识结构/概念表/阅读路径/追问/来源) |
| `DeepWikiPage` | 持久化实体,(userId, notebookId, mode, focusKey) 唯一,支持 home/map/topic 三模式 |

**实现要点**:
- Mermaid `click X href "atlas://note/id"`:prompt 要求 LLM 给 3-6 个关键节点标注跳转链接,前端解析后节点可点击
- **stale 脏检测**:`latest` 响应带 `stale` 字段,后端比较知识库子树笔记 max(updatedAt) 与 wiki 页 updatedAt,提示用户"内容已更新,建议重新生成"
- context 截断到 65KB,避免超 LLM 上下文

---

## 🔗 vcp · 长期记忆同步

**职责**:把 AI 生成的"记忆轨"笔记同步到 VCP(SillyTavern 风格)日记本系统。

| 关键类 | 作用 |
|---|---|
| `VcpController` | 17 个端点:笔记本 CRUD、文件读写/迁移、draft 全生命周期(草稿/审核/同步/忽略)、批量建议、传输 |
| `VcpMemoryDraftService` | draft 状态机(pending/review/synced/ignored/failed) |
| `VcpNotebookService` | VCP 笔记本(本地文件夹)的扫描/搜索/文件操作 |
| `VcpProperties` | 配置(VCP 根目录、工作日记本) |

**实现要点**:
- VCP 笔记本是**本地文件夹系统**(不是数据库),VcpNotebookService 用文件系统 API 操作
- draft 是"待审核的同步任务":AI 生成记忆 → 进 draft(pending)→ 用户审核 → sync(写入 VCP 文件)或 ignore
- 批量建议:LLM 给一批 draft 建议目标笔记本

---

## 📥 inbox · 投递箱

**职责**:外部项目整包资料投递(如 AI 生图项目一次投 60 张图 + README)。

| 关键类 | 作用 |
|---|---|
| `InboxController` | create(提交一批文件)/ list / get / accept(入库)/ plan(AI 规划)/ reject |
| `InboxService` | 落盘 + AI 规划(读 README/说明文件 + 文件清单,生成入库计划) |
| `InboxRequest` / `InboxFile` | 投递请求 + 其包含的文件 |

**实现要点**:
- 单次最多 100 个文件(应用层保护),Tomcat 层放宽到 20000 part(见 `TomcatMultipartTuning`)
- AI 规划优先读 README/说明文件,没有则用文件清单 + 来源信息生成保守计划

---

## 🗂 asset · 资产管家

**职责**:像文件管理器一样管理所有原文件,空间统计。

| 关键类 | 作用 |
|---|---|
| `AssetController` | summary(空间账单)/ items(筛选)/ trash(批量)/ export(批量 zip) |
| `AssetService` | 聚合 library_item + paper_attachment 的统计/查询 |

**实现要点**:
- 跨域聚合:asset 不是独立存储,是 library + paper 的只读视图
- summary 按桶(图片/文档/...)统计占用 + 可清理空间

---

## 🏷 tag · 标签

**职责**:笔记的多对多标签。

| 关键类 | 作用 |
|---|---|
| `TagController` | create / list / setNoteTags / listByNote |
| `TagService` | `setNoteTagsByName`(按名字设标签,不存在则创建) |

**实现要点**:
- `note_tag` 多对多关联表
- 导入时 AI 生成的标签通过 `setNoteTagsByName` 写入

---

## 🗑 trash · 回收站

**职责**:30 天软删除保留 + 恢复 + 彻底清除。

| 关键类 | 作用 |
|---|---|
| `TrashController` | list / restore(按 kind)/ purge / purgeAll / purgeExpired |
| `TrashService` | 聚合 note/library/paper 的已删除项,统一列表 |

**实现要点**:
- 软删除:note/library/paper 都有 `deleted` 字段 + `updated_at`,过期靠时间判断
- restore 按 kind 路由到对应表的恢复逻辑

---

## 🤖 ai · AI 配置管理

**职责**:Provider/Model/Agent 的 CRUD + 激活 + 模型同步。

| 关键类 | 作用 |
|---|---|
| `AiAdminController` | `/admin/ai/providers|models|agents|active|newapi/sync-models|embedding/test|embedding/local-fallback` |
| `AiAdminService` | 配置管理 + NewAPI 模型同步(拉网关的模型列表入库) + embedding 测试 |
| `ProviderRegistry` | 当前激活的 chat/embedding model 缓存 |
| `AiAgentService` | Agent 调用(complete/completeStream),按 agentId 取系统提示词 + 模型 |
| `EmbeddingClientFactory` / `ChatClientFactory` | 按 active model 创建客户端,带实例缓存 |

**实现要点**:
- **NewAPI 同步**:填一个网关地址 + key,一键拉取所有可用模型,按 name 猜 chat/embedding,自动入库
- **embedding 测试**:发一条测试文本,返回实际维度,校验配置正确
- **local-fallback**:无 embedding 配置时切到 `HashEmbeddingClient`(纯本地哈希,跑通但不智能)

---

## 🏥 common / config · 基础设施

| 类 | 作用 |
|---|---|
| `common/ApiResponse` | 统一响应 `{code, message, data}` |
| `common/BizException` | 业务异常,带 code(404/500/...) |
| `common/GlobalExceptionHandler` | `@RestControllerAdvice` 统一异常 → ApiResponse |
| `common/HealthController` | `/health` 健康检查 |
| `config/AsyncConfig` | `embeddingExecutor` + `agentExecutor` 两个线程池(CallerRunsPolicy 背压) |
| `config/SecurityConfig` | Security 过滤链、CORS、路由放行 |
| `config/TomcatMultipartTuning` | 放宽 multipart 文件数上限到 20000(支持批量投递) |

---

继续阅读:[FRONTEND.md](FRONTEND.md) · [RAG.md](RAG.md) · [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md) · [ROADMAP.md](ROADMAP.md)
