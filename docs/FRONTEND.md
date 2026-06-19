# Atlas 前端架构

> 讲清前端怎么组织、状态怎么管、UI Kit/主题怎么设计、RAG 怎么流式渲染。

## 1. 技术栈与组织

```
Vue 3 (Composition API) + TypeScript + Vite
状态:Pinia (setup store)
渲染:原生 fetch + ReadableStream (SSE 流式)
样式:设计 token (CSS 变量) + scoped styles + 5 套主题
数学:KaTeX
```

```
src/
├── App.vue             主应用(目前仍较大,~4500 行,正在增量拆分)
├── main.ts             入口:注册 Pinia、绑定 apiClient、initTheme
├── types/index.ts      全局共享类型(从 App.vue 抽出)
├── lib/
│   ├── api.ts          API 客户端工厂 + apiUrl 工具
│   ├── apiClient.ts    模块级 API 单例(request/requestRaw,token+401 全局)
│   ├── markdown.ts     Markdown→HTML 渲染(含 KaTeX 数学公式提取)
│   ├── noteBlocks.ts   双轨笔记协议解析
│   └── theme.ts        主题系统(5 套主题 × 浅深模式)
├── stores/
│   ├── auth.ts         认证(token/登录/登出 + onLogout 钩子)
│   ├── ui.ts           全局 UI(toast/appView/loading/弹窗开关)
│   ├── notebook.ts     知识库树(currentNotebookId/树构建/CRUD)
│   └── trash.ts        回收站(完整 CRUD)
├── components/
│   ├── ui/             自研 UI Kit(11 组件)
│   ├── art/            主题艺术层(Nocturne SVG)
│   ├── NotebookTree.vue / FileTreeView.vue / NoteToolbar.vue ...
├── design/
│   ├── tokens.css      设计 token(颜色/圆角/阴影/缓动/间距/字号/层级)
│   └── themes.css      5 套主题 + 深色模式
└── styles/
    └── prose.css       .prose 阅读排版系统
```

## 2. 状态管理:Pinia store 设计

### 为什么用 setup store 而非 options store
setup store 写法用 Composition API,类型推导好、能直接用 computed/watch、灵活。Pinia 3 推荐。

### 迁移模式:storeToRefs 别名(关键技巧)

App.vue 原本 4600 行,直接重写风险高。迁移时用**别名模式**:
```ts
const notebookStore = useNotebookStore()
const { currentNotebookId, notebooks } = storeToRefs(notebookStore)
// 代码体里 currentNotebookId.value / notebooks.value 不用改
```
这样**只改声明,不改代码体**,每抽一个域都 build 验证,零回归。

### 已抽的 store

| store | 职责 | 模式要点 |
|---|---|---|
| `auth` | token/登录/登出 | `onLogout(hook)` 注册钩子,业务 store 自清理,避免反向依赖 |
| `ui` | toast/appView/loading/弹窗 | loading 用 `reactive`(非 ref),避免 storeToRefs 包成 Ref |
| `notebook` | currentNotebookId/树/CRUD | `select()` 接受 `onSelected` 回调,notebook 不依赖 note/library 域 |
| `trash` | 回收站 CRUD | 完整端到端,是后续业务 store 的样板 |

### 还在 App.vue 的域(增量迁移中)
note / search / asset / inbox / deepwiki / vcp 的状态还在 App.vue。遵循 trash 样板,每次改某域时顺手抽进 store。

## 3. 自研 UI Kit(`components/ui/`)

11 个零依赖组件,统一设计语言:

| 组件 | 职责 |
|---|---|
| `AButton` | primary/ghost/danger/text/subtle 变体 + loading + scale(0.97) 按下 |
| `AInput` / `ASelect` | 表单,label/error/focus 柔光 ring |
| `ACard` | 卡片,default/glass/soft/outline + lift 悬浮 + glow 高光 |
| `AModal` | 弹窗,Teleport + Transition + ESC + 滚动锁,弹性进入 |
| `AToast` | 多通道 toast 队列,呼吸点 + 弹性进入 |
| `ASkeleton` | shimmer 占位(rows/宽高/圆形 props) |
| `AEmpty` | 空状态(图标+文案+CTA slot) |
| `ABadge` | 标签/状态徽章(neutral/primary/success/danger/warn/sage/clay) |
| `ASpinner` | 旋转加载 |
| `ATabs` | 标签页,active 下划线滑动(pill/underline 两变体) |
| `ThemePicker` | 主题选择器,色卡预览 + 浅深模式切换 |

**设计原则**:全部 scoped styles、走 design token(换主题自动适配)、触感反馈(scale/translate)、`prefers-reduced-motion` 降级、GPU-only 动画属性(transform/opacity)。

