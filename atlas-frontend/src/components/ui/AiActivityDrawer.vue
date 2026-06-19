<script setup lang="ts">
/**
 * AiActivityDrawer — 全局 AI 活动面板
 *
 * 顶栏按钮触发,从右侧滑出抽屉,展示当前会话的所有 AI 调用历史。
 * 与 AiTracePanel 的区别:
 * - AiTracePanel 是某次操作内嵌的 trace 列表(局部)
 * - AiActivityDrawer 是会话级总账本(全局),跨所有请求累积
 */
import { onMounted, onUnmounted } from 'vue'
import { useAiActivityStore } from '../../stores/aiActivity'

const store = useAiActivityStore()

function close() {
  store.open = false
}

function onKey(ev: KeyboardEvent) {
  if (ev.key === 'Escape' && store.open) close()
}

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))

const sceneLabel = (scene: string) => {
  const map: Record<string, string> = {
    'rag': 'RAG 问答',
    'inbox-plan': '投递箱规划',
    'deepwiki': 'DeepWiki 生成',
    'note-agent': 'AI 写笔记',
    'note-agent-stream': 'AI 写笔记(流)',
    'paper-ai': '论文入库',
    'library-ai-note': '资料 AI 笔记',
    'library-auto-plan': '单文件规划',
    'library-folder-plan': '文件夹规划',
    'library-folder-polish': '文件夹精修',
    'library-folder-refine': '不确定文件复判',
    'agent': 'Agent 通用',
    'agent-override': 'Agent(系统提示覆盖)',
    'agent-stream': 'Agent 流式',
  }
  return map[scene] || scene
}

