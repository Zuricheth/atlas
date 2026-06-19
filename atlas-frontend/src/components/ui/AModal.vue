<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'

const props = defineProps<{
  modelValue: boolean
  title?: string
  width?: string
  closeOnOverlay?: boolean
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void; (e: 'close'): void }>()

function close() {
  emit('update:modelValue', false)
  emit('close')
}

function onKey(ev: KeyboardEvent) {
  if (ev.key === 'Escape' && props.modelValue) close()
}

watch(
  () => props.modelValue,
  (open) => {
    if (typeof document === 'undefined') return
    document.body.style.overflow = open ? 'hidden' : ''
  },
)

onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <Transition name="a-modal">
      <div
        v-if="modelValue"
        class="a-modal"
        @mousedown.self="closeOnOverlay !== false && close()"
      >
        <div class="a-modal__panel" :style="{ width: width || '560px' }">
          <header v-if="title || $slots.header" class="a-modal__head">
            <slot name="header">
              <h3 class="a-modal__title">{{ title }}</h3>
            </slot>
            <button class="a-modal__close" aria-label="关闭" @click="close">
              <svg viewBox="0 0 16 16" width="14" height="14"><path d="M3 3l10 10M13 3L3 13" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
            </button>
          </header>
          <div class="a-modal__body">
            <slot />
          </div>
          <footer v-if="$slots.footer" class="a-modal__foot">
            <slot name="footer" />
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.a-modal {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(28, 31, 38, 0.42);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

.a-modal__panel {
  max-width: calc(100vw - 48px);
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-xl), var(--shadow-inset-strong);
  border: 1px solid rgba(255, 255, 255, 0.6);
  overflow: hidden;
}

.a-modal__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-soft);
}

.a-modal__title {
  margin: 0;
  font-size: var(--fs-xl);
  font-weight: var(--fw-semibold);
  color: var(--text-strong);
}

.a-modal__close {
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
.a-modal__close:hover { background: var(--panel-strong); color: var(--text); }

.a-modal__body {
  padding: 20px;
  overflow: auto;
}

.a-modal__foot {
  padding: 14px 20px;
  border-top: 1px solid var(--border-soft);
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  background: var(--panel-soft);
}

/* —— 进入/退出 transition —— */
.a-modal-enter-active, .a-modal-leave-active {
  transition: opacity var(--t) var(--ease-out);
}
.a-modal-enter-active .a-modal__panel,
.a-modal-leave-active .a-modal__panel {
  transition: transform var(--t-slow) var(--ease-spring), opacity var(--t-slow) var(--ease-out);
}
.a-modal-enter-from { opacity: 0; }
.a-modal-leave-to { opacity: 0; }
.a-modal-enter-from .a-modal__panel { transform: scale(0.94) translateY(8px); opacity: 0; }
.a-modal-leave-to .a-modal__panel { transform: scale(0.96); opacity: 0; }
</style>