## 4. 主题系统(`design/tokens.css` + `themes.css`)

### 双维度:`data-theme` × `data-mode`
```html
<html data-theme="nocturne" data-mode="dark">
```
- `data-theme`:调色板(ink/parchment/obsidian/mist/nocturne)
- `data-mode`:浅深(light/dark/不设=跟随系统)

两个维度独立组合,5×3 种组合都能跑。

### 5 套主题(各有设计哲学)
| 主题 | 调色板 | 气质 |
|---|---|---|
| **墨青石板** ink | 暖纸 + 墨青 | 现代沉稳(默认) |
| **羊皮卷轴** parchment | 米黄 + 焦褐墨绿 | 古籍手账 |
| **黑曜石** obsidian | 暖黑 + 紫罗兰 | 矿物质感深色 |
| **晨雾** mist | 雾灰 + 钢蓝 | 北欧极简 |
| **夜曲** nocturne | 酒红 + 烛光金 + 老乐谱 | 浪漫主义(含 SVG 艺术层) |

详见 [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md)。

### token 分层
```
配色:--bg / --panel / --panel-soft / --text / --muted / --border (+ strong/soft 变体)
强调:--primary (+strong/soft/deep) / --accent-sage / --accent-clay
状态:--danger / --success / --warn (+ soft)
圆角:--r-xs/sm/md/lg/xl/pill
阴影:--shadow-xs/sm/lg/xl + --shadow-inset
缓动:--ease-out/in-out/spring + --t-fast/t/slow/slower
间距:--s-1~16 (4px 基)
字号:--fs-xs~hero
层级:--z-base~tooltip
```

## 5. Markdown 渲染 + KaTeX

`lib/markdown.ts` 自写轻量渲染器(不引 marked/marked-it,够用且可控):

```ts
markdownToHtml(md)
  → extractMath(md)        // 先抽 $...$/ $$...$$ 占位,避免被 escapeHtml 破坏
  → escapeHtml + 行级解析  // 标题/列表/引用/代码块/表格/分隔线
  → inlineMarkdown         // 链接/图片/加粗/行内代码
  → restoreMath            // 回填 KaTeX 渲染的 HTML
```

**KaTeX 集成要点**:LaTeX 里的 `<`/`>` 不能被 escapeHtml 转义(会破坏公式),所以先抽成占位 token,渲染完再回填 KaTeX 输出。失败兜底显示红框 + 原文 + tooltip。

`.prose` 排版系统(`styles/prose.css`):阅读区统一排版(标题层级/行高 1.78/代码块/引用块/表格/链接),所有渲染 markdown 的地方加 `.prose` class。

## 6. RAG 流式问答(前端)

`askRag` 用 fetch + ReadableStream(非 EventSource,因为要带 JWT):
```ts
const response = await fetch(apiUrl(`/chat/rag/stream?${params}`), {
  headers: { Authorization: `Bearer ${token}` }
})
const reader = response.body.getReader()
// 手动解析 SSE:按 \n\n 分事件,每事件取 event: 和 data:
while (true) {
  const { done, value } = await reader.read()
  buffer += decoder.decode(value, { stream: true })
  while ((sep = buffer.indexOf('\n\n')) >= 0) {
    // 解析 event: delta/citations/done/error
  }
}
```

delta 逐字累加渲染,citations 先到先显示来源,带 notebookId 限定知识库 scope。

## 7. 夜曲主题的艺术层(`components/art/`)

唯一带 SVG 装饰的主题(浪漫主义):

- `NocturneArt.vue`:Teleport 到 body 的四角装饰(G 谱号涡卷/巴洛克角花/小提琴 f 孔/飘动音符),仅 `data-theme="nocturne"` 显示
- `NocturneHero.vue`:登录页 hero 插画(三角钢琴 + 小提琴 + 摇曳烛台线描,全矢量)
- 背景:五线谱 SVG 纹理 + 烛光金光晕 + 纸质噪点
- 引用块:巴洛克涡卷 + 双线金边 + italic
- 标题:自动切衬线字体(Source Han Serif)

**技术要点**:`<Teleport>` 出去的元素 `<style scoped>` 不生效,要用全局 `<style>` + `:root[data-theme]` 限定。

## 8. 已知前端债务(见 ROADMAP.md)

- App.vue 仍 ~4500 行单体,note/search/asset/inbox/deepwiki/vcp 域待抽进 store
- 未引入 vue-router(用 appView 切换),刷新不保留视图状态
- 主题切换无过渡动画
- 无错误边界(组件抛错白屏)

---

继续阅读:[DESIGN-SYSTEM.md](DESIGN-SYSTEM.md) · [RAG.md](RAG.md) · 返回 [ARCHITECTURE.md](ARCHITECTURE.md)