function formatMs(ms: number) {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

function formatChars(chars: number) {
  if (chars < 1000) return `${chars} 字`
  return `${(chars / 1000).toFixed(1)}k 字`
}

function formatTime(ts: number) {
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
</script>

<template>
  <Teleport to="body">
    <Transition name="ai-drawer-fade">
      <div v-if="store.open" class="ai-drawer-overlay" @mousedown.self="close" />
    </Transition>
    <Transition name="ai-drawer-slide">
      <aside v-if="store.open" class="ai-drawer" role="dialog" aria-label="AI 活动">
        <header class="ai-drawer__head">
          <div class="ai-drawer__title">
            <strong>AI 活动</strong>
            <span class="ai-drawer__subtitle">本次会话的所有 AI 调用</span>
          </div>
          <button class="ai-drawer__close" aria-label="关闭" @click="close">
            <svg viewBox="0 0 16 16" width="14" height="14"><path d="M3 3l10 10M13 3L3 13" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
          </button>
        </header>

        <section class="ai-drawer__stats">
          <div class="ai-drawer__stat">
            <span>总调用</span>
            <strong>{{ store.total }}</strong>
          </div>
          <div class="ai-drawer__stat">
            <span>累计耗时</span>
            <strong>{{ formatMs(store.totalDurationMs) }}</strong>
          </div>
          <div class="ai-drawer__stat" :class="{ 'has-pending': store.pendingCount > 0 }">
            <span>进行中</span>
            <strong>{{ store.pendingCount }}</strong>
          </div>
          <div class="ai-drawer__stat" :class="{ 'has-failed': store.failedCount > 0 }">
            <span>失败</span>
            <strong>{{ store.failedCount }}</strong>
          </div>
        </section>

        <div class="ai-drawer__actions">
          <button class="ghost small" :disabled="!store.total" @click="store.clear()">清空记录</button>
        </div>

        <div class="ai-drawer__body">
          <ul v-if="store.total" class="ai-drawer__list">
            <li
              v-for="call in store.reverseCalls"
              :key="call.id"
              :class="['ai-drawer__item', !call.success && 'is-failed', call.pending && 'is-pending']"
            >
              <div class="ai-drawer__row">
                <span class="ai-drawer__time">{{ formatTime(call.recordedAt) }}</span>
                <span class="ai-drawer__scene">{{ sceneLabel(call.scene) }}</span>
                <span class="ai-drawer__model">{{ call.model || '—' }}</span>
                <span v-if="call.pending" class="ai-drawer__badge ai-drawer__badge--pending">
                  进行中
                </span>
                <span v-else-if="!call.success" class="ai-drawer__badge ai-drawer__badge--failed">
                  失败
                </span>
                <span v-else class="ai-drawer__duration">{{ formatMs(call.durationMs) }}</span>
              </div>
              <div class="ai-drawer__meta">
                <span v-if="call.agentName" class="ai-drawer__chip ai-drawer__chip--agent">
                  Agent · {{ call.agentName }}
                </span>
                <span v-else class="ai-drawer__chip ai-drawer__chip--direct">
                  直连激活模型
                </span>
                <span class="ai-drawer__chip">{{ call.channel === 'embedding' ? '向量化' : '对话' }}</span>
                <span class="ai-drawer__chip ai-drawer__chip--io">
                  ↑ {{ formatChars(call.inputChars) }}
                  <template v-if="!call.pending">↓ {{ formatChars(call.outputChars) }}</template>
                </span>
              </div>
              <p v-if="!call.success && call.error" class="ai-drawer__error">{{ call.error }}</p>
            </li>
          </ul>
          <div v-else class="ai-drawer__empty">
            <strong>还没有 AI 调用</strong>
            <p>试试问知识库一个问题,或让 Agent 整理笔记 — 这里会实时显示每一次调用。</p>
          </div>
        </div>
      </aside>
    </Transition>
  </Teleport>
</template>

<style scoped>
.ai-drawer-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
  background: rgba(20, 18, 16, 0.42);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

.ai-drawer {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(440px, 92vw);
  z-index: calc(var(--z-overlay) + 1);
  background: var(--panel);
  border-left: 1px solid var(--border-strong);
  box-shadow: var(--shadow-xl);
  display: grid;
  grid-template-rows: auto auto auto 1fr;
  overflow: hidden;
}

.ai-drawer__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--border-soft);
}
.ai-drawer__title strong {
  display: block;
  font-size: var(--fs-lg);
  color: var(--text-strong);
  font-weight: var(--fw-semibold);
}
.ai-drawer__subtitle {
  display: block;
  font-size: var(--fs-xs);
  color: var(--muted);
  margin-top: 2px;
}

.ai-drawer__close {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  border-radius: var(--r-xs);
  background: transparent;
  color: var(--muted);
  border: 0;
  cursor: pointer;
  transition: background var(--t-fast) var(--ease-out), color var(--t-fast) var(--ease-out);
}
.ai-drawer__close:hover { background: var(--panel-strong); color: var(--text); }

.ai-drawer__stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  padding: 12px 20px;
  background: var(--panel-soft);
  border-bottom: 1px solid var(--border-soft);
}
.ai-drawer__stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  text-align: center;
  padding: 8px 4px;
  background: var(--panel);
  border-radius: var(--r-sm);
  border: 1px solid var(--border-soft);
}
.ai-drawer__stat span {
  font-size: var(--fs-xs);
  color: var(--muted);
  letter-spacing: 0.04em;
}
.ai-drawer__stat strong {
  font-size: var(--fs-md);
  color: var(--text-strong);
  font-weight: var(--fw-semibold);
  font-feature-settings: "tnum";
}
.ai-drawer__stat.has-pending strong { color: var(--primary); }
.ai-drawer__stat.has-pending strong::after {
  content: "";
  display: inline-block;
  width: 5px; height: 5px;
  border-radius: 50%;
  margin-left: 4px;
  background: var(--primary);
  vertical-align: 2px;
  animation: a-breathe 1.6s var(--ease-out) infinite;
}
.ai-drawer__stat.has-failed strong { color: var(--danger); }

