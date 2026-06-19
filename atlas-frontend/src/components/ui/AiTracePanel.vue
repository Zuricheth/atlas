<script setup lang="ts">
/**
 * AiTracePanel — 展示一次操作涉及的 AI 调用透明化信息
 *
 * 用途:投递箱规划/RAG问答/导入等任何 AI 调用,都在响应里带 aiTrace 数组。
 * 把这个组件放在结果旁边,用户能看到:
 *   - 这一步用了哪个 agent + 哪个模型 + 哪个渠道
 *   - 耗时多久,输入/输出大概多少字
 *   - 失败了具体什么原因
 *
 * 设计目标:让 AI 调用从黑箱变透明,可解释、可排查。
 */
import { computed } from 'vue'

type AiCall = {
  scene: string
  channel: string
  agentId?: number | null
  agentName?: string | null
  model: string
  providerId?: number | null
  providerName?: string | null
  durationMs: number
  success: boolean
  error?: string | null
  inputChars: number
  outputChars: number
}

const props = defineProps<{
  trace: AiCall[]
  /** 默认展开还是折叠 */
  defaultOpen?: boolean
  /** 紧凑模式(内嵌在小卡片旁) */
  compact?: boolean
}>()

const totalDuration = computed(() => props.trace.reduce((s, c) => s + c.durationMs, 0))
const successCount = computed(() => props.trace.filter((c) => c.success).length)
const failedCount = computed(() => props.trace.length - successCount.value)

const sceneLabel = (scene: string) => {
  const map: Record<string, string> = {
    'rag': 'RAG 问答',
    'inbox-plan': '投递箱规划',
    'deepwiki': 'DeepWiki 生成',
    'note-agent': 'AI 写笔记',
    'note-agent-stream': 'AI 写笔记(流)',
    'paper-ai': '论文入库',
    'library-auto': '资料自动整理',
    'library-folder-plan': '文件夹规划',
    'agent': 'Agent 通用',
    'agent-override': 'Agent(系统提示覆盖)',
    'agent-stream': 'Agent 流式',
  }
  return map[scene] || scene
}

const channelLabel = (channel: string) => {
  if (channel === 'chat') return '对话'
  if (channel === 'embedding') return '向量化'
  return channel
}

function formatMs(ms: number) {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

function formatChars(chars: number) {
  if (chars < 1000) return `${chars} 字`
  return `${(chars / 1000).toFixed(1)}k 字`
}
</script>

<template>
  <details v-if="trace.length" class="ai-trace" :open="defaultOpen ?? !compact">
    <summary class="ai-trace__head">
      <span class="ai-trace__icon" aria-hidden="true">⚙</span>
      <span class="ai-trace__title">AI 调用</span>
      <span class="ai-trace__count">{{ trace.length }}</span>
      <span class="ai-trace__sep">·</span>
      <span class="ai-trace__duration">{{ formatMs(totalDuration) }}</span>
      <span v-if="failedCount" class="ai-trace__failed">{{ failedCount }} 失败</span>
    </summary>
    <ul class="ai-trace__list">
      <li v-for="(call, idx) in trace" :key="idx" :class="['ai-trace__item', { 'is-failed': !call.success }]">
        <div class="ai-trace__row">
          <span class="ai-trace__scene">{{ sceneLabel(call.scene) }}</span>
          <span class="ai-trace__channel">{{ channelLabel(call.channel) }}</span>
          <span class="ai-trace__model">{{ call.model || '—' }}</span>
          <span class="ai-trace__time">{{ formatMs(call.durationMs) }}</span>
        </div>
        <div class="ai-trace__meta">
          <template v-if="call.agentName">
            <span class="ai-trace__chip ai-trace__chip--agent" :title="`Agent #${call.agentId}`">
              Agent · {{ call.agentName }}
            </span>
          </template>
          <template v-else>
            <span class="ai-trace__chip ai-trace__chip--direct" title="未走 Agent,直接用当前激活模型">
              直连激活模型
            </span>
          </template>
          <span class="ai-trace__chip ai-trace__chip--io">
            ↑ {{ formatChars(call.inputChars) }}
            ↓ {{ formatChars(call.outputChars) }}
          </span>
          <span v-if="call.providerName" class="ai-trace__chip ai-trace__chip--provider">
            via {{ call.providerName }}
          </span>
        </div>
        <p v-if="!call.success && call.error" class="ai-trace__error">
          失败原因:{{ call.error }}
        </p>
      </li>
    </ul>
  </details>
</template>

<style scoped>
.ai-trace {
  margin-top: 12px;
  border: 1px solid var(--border-soft);
  border-radius: var(--r-md);
  background: var(--panel-soft);
  overflow: hidden;
}

.ai-trace__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  cursor: pointer;
  user-select: none;
  font-size: var(--fs-sm);
  list-style: none;
}
.ai-trace__head::-webkit-details-marker { display: none; }
.ai-trace__head::before {
  content: "▸";
  display: inline-block;
  color: var(--muted);
  font-size: 10px;
  transition: transform var(--t-fast) var(--ease-out);
}
.ai-trace[open] .ai-trace__head::before { transform: rotate(90deg); }

.ai-trace__icon {
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--primary-soft);
  color: var(--primary-strong);
  font-size: 11px;
}
.ai-trace__title { color: var(--text-strong); font-weight: var(--fw-semibold); }
.ai-trace__count {
  background: var(--panel);
  color: var(--muted);
  padding: 1px 8px;
  border-radius: var(--r-pill);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
}
.ai-trace__sep { color: var(--muted-soft); }
.ai-trace__duration {
  color: var(--muted);
  font-size: var(--fs-xs);
  font-feature-settings: "tnum";
}
.ai-trace__failed {
  margin-left: auto;
  color: var(--danger);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
}

.ai-trace__list {
  list-style: none;
  margin: 0;
  padding: 0;
  border-top: 1px solid var(--border-soft);
  background: var(--panel);
}
.ai-trace__item {
  padding: 10px 14px;
  border-bottom: 1px solid var(--border-soft);
}
.ai-trace__item:last-child { border-bottom: 0; }
.ai-trace__item.is-failed { background: var(--danger-soft); }

.ai-trace__row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  font-size: var(--fs-sm);
}
.ai-trace__scene {
  font-weight: var(--fw-semibold);
  color: var(--text-strong);
}
.ai-trace__channel {
  color: var(--muted);
  font-size: var(--fs-xs);
  padding: 1px 8px;
  background: var(--panel-strong);
  border-radius: var(--r-xs);
}
.ai-trace__model {
  color: var(--accent-clay);
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: var(--fs-xs);
}
.ai-trace__time {
  margin-left: auto;
  color: var(--muted);
  font-size: var(--fs-xs);
  font-feature-settings: "tnum";
}

.ai-trace__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
.ai-trace__chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: var(--r-pill);
  font-size: var(--fs-xs);
  background: var(--panel-strong);
  color: var(--muted);
  border: 1px solid var(--border-soft);
}
.ai-trace__chip--agent { background: var(--primary-soft); color: var(--primary-strong); border-color: transparent; }
.ai-trace__chip--direct { background: var(--accent-sage-soft); color: var(--accent-sage); border-color: transparent; }
.ai-trace__chip--io { font-feature-settings: "tnum"; }
.ai-trace__chip--provider { font-style: italic; }

.ai-trace__error {
  margin: 8px 0 0;
  padding: 8px 10px;
  background: var(--danger-soft);
  color: var(--danger);
  border-radius: var(--r-xs);
  font-size: var(--fs-xs);
  line-height: 1.5;
  word-break: break-word;
}
</style>
