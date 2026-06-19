<script setup lang="ts">
import { onUnmounted, ref } from 'vue'

type ToastItem = { id: number; type: 'success' | 'error' | 'info' | 'warn'; text: string; ttl: number }

const items = ref<ToastItem[]>([])
let seq = 0
const timers = new Map<number, number>()

function push(type: ToastItem['type'], text: string, ttl = 3000) {
  const id = ++seq
  items.value.push({ id, type, text, ttl })
  const timer = window.setTimeout(() => dismiss(id), ttl)
  timers.set(id, timer)
}

function dismiss(id: number) {
  const t = timers.get(id)
  if (t) window.clearTimeout(t)
  timers.delete(id)
  items.value = items.value.filter((i) => i.id !== id)
}

defineExpose({ push, dismiss })

onUnmounted(() => {
  timers.forEach((t) => window.clearTimeout(t))
  timers.clear()
})
</script>

<template>
  <Teleport to="body">
    <div class="a-toast-stack" aria-live="polite">
      <TransitionGroup name="a-toast">
        <div
          v-for="item in items"
          :key="item.id"
          :class="['a-toast', `a-toast--${item.type}`]"
          @click="dismiss(item.id)"
        >
          <span class="a-toast__dot" />
          <span class="a-toast__text">{{ item.text }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.a-toast-stack {
  position: fixed;
  right: 18px;
  bottom: 18px;
  z-index: var(--z-toast);
  display: flex;
  flex-direction: column-reverse;
  gap: 10px;
  pointer-events: none;
}

.a-toast {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-radius: var(--r-md);
  font-size: var(--fs-base);
  font-weight: var(--fw-medium);
  color: #fff;
  background: #23262e;
  box-shadow: var(--shadow-lg), var(--shadow-inset);
  min-width: 240px;
  max-width: 420px;
  cursor: pointer;
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.a-toast--success { background: linear-gradient(180deg, #5d8c61 0%, var(--success) 100%); }
.a-toast--error { background: linear-gradient(180deg, #c2554a 0%, var(--danger) 100%); }
.a-toast--warn { background: linear-gradient(180deg, #ad7c3c 0%, var(--warn) 100%); }
.a-toast--info { background: linear-gradient(180deg, #356268 0%, var(--primary) 100%); }

.a-toast__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #fff;
  flex-shrink: 0;
  animation: a-breathe 2s var(--ease-out) infinite;
}

.a-toast__text {
  white-space: pre-wrap;
  line-height: 1.4;
}

.a-toast-enter-active, .a-toast-leave-active {
  transition: transform var(--t-slow) var(--ease-spring), opacity var(--t-slow) var(--ease-out);
}
.a-toast-enter-from { opacity: 0; transform: translateX(40px) scale(0.96); }
.a-toast-leave-to { opacity: 0; transform: translateX(40px) scale(0.96); }
</style>