.ai-drawer__actions {
  padding: 8px 20px;
  border-bottom: 1px solid var(--border-soft);
  display: flex;
  justify-content: flex-end;
}
.ai-drawer__actions button {
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--r-xs);
  padding: 4px 10px;
  font-size: var(--fs-xs);
  color: var(--muted);
  cursor: pointer;
}
.ai-drawer__actions button:hover:not(:disabled) {
  border-color: var(--border-strong);
  color: var(--text);
  background: var(--panel-soft);
}
.ai-drawer__actions button:disabled { opacity: 0.4; cursor: not-allowed; }

.ai-drawer__body {
  overflow: auto;
  padding: 12px 16px 24px;
}

.ai-drawer__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ai-drawer__item {
  padding: 12px 14px;
  background: var(--panel-soft);
  border: 1px solid var(--border-soft);
  border-radius: var(--r-md);
  transition: background var(--t-fast) var(--ease-out);
}
.ai-drawer__item:hover { background: var(--panel-strong); }
.ai-drawer__item.is-failed {
  background: var(--danger-soft);
  border-color: var(--danger);
}
.ai-drawer__item.is-pending { border-left: 3px solid var(--primary); }

.ai-drawer__row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: var(--fs-sm);
  flex-wrap: wrap;
}
.ai-drawer__time {
  color: var(--muted);
  font-size: var(--fs-xs);
  font-feature-settings: "tnum";
  font-family: "JetBrains Mono", Consolas, monospace;
}
.ai-drawer__scene {
  font-weight: var(--fw-semibold);
  color: var(--text-strong);
}
.ai-drawer__model {
  color: var(--accent-clay);
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: var(--fs-xs);
  flex-shrink: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ai-drawer__duration {
  margin-left: auto;
  color: var(--muted);
  font-size: var(--fs-xs);
  font-feature-settings: "tnum";
}
.ai-drawer__badge {
  margin-left: auto;
  padding: 1px 8px;
  border-radius: var(--r-pill);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
}
.ai-drawer__badge--pending {
  background: var(--primary-soft);
  color: var(--primary-strong);
}
.ai-drawer__badge--failed {
  background: var(--danger);
  color: var(--on-accent);
}

.ai-drawer__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 6px;
}
.ai-drawer__chip {
  display: inline-flex;
  padding: 1px 7px;
  border-radius: var(--r-pill);
  font-size: var(--fs-xs);
  background: var(--panel);
  color: var(--muted);
  border: 1px solid var(--border-soft);
}
.ai-drawer__chip--agent { background: var(--primary-soft); color: var(--primary-strong); border-color: transparent; }
.ai-drawer__chip--direct { background: var(--accent-sage-soft); color: var(--accent-sage); border-color: transparent; }
.ai-drawer__chip--io { font-feature-settings: "tnum"; }

.ai-drawer__error {
  margin: 8px 0 0;
  padding: 6px 10px;
  background: var(--panel);
  color: var(--danger);
  border-radius: var(--r-xs);
  font-size: var(--fs-xs);
  line-height: 1.5;
  word-break: break-word;
}

.ai-drawer__empty {
  padding: 48px 24px;
  text-align: center;
  color: var(--muted);
}
.ai-drawer__empty strong {
  display: block;
  font-size: var(--fs-md);
  color: var(--text-strong);
  margin-bottom: 8px;
}
.ai-drawer__empty p {
  margin: 0;
  font-size: var(--fs-sm);
  line-height: 1.6;
}

/* —— 进入/退出 —— */
.ai-drawer-fade-enter-active, .ai-drawer-fade-leave-active {
  transition: opacity var(--t) var(--ease-out);
}
.ai-drawer-fade-enter-from, .ai-drawer-fade-leave-to { opacity: 0; }

.ai-drawer-slide-enter-active, .ai-drawer-slide-leave-active {
  transition: transform var(--t-slow) var(--ease-spring);
}
.ai-drawer-slide-enter-from, .ai-drawer-slide-leave-to {
  transform: translateX(100%);
}
</style>
