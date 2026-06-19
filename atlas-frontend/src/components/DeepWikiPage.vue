<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { ASelect, AiTracePanel } from './ui'

const props = defineProps<{
  currentNotebook: any
  notebookOptions: Array<{ value: string | number; label: string }>
  currentNotebookId: number | null
  mode: 'home' | 'topic' | 'map'
  focus: string
  result: any | null
  stats: { notes: number; files: number }
  agents: any[]
  selectedAgentId: number | ''
  loading: boolean
  asking: boolean
  renderedMarkdown: string
  suggestedQuestions: string[]
  sourceLinks: Array<{ kind: 'note' | 'library'; id: number; label: string }>
  question: string
  answer: string
  citations: any[]
}>()

const emit = defineEmits<{
  'update:mode': [value: 'home' | 'topic' | 'map']
  'update:focus': [value: string]
  'update:selectedAgentId': [value: number | '']
  'update:question': [value: string]
  selectNotebook: [value: string | number]
  generate: []
  generateTopic: [focus: string]
  ask: []
  askSuggestion: [value: string]
  loadLatest: []
  linkClick: [payload: { href: string; label: string }]
}>()

const readerHost = ref<HTMLElement | null>(null)
const topicEntries = ref<Array<string>>([])

watch(() => props.renderedMarkdown, () => {
  void nextTick(() => {
    renderMermaidBlocks()
    extractTopicEntries()
  })
}, { flush: 'post', immediate: true })

watch(() => props.result?.markdown, () => {
  void nextTick(extractTopicEntries)
}, { flush: 'post' })

