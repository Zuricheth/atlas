# Atlas 技术债与演进路线

> 诚实自评:这个项目能讲什么、还欠什么、接下来怎么走。能说清自己项目的不足,是工程成熟的标志。

## 1. 当前完成度(2026-06)

作为一个**个人知识库**,功能闭环完整、体验可用、有设计感。整体 8/10:
- 功能完整度 ⭐⭐⭐⭐⭐
- 视觉设计 ⭐⭐⭐⭐⭐
- 性能 ⭐⭐⭐⭐(Qdrant 未启用是大库短板)
- 后端工程质量 ⭐⭐⭐⭐(测试覆盖低)
- 前端工程质量 ⭐⭐⭐(App.vue 单体未拆完)
- 安全 ⭐⭐(个人用先放,但有真实风险)
- 数据治理 ⭐⭐⭐(schema 双轨)

## 2. 已知技术债(按风险排序)

### 🔴 高危(若要给别人用/上线,必须处理)

#### 安全三件套
| 问题 | 位置 | 风险 |
|---|---|---|
| JWT 默认弱 secret,仅 prod profile 校验 | `JwtService.java` | 本地/默认启动可用公开字符串伪造任意用户 token |
| config.env 含真实凭证(MinerU JWT) | `config.env` | 凭证泄露(虽 .gitignore,但已落盘) |
| 前端 v-html 渲染未 sanitize | `App.vue` 多处 | stored XSS(笔记/AI 输出是恶意源) |

**处理方向**:JWT 启动无条件校验密钥强度;真实凭证走 OS keychain/环境变量;前端引 DOMPurify + 协议白名单。

#### 数据库 schema 三轨
- 根目录 `schema.sql`(MySQL 旧版手工脚本)
- `schema-h2.sql`(H2 方言)
- `db/migration/V2__create_current_schema.sql`(Flyway,但默认 disabled)

三份会漂移。**处理方向**:默认启用 Flyway,H2 也走 Flyway vendor 目录,删 schema.sql / schema-h2.sql。

### 🟠 中等(影响可维护性)

#### 前端单体
- App.vue ~4500 行,note/search/asset/inbox/deepwiki/vcp 域未抽进 store
- 无 vue-router(用 appView 切换,刷新丢视图状态)
- 无错误边界(组件抛错白屏)

**处理方向**:继续增量抽 store(每次改某域顺手抽),再拆视图到 `src/views/` + 引 vue-router。

#### 测试覆盖
- 后端只有 `contextLoads` 冒烟测试,无业务逻辑测试
- 前端无测试

**处理方向**:RAG 检索、事务回滚、降级逻辑这些核心路径补单测。

#### 仓库卫生
- 仓库根混入 `tmp-*`、`paperreader-*-workspace`、`卷一1-9章`(小说素材)等无关产物
- `atlas-backend/data/`(H2 工作库 + 备份)在源码树
- `.gitignore` 不全

**处理方向**:`.gitignore` 补全 + `git rm --cached`,数据目录移到 `${user.home}/.atlas/`。

### 🟡 较低(优化项)

- 无 rerank / 查询改写(召回质量可再提)
- 无多轮对话(RAG 单轮)
- 笔记编辑器是 textarea(无富文本)
- 无 spaced repetition / 复习提醒
- 无分享/公开链接
- 启动脚本 7 个(可精简到 2)
- 前端 chunk > 500KB(KaTeX 体积,可 code-split)

## 3. 演进路线(按价值排序)

### Phase 1:夯实(如果继续投入)
1. **安全三件套**:JWT 强校验 + DOMPurify + 凭证迁移(1-2 天)
2. **schema 统一**:启用 Flyway,删双轨(半天)
3. **前端继续拆**:note/search 域抽 store + 引 vue-router(2-3 天)

### Phase 2:差异化
4. **Qdrant 启用 + 向量检索性能**:大库从 MySQL 兜底的 ~500ms → ~10ms(需 Docker)
5. **Rerank**:加 cross-encoder 二次排序,召回质量提升(延迟换质量)
6. **多轮对话**:conversationId + history,RAG 从单问变对话

### Phase 3:产品化
7. **知识图谱落地**:DeepWiki 的 Mermaid 地图目前是 LLM 即兴文本,可做实体抽取 + 关系表 + 真图谱
8. **Agent tool use**:让 AI 能建笔记/打标签/检索,从问答升级到助手
9. **分享/协作**(若定位变更)

## 4. 这个项目能讲什么(面试视角)

### 工程能力
- **全栈贯通**:数据库 → REST+SSE → JWT → Vue → 主题系统,一个人做完整产品
- **RAG 系统设计**:不是调 API,是自己实现 hybrid 融合、embedding 缓存、双轨向量库、故障降级
- **工程纪律**:事务边界、事务后异步 dispatch、文件回滚、分页防 OOM、HTTP 指数退避

### 设计能力
- **5 套主题设计系统**:每套有设计哲学,双维度架构(token × mode),可扩展
- **浪漫主义艺术层**:SVG 矢量装饰,零图片资源
- **prose 排版 + KaTeX**:阅读体验

### 可讲解性
- 每个模块有文档,能说清"为什么这么设计"
- 能说清技术债和取舍(不是无脑堆技术)

### 值得准备的追问
- "为什么不用 ES 做搜索?" → 全文索引够用 + 避免 ES 运维负担;Qdrant 做向量
- "hybrid 怎么融合的?" → 线性加权(精确分+词项分+语义分+时间衰减),不是 rerank(讲清取舍)
- "事务后异步为什么必要?" → 避免异步线程读到未提交 chunks(脏读)
- "embedding 缓存的 key 为什么不带 userId?" → embedding 输出只依赖查询文本+模型,全用户共享
- "为什么 SSE 用 fetch 不用 EventSource?" → EventSource 不能带 Authorization header
- "5 套主题怎么不互相冲突?" → `:root[data-theme]` 限定,每套重定义全套 token

## 5. 维护建议(给自己)

- 改任何业务域前,先看 `docs/BACKEND.md` 对应章节
- 加新功能,照 `stores/trash.ts` 样板抽 store
- 改样式,先看是不是 token 能解决的(避免硬编码色)
- 改 RAG,先看 `docs/RAG.md` 确认不破坏降级链路
- 每次 build 前后端都要过(`mvnw compile` + `npm run build`)

---

返回 [ARCHITECTURE.md](ARCHITECTURE.md)
