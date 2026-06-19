# Atlas RAG 系统深度剖析

> 这份文档讲透 Atlas 的检索增强生成(RAG)实现,适合面试讲 RAG 细节、性能优化、降级策略。

## 1. RAG 是什么(30 秒回顾)

RAG = Retrieval Augmented Generation。LLM 自己不知道你的私有资料,所以:
1. 用户提问 → 先**检索**出相关片段
2. 把片段塞进 prompt → LLM **生成**回答(只依据片段)
3. 回答带**引用**(哪几个片段)

核心矛盾:**召回质量** vs **延迟/成本**。Atlas 在这上面做了不少工程。

## 2. 数据流:从笔记到可被检索

### 2.1 向量化(写入路径)

```
note.content
  │
  ▼ TextChunker.split (TARGET=500字, OVERLAP=50)
  │   滑动窗口切块,保留重叠避免切断语义
  ▼
chunks[]  → note_chunk 表 (status=PENDING)
  │
  ▼ scheduleEmbedAfterCommit  ← 关键:事务提交后才触发
  ▼
@Async embedAsync
  │   EmbeddingPipeline.embedBatch (批量调 Embedding API)
  │   失败 → embedSingle 单条重试(指数退避)
  ▼
chunks 更新 (embedding 写入, status=READY)
  │
  ▼ VectorStoreClient.upsertAll
  ▼
Qdrant (按维度分 collection: atlas_note_chunks_3072)
```

**为什么 chunk 而不是整篇向量化**:
- 精确召回:一篇长笔记里只有一段相关,整篇向量会被稀释
- 上下文可控:召回后拼 prompt,chunk 大小决定每次给 LLM 多少上下文
- Atlas 用 500 字 + 50 字重叠,兼顾语义完整和精确度

### 2.2 检索(读取路径)

三种模式,从快到慢、从精确到语义:

```
keyword    SQL LIKE / MATCH AGAINST + 全文索引      ~30ms     精确,无 AI
semantic   embed(query) → 向量近邻搜索              ~1.5s     语义,要 embed
hybrid     keyword 候选 ∪ semantic 候选 → 加权融合  ~1.5s     质量最好
```

## 3. Hybrid 检索融合(核心算法)

`KeywordSearchService.hybrid` 是 Atlas 检索的精华:

```java
// 1. 关键词候选:SQL 搜出 top (limit*6) 篇笔记
Map<Long, Note> candidates = candidateNotes(userId, query, terms, limit*6);

// 2. 语义候选:向量召回 top (limit*6) 篇笔记
List<SearchHit> vectorHits = semantic(userId, query, limit*6);

// 3. 融合:对每个候选笔记,加权算分
for (Note note : candidates.values()) {
    HybridScore score = SearchTextSupport.hybridScore(
        note, notebookPath, query, terms, semanticHit
    );
    // score = 精确命中分 + 词项分 + 语义分 + 时间衰减分
}

// 4. 按 score 降序取 topK
```

### 加权细节(`SearchTextSupport.hybridScore`)
- **精确命中分**:标题/摘要/正文包含查询词加分(标题权重最高)
- **词项分**:分词后每个 term 的命中次数
- **语义分**:向量召回的 cosine 相似度(0~1)
- **时间衰减**:`recencyScore` —— 最近更新的笔记轻微加权(个人知识库偏好新内容)

这是**线性融合**(linear combination),不是 cross-encoder rerank。权衡:rerank 质量更高但要额外一次模型调用(慢+贵),线性融合零额外成本,对个人知识库够用。

## 4. 向量检索双轨:Qdrant + MySQL 兜底

`SemanticRetrievalService` 先查 Qdrant,空则 fallback MySQL:

```java
List<VectorSearchResult> results = vectorStoreClient.search(
    userId, model, providerId, dim, queryVector, limit
);
results = filterActive(results);  // 过滤已删除笔记
if (results.isEmpty()) {
    results = semanticChunksFromMysql(userId, context, limit);  // 兜底
}
```

### Qdrant(首选,快)
- 按**维度分 collection**:`atlas_note_chunks_3072`、`atlas_note_chunks_768`... 换 embedding 模型(维度变)不冲突
- 过滤条件:userId + model + providerId + dim(确保不同用户/模型隔离)
- Cosine 距离,limit 最多 50

### MySQL 兜底(慢但可用)
Qdrant 没启用或没结果时:
```java
// 拿用户所有 ready 的 chunk(最多 3000)
List<NoteChunk> chunks = noteChunkMapper.selectList(... limit 3000);
// 内存里逐个算余弦
for (NoteChunk chunk : chunks) {
    float[] vec = VectorCodec.decode(chunk.getEmbedding());
    scored.add(scored(chunk, cosine(queryVector, vec)));
}
scored.sort(desc);
```

**为什么有这个兜底**:MVP 阶段不想强制用户装 Qdrant(Docker + 容器),MySQL 兜底让链路跑通。生产用 Qdrant。

## 5. 性能优化(把最坏 60s 压到 30ms)

### 5.1 查询级 embedding 缓存(P0,最大收益)