// 从 home 页「## 三、知识结构」解析一级主题作为专题入口
function extractTopicEntries() {
  if (props.mode !== 'home' || !props.result?.markdown) {
    topicEntries.value = []
    return
  }
  const md = props.result.markdown
  const sectionStart = md.search(/##\s*三、?知识结构/)
  if (sectionStart < 0) {
    topicEntries.value = []
    return
  }
  const rest = md.slice(sectionStart)
  const nextSection = rest.slice(1).search(/\n##\s/)
  const section = nextSection < 0 ? rest : rest.slice(0, nextSection + 1)
  const topics: string[] = []
  for (const line of section.split('\n')) {
    // 一级列表项:- 主题名 或 - **主题名**：说明
    const m = line.match(/^\s*-\s+\*{0,2}([^\*：:（(【\[【]+?)\*{0,2}\s*[：:（(【\[]?/)
    if (m && m[1] && m[1].trim().length <= 24) {
      const t = m[1].trim()
      if (t && !topics.includes(t)) topics.push(t)
    }
  }
  topicEntries.value = topics.slice(0, 8)
}

function renderMermaidBlocks() {
  const host = readerHost.value
  if (!host) return
  const blocks = Array.from(host.querySelectorAll('pre.mermaid'))
  blocks.forEach((block) => {
    if (block.getAttribute('data-rendered') === '1') return
    const source = block.textContent || ''
    const graph = parseSimpleMermaid(source)
    if (!graph) return
    const wrapper = document.createElement('div')
    wrapper.className = 'deepwiki-mermaid-rendered'
    const clickableCount = Array.from(graph.nodeUrl.keys()).length
    wrapper.innerHTML = `
      <div class="deepwiki-mermaid-head">
        <strong>${escapeLocal(graph.title)}</strong>
        <span>${graph.nodes.length} 个节点 / ${graph.edges.length} 条关系${clickableCount ? ` · ${clickableCount} 个可点击` : ''}</span>
      </div>
      <div class="deepwiki-mermaid-legend">${clickableCount ? '<span class="legend-dot"></span>带圆点的节点可点击跳转到来源笔记' : ''}</div>
      <div class="deepwiki-mermaid-levels">
        ${graph.levels.map((level) => `
          <div class="deepwiki-mermaid-level">
            ${level.map((id) => {
              const label = graph.labels.get(id) || id
              const url = graph.nodeUrl.get(id) || ''
              const clickable = url ? ' is-clickable' : ''
              return `<button type="button" class="deepwiki-mermaid-node${clickable}" data-wiki-href="${escapeLocal(url)}"><span class="deepwiki-mermaid-node__label">${escapeLocal(label)}</span>${url ? '<span class="deepwiki-mermaid-node__dot" aria-hidden="true"></span>' : ''}</button>`
            }).join('')}
          </div>
        `).join('')}
      </div>
      <details>
        <summary>查看 Mermaid 源码</summary>
        <pre>${escapeLocal(source)}</pre>
      </details>
    `
    wrapper.querySelectorAll('.deepwiki-mermaid-node[data-wiki-href]').forEach((node) => {
      const href = (node as HTMLElement).getAttribute('data-wiki-href') || ''
      if (!href) return
      node.addEventListener('click', (ev: Event) => {
        ev.preventDefault()
        ev.stopPropagation()
        const label = (node.querySelector('.deepwiki-mermaid-node__label') as HTMLElement)?.textContent?.trim() || href
        emit('linkClick', { href, label })
      })
    })
    block.setAttribute('data-rendered', '1')
    block.replaceWith(wrapper)
  })
}

function parseSimpleMermaid(source: string) {
  const trimmed = source.trim()
  if (!/^graph\s+(TD|TB|LR)\b/i.test(trimmed)) return null
  const labels = new Map<string, string>()
  const edges: Array<{ from: string; to: string }> = []
  const nodeUrl = new Map<string, string>()
  // 解析边:A[标签] --> B[标签]
  const edgePattern = /^\s*([A-Za-z0-9_]+)(?:\[(.*?)])?\s*-->\s*([A-Za-z0-9_]+)(?:\[(.*?)])?/gm
  let match: RegExpExecArray | null
  while ((match = edgePattern.exec(trimmed)) !== null) {
    labels.set(match[1], (match[2] || labels.get(match[1]) || match[1]).trim())
    labels.set(match[3], (match[4] || labels.get(match[3]) || match[3]).trim())
    edges.push({ from: match[1], to: match[3] })
  }
  // 解析独立节点定义:A[标签] (无边)
  const nodePattern = /^\s*([A-Za-z0-9_]+)\[([^\]]*)]/gm
  while ((match = nodePattern.exec(trimmed)) !== null) {
    if (!labels.has(match[1])) labels.set(match[1], match[2].trim())
  }
  // 解析 click 语句:click B href "atlas://note/123" 或 click B "atlas://note/123" 或 click B callback
  const clickPattern = /^\s*click\s+([A-Za-z0-9_]+)\s+(?:href\s+)?["']?([^"'\s]+)["']?/gmi
  while ((match = clickPattern.exec(trimmed)) !== null) {
    const url = match[2]
    if (url && url.startsWith('atlas://')) {
      nodeUrl.set(match[1], url)
    }
  }
  if (!edges.length && labels.size === 0) return null
  const nodes = Array.from(labels.keys())
  if (!nodes.length) return null
  const incoming = new Map(nodes.map((id) => [id, 0]))
  edges.forEach((edge) => incoming.set(edge.to, (incoming.get(edge.to) || 0) + 1))
  const roots = nodes.filter((id) => (incoming.get(id) || 0) === 0)
  const levels = new Map<string, number>()
  const queue = (roots.length ? roots : [nodes[0]]).map((id) => ({ id, level: 0 }))
  while (queue.length) {
    const current = queue.shift()!
    if ((levels.get(current.id) ?? -1) >= current.level) continue
    levels.set(current.id, current.level)
    edges.filter((edge) => edge.from === current.id).forEach((edge) => queue.push({ id: edge.to, level: current.level + 1 }))
  }
  nodes.forEach((id) => {
    if (!levels.has(id)) levels.set(id, 0)
  })
  const grouped: string[][] = []
  nodes.forEach((id) => {
    const level = levels.get(id) || 0
    if (!grouped[level]) grouped[level] = []
    grouped[level].push(id)
  })
  return {
    title: labels.get(roots[0] || nodes[0]) || '知识地图',
    labels,
    edges,
    nodes,
    nodeUrl,
    levels: grouped.filter(Boolean),
  }
}

function escapeLocal(text: string) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function handleReaderClick(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  const anchor = target?.closest?.('a')
  if (!anchor) return
  const href = anchor.getAttribute('href') || ''
  if (!href) return
  event.preventDefault()
  event.stopPropagation()

  if (href.startsWith('#')) {
    const id = decodeURIComponent(href.slice(1))
    const targetEl = anchor.closest('.deepwiki-doc')?.querySelector(`#${CSS.escape(id)}`)
    targetEl?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    return
  }

  emit('linkClick', {
    href,
    label: anchor.textContent?.trim() || href,
  })
}
</script>

<template>
  <section class="deepwiki-page">
    <section class="deepwiki-shell">
      <aside class="deepwiki-nav">
        <div class="deepwiki-brand">
          <strong>DeepWiki</strong>
          <span>中文知识库认知层</span>
        </div>

        <div class="deepwiki-notebook-select">
          <label>目标知识库</label>
          <ASelect
            :model-value="currentNotebookId"
            :options="notebookOptions"
            placeholder="选择要生成 DeepWiki 的知识库"
            block
            @update:model-value="(v: string | number) => emit('selectNotebook', v)"
          />
        </div>

        <button :class="{ active: mode === 'home' }" @click="emit('update:mode', 'home')">
          <strong>总览页</strong>
          <span>先给全景和阅读路径</span>
        </button>
        <button :class="{ active: mode === 'map' }" @click="emit('update:mode', 'map')">
          <strong>知识地图</strong>
          <span>结构、概念、模块关系</span>
        </button>
        <button :class="{ active: mode === 'topic' }" @click="emit('update:mode', 'topic')">
          <strong>专题页</strong>
          <span>聚焦一个具体问题</span>
        </button>

        <div class="deepwiki-nav-meta">
          <span>笔记</span><strong>{{ stats.notes }}</strong>
          <span>资料</span><strong>{{ stats.files }}</strong>
          <span>本页来源</span><strong>{{ result?.sourceCount || 0 }}</strong>
        </div>

        <div class="deepwiki-principles">
          <span>生成原则</span>
          <p>全景优先、路径清楚、回答可追溯。</p>
        </div>
      </aside>

      <main class="deepwiki-doc">
        <header class="deepwiki-docbar">
          <div>
            <span>{{ result?.updatedAt ? `已保存 · ${result.updatedAt}` : '未生成保存页' }}</span>
            <h1>{{ result?.title || currentNotebook?.name || 'DeepWiki' }}</h1>
            <span v-if="result?.stale" class="deepwiki-stale-badge">
              <span class="deepwiki-stale-badge__dot" />
              内容已更新,建议重新生成
            </span>
          </div>
          <div class="deepwiki-controls">
            <select :value="selectedAgentId" title="选择 DeepWiki Agent" @change="emit('update:selectedAgentId', Number(($event.target as HTMLSelectElement).value) || '')">
              <option value="">DeepWiki Agent</option>
              <option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agent.name }}</option>
            </select>
            <input
              v-if="mode === 'topic'"
              :value="focus"
              placeholder="专题标题，例如：RAG 检索流程"
              @input="emit('update:focus', ($event.target as HTMLInputElement).value)"
            />
            <button class="primary" :disabled="loading || !currentNotebook" @click="emit('generate')">
              {{ loading ? '生成中' : result?.stale ? '更新内容' : result ? '重新生成' : '生成 DeepWiki' }}
            </button>
          </div>
        </header>

        <nav v-if="mode === 'home' && topicEntries.length" class="deepwiki-topics">
          <div class="deepwiki-topics__head">
            <strong>专题入口</strong>
            <span>点击进入对应专题页</span>
          </div>
          <div class="deepwiki-topics__grid">
            <button
              v-for="topic in topicEntries"
              :key="topic"
              class="deepwiki-topic-card"
              :disabled="loading"
              @click="emit('generateTopic', topic)"
            >
              <span class="deepwiki-topic-card__title">{{ topic }}</span>
              <span class="deepwiki-topic-card__arrow" aria-hidden="true">→</span>
            </button>
          </div>
        </nav>

        <article v-if="result" ref="readerHost" class="deepwiki-reader note-reader" v-html="renderedMarkdown" @click="handleReaderClick"></article>
        <AiTracePanel v-if="result?.aiTrace?.length" :trace="result.aiTrace" />
        <section v-else class="deepwiki-empty">
          <strong>还没有保存的 Wiki 页面</strong>
          <p>点击生成后，Atlas 会把当前知识库整理成总览、地图、阅读路径、追问建议和来源索引。页面会保存下来，下次打开不会自动刷新。</p>
          <button class="primary" :disabled="loading || !currentNotebook" @click="emit('generate')">
            {{ loading ? '生成中' : '生成首页' }}
          </button>
        </section>
      </main>

      <aside class="deepwiki-toc">
        <section class="deepwiki-ask">
          <strong>继续追问</strong>
          <p>基于当前知识库做 RAG 追问，回答会带召回片段。</p>
          <textarea
            :value="question"
            placeholder="例如：这个知识库最核心的阅读路径是什么？"
            @input="emit('update:question', ($event.target as HTMLTextAreaElement).value)"
          ></textarea>
          <button class="primary wide" :disabled="asking || !question.trim()" @click="emit('ask')">
            {{ asking ? '追问中' : '追问 Atlas' }}
          </button>
        </section>

        <section v-if="suggestedQuestions.length" class="deepwiki-suggestions">
          <strong>推荐问题</strong>
          <button v-for="item in suggestedQuestions" :key="item" class="ghost" @click="emit('askSuggestion', item)">
            {{ item }}
          </button>
        </section>

        <section v-if="answer" class="deepwiki-answer">
          <strong>回答</strong>
          <p>{{ answer }}</p>
          <div v-if="citations.length" class="deepwiki-citations">
            <span>召回依据</span>
            <button
              v-for="citation in citations.slice(0, 5)"
              :key="`${citation.noteId}-${citation.chunkIndex}`"
              class="text-button"
              @click="emit('linkClick', { href: `atlas://note/${citation.noteId}`, label: `Note #${citation.noteId}` })"
            >
              Note #{{ citation.noteId }} · {{ Number(citation.score || 0).toFixed(2) }}
            </button>
          </div>
        </section>

        <section class="deepwiki-sources">
          <strong>来源索引</strong>
          <p v-if="!sourceLinks.length">生成页里还没有检测到来源链接。</p>
          <button
            v-for="source in sourceLinks"
            :key="`${source.kind}-${source.id}`"
            class="ghost source-chip"
            @click="emit('linkClick', { href: `atlas://${source.kind}/${source.id}`, label: source.label })"
          >
            {{ source.label }}
          </button>
        </section>

        <section class="deepwiki-page-plan">
          <strong>页面规划</strong>
          <div>
            <span>当前模式</span>
            <em>{{ mode === 'home' ? '总览页' : mode === 'map' ? '知识地图' : '专题页' }}</em>
          </div>
          <div v-if="mode === 'topic'">
            <span>专题</span>
            <em>{{ focus || '未填写' }}</em>
          </div>
          <button class="ghost" :disabled="!currentNotebook" @click="emit('loadLatest')">读取保存页</button>
        </section>
      </aside>
    </section>
  </section>
</template>
