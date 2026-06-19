# Atlas · 个人智能知识库

> 导入 · 整理 · 检索 · 问答 · 认知图谱 —— 一个把零散资料变成"会回答"的知识库的全栈应用。

Atlas 是一个面向个人知识管理的全栈系统:你把 PDF / Word / Markdown / 网页 / 文本丢进来,它自动解析、归类、向量化,然后你可以**关键词极速检索**、**AI 语义问答**(带来源引用)、**一键生成知识库认知地图**(DeepWiki),并把 AI 整理的双轨笔记同步到长期记忆系统(VCP)。

```
┌─────────────────────────────────────────────────────────┐
│  atlas-frontend (Vue 3 + Vite + Pinia + KaTeX)          │
│  5 套主题 · 自研 UI Kit · RAG 流式问答 · SVG 艺术层      │
└──────────────────────────┬──────────────────────────────┘
                           │ REST + SSE (JWT 鉴权)
┌──────────────────────────▼──────────────────────────────┐
│  atlas-backend (Spring Boot 3.5 + MyBatis-Plus + H2/MySQL)│
│  15 个业务域 · Hybrid RAG · Qdrant 双轨向量 · MinerU 解析 │
└──────────────────────────┬──────────────────────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
   MySQL / H2          Qdrant 向量库      MinerU / LLM API
   (16 张表)          (语义检索双轨)     (PDF→MD / Chat / Embedding)
```

## ✨ 核心能力

| 能力 | 说明 |
|---|---|
| **多格式导入** | PDF / Word / Markdown / HTML / TXT,经 MinerU 转 Markdown,自动提取正文进搜索索引 |
| **AI 自动整理** | 文件夹批量导入时,LLM 按语义规划知识库树(领域/项目/资料库三级),自动打标签、生成笔记 |
| **三模式检索** | 关键词(极速,本地全文)/ 语义(纯向量)/ 综合(关键词+向量加权融合) |
| **RAG 问答** | 召回相关片段 → 拼 prompt → LLM 生成带引用的回答,SSE 流式输出,支持限定知识库 scope |
| **DeepWiki** | 一键生成知识库"认知层":全景总览 + Mermaid 认知地图(节点可点击跳转笔记)+ 知识结构 + 阅读路径 + 专题导航 |
| **笔记版本历史** | 每次编辑自动存档,任意回滚 |
| **VCP 同步** | AI 整理的"双轨笔记"(人类阅读轨 + AI 记忆轨)同步到 VCP 日记本系统 |
| **资产管家** | 像文件管理器一样管理所有原文件,按知识库/类型/关键词筛选,批量导出 |
| **投递箱** | 外部项目(如 AI 生图)整包资料投递,AI 规划入库 |
| **回收站** | 30 天软删除保留,可恢复 |

## 🏗 技术栈

**后端** · Spring Boot 3.5 · Java 17 · MyBatis-Plus · Flyway · Spring Security + JWT · Qdrant(向量库,双轨)· MinerU(PDF/Word 解析)· OpenAI 兼容协议(任意 LLM/Embedding 提供商)

**前端** · Vue 3 · TypeScript · Vite · Pinia · KaTeX(数学公式)· 自研 UI Kit(11 组件)· 5 套主题设计系统

## 📚 文档导航

这是**为学习和简历展示准备的项目文档**,按需阅读:

| 文档 | 内容 | 适合 |
|---|---|---|
| [**docs/ARCHITECTURE.md**](docs/ARCHITECTURE.md) | 整体架构、技术选型理由、数据流、关键设计决策 | **面试讲解 + 理解全局** |
| [**docs/BACKEND.md**](docs/BACKEND.md) | 后端 15 个业务域逐个说明:职责、关键类、API、实现要点 | **后端学习 + 改后端** |
| [**docs/FRONTEND.md**](docs/FRONTEND.md) | 前端架构、Pinia store 设计、UI Kit、主题系统、RAG 流式 | **前端学习 + 改前端** |
| [**docs/RAG.md**](docs/RAG.md) | Hybrid 检索、向量化、查询缓存、降级策略的深度剖析 | **RAG 原理 + 性能优化** |
| [**docs/DESIGN-SYSTEM.md**](docs/DESIGN-SYSTEM.md) | 5 套主题、墨青石板调色、夜曲浪漫主义艺术层的设计哲学 | **设计思考 + 主题定制** |
| [**docs/ROADMAP.md**](docs/ROADMAP.md) | 已知技术债 + 后续演进方向 | **诚实自评 + 持续改进** |

## 🚀 快速开始

```bash
# 1. 配置(复制环境变量模板,填你的 API Key)
cp config.env.example config.env
#   编辑 config.env:
#     ATLAS_JWT_SECRET=...        (JWT 签名密钥,≥32 字节)
#     MINERU_API_TOKEN=...        (MinerU PDF 解析 token,mineru.net 注册)
#     ATLAS_OPENAI_API_KEY=...    (或任意 OpenAI 兼容网关的 key)

# 2. 启动后端(默认 H2 本地库,零外部依赖即可跑)
cd atlas-backend && ./mvnw spring-boot:run

# 3. 启动前端
cd atlas-frontend && npm install && npm run dev

# 4. 打开 http://localhost:5173,用"演示用户 / atlas123456"登录
```

详见 [docs/ARCHITECTURE.md#快速开始](docs/ARCHITECTURE.md)。

## 🎯 这个项目能展示什么(简历视角)

- **全栈工程能力**:从数据库设计、REST+SSE API、JWT 鉴权,到 Vue 前端、主题系统、流式渲染,一个人贯通
- **RAG 系统设计**:不是调 API,是自己实现了 embedding 缓存、hybrid 检索融合、向量库双轨(MySQL 内存余弦 + Qdrant)、故障降级
- **工程化纪律**:Flyway 迁移、事务边界、事务后异步 dispatch、文件落盘回滚、分页避免 OOM
- **产品 + 设计**:5 套有设计哲学的主题(含浪漫主义 SVG 艺术层)、自研 UI Kit、KaTeX 数学公式、可交互的认知地图
- **可讲解性**:每个模块都有文档,能说清"为什么这么设计"

## 📄 License

MIT

---
> **Warning**: This is the public read-only mirror of Atlas.  
> The private repository contains the full history with development configurations.
> Sensitive credentials, API keys, and personal configuration files have been replaced with placeholders in this branch.
> See `config.env.example` for the configuration template.
---
> **Warning**: This is the public read-only mirror of Atlas.  
> The private repository at  contains the full history with development configurations.
> Sensitive credentials, API keys, and personal configuration files have been replaced with placeholders in this branch.
> See  for the configuration template.