**问题**:一次 hybrid 搜索内部其实调 **2 次** embed API:
```
/search/hybrid → semantic() → embed(query)   ← 第1次
/search/tree   → semantic() → embed(query)   ← 第2次(同一查询!)
```
而且前端 `Promise.all` 并发这两个接口。

**解决**:`SemanticRetrievalService` 加 LRU 缓存:
```java
private static final int CACHE_SIZE = 200;
private static final long CACHE_TTL_MS = 5 * 60 * 1000;
// key = modelName + ":" + providerId + ":" + dim + ":" + normalizedQuery
// value = float[] queryVector
private final Map<String, CacheEntry> cache = synchronizedLinkedHashMap(...);
```

**效果**:
- 同一次搜索的 hits + tree:第 2 次命中缓存(0 次 API)
- 5 分钟内重复搜索:0 次 API
- 缓存全用户共享(embedding 输出只取决于查询文本 + 模型,与 user 无关)

### 5.2 embedding 超时 60s → 8s + 自动降级(P1)

**问题**:Embedding API 挂了(如代理 502),用户干等 60 秒报错。

**解决**:
```java
// OpenAiCompatibleEmbeddingClient
.timeout(Duration.ofSeconds(8))  // 从 60s 降到 8s

// SemanticRetrievalService.embeddingContext
try {
    return new EmbeddingContext(client, cachedEmbed(query, client), dim, false);
} catch (Exception e) {
    log.warn("embed failed, degrading to keyword-only");
    return new EmbeddingContext(client, null, dim, true);  // degraded=true
}
// 调用方:if (context.degraded()) return List.of();  // 语义返回空,hybrid 退化纯 keyword
```

**效果**:API 故障时,8 秒降级返回 keyword 结果,**永不卡 60 秒**。

### 5.3 LibraryItem LIMIT(P3)

`SearchTreeService.libraryItems` 原本全表加载用户所有文件,加 `.last("limit 500")`。

### 5.4 前端默认 keyword 模式(P4)

搜索默认 `keyword`(本地 30ms),`hybrid`/`semantic` 标注"AI"让用户按需选。toast 显示实时延迟:`检索完成 23ms`。

## 6. RAG 问答的 SSE 流式

```
前端                          后端
  │
  │  GET /chat/rag/stream?question=...&notebookId=...
  ├──────────────────────────►│
  │                            │ retrieveHybridChunks
  │  ◄──── event: citations ──┤  (先发来源,前端边等边看)
  │                            │
  │                            │ completeStream(LLM)
  │  ◄──── event: delta ───────┤  (逐字)
  │  ◄──── event: delta ───────┤
  │      ...                   │
  │  ◄──── event: done ────────┤
  │                            │
```

**为什么不用 EventSource**:`EventSource` 不能自定义 header(带不了 JWT)。所以前端用 `fetch + ReadableStream`,手动解析 `event:`/`data:` 行。

**为什么先发 citations**:回答生成要几秒,但检索很快(几十毫秒)。先发来源,用户立刻看到"找到了 5 个相关片段",等待感大幅降低。

## 7. 知识库 scope 限定

RAG 默认全用户库召回。加了 `notebookId` 参数后,`retrieveHybridChunks` 只保留该知识库子树的 chunk:

```java
if (notebookId != null) {
    Set<Long> subtree = descendantNotebookIds(userId, notebookId);  // 含自身
    // 反查每个候选 chunk 的 noteId → noteId 的 notebookId,只在子树内保留
}
```

**注意**:chunk 表只有 noteId,要反查 note.notebookId 再过滤(不是直接拿 noteId 比子树 id)。

## 8. 可讲解的工程细节(面试亮点)

| 问题 | Atlas 的处理 |
|---|---|
| 长笔记向量化耗时 | `@Async` + 事务后 dispatch,用户不等 |
| 事务提交前异步读 chunks 会脏读 | `TransactionSynchronizationManager.afterCommit` |
| 文件落盘后事务回滚留孤儿 | `registerSynchronization` 回滚时 `Files.deleteIfExists` |
| Embedding API 挂了 | 8s 超时 + degraded 标志 + 自动退化 keyword |
| 同一查询重复 embed | LRU 缓存(200 条,5min TTL) |
| Qdrant 没装 | MySQL 内存余弦兜底(慢但可用) |
| EventSource 不能带 JWT | fetch + ReadableStream 手动解析 SSE |
| 换 embedding 模型维度变了 | Qdrant 按维度分 collection,新旧不冲突 |
| 全表加载 OOM | `ReindexService` 游标分页(200/批),不再 selectList 全表 |
| SSE 连接泄漏 | `OpenAiCompatibleChatClient.completeStream` try-with-resources 关闭行流 |

## 9. 进一步可做的(见 ROADMAP.md)

- **Rerank**:加 cross-encoder 二次排序(质量↑但延迟↑)
- **查询改写/扩展**:HyDE / multi-query(先改写再检索,召回↑)
- **多轮对话**:目前单轮,可加 conversationId + history
- **引用精确到原文区间**:目前到 chunk,可加 offset 高亮

---

继续阅读:[DESIGN-SYSTEM.md](DESIGN-SYSTEM.md) · [ROADMAP.md](ROADMAP.md) · 返回 [ARCHITECTURE.md](ARCHITECTURE.md)
