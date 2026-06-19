<script setup lang="ts">
/**
 * 顶栏 AI 活动触发按钮 — 显示当前活跃数,点击打开抽屉
 */
import { useAiActivityStore } from '../../stores/aiActivity'

const store = useAiActivityStore()
</script>

<template>
  <button
    class="ai-activity-btn"
    :class="{ 'has-pending': store.pendingCount > 0, 'has-failed': store.failedCount > 0 }"
    :title="store.pendingCount > 0 ? `${store.pendingCount} 个 AI 调用进行中` : `已记录 ${store.total} 次 AI 调用`"
    @click="store.toggle()"
  >
    <svg viewBox="0 0 16 16" width="13" height="13" aria-hidden="true">
      <path d="M8 2c-2 0-3 1-3 3 0 1 .5 1.7 1 2.2.4.4.7.8.7 1.3v.5h2.6v-.5c0-.5.3-.9.7-1.3C10.5 6.7 11 6 11 5c0-2-1-3-3-3z"
            stroke="currentColor" stroke-width="1.2" fill="none" stroke-linejoin="round"/>
      <line x1="6" y1="11" x2="10" y2="11" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
      <line x1="6.5" y1="13" x2="9.5" y2="13" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
    </svg>
    <span v-if="store.pendingCount > 0" class="ai-activity-btn__dot" />
    <span v-else-if="store.total > 0" class="ai-activity-btn__count">{{ store.total }}</span>
  </button>
</template>

<style scoped>
.ai-activity-btn {
  position: relative;
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  color: var(--muted);
  cursor: pointer;
  transition: background var(--t-fast) var(--ease-out), color var(--t-fast) var(--ease-out);
}
.ai-activity-btn:hover {
  background: var(--panel-soft);
  color: var(--text);
}

.ai-activity-btn.has-pending { color: var(--primary); }
.ai-activity-btn.has-failed { color: var(--danger); }

.ai-activity-btn__dot {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--primary);
  box-shadow: 0 0 0 2px var(--panel);
  animation: a-breathe 1.6s var(--ease-out) infinite;
}

.ai-activity-btn__count {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  display: grid;
  place-items: center;
  border-radius: var(--r-pill);
  background: var(--accent-clay);
  color: var(--on-accent);
  font-size: 10px;
  font-weight: var(--fw-bold);
  border: 1.5px solid var(--panel);
  font-feature-settings: "tnum";
  line-height: 1;
}
.ai-activity-btn.has-failed .ai-activity-btn__count {
  background: var(--danger);
}
</style>
