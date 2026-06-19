<script setup lang="ts">
defineProps<{
  variant?: 'primary' | 'ghost' | 'danger' | 'text' | 'subtle'
  size?: 'sm' | 'md' | 'lg'
  loading?: boolean
  disabled?: boolean
  type?: 'button' | 'submit' | 'reset'
  block?: boolean
}>()
defineEmits<{ (e: 'click', ev: MouseEvent): void }>()
</script>

<template>
  <button
    :type="type || 'button'"
    :disabled="disabled || loading"
    :class="[
      'a-btn',
      `a-btn--${variant || 'subtle'}`,
      `a-btn--${size || 'md'}`,
      block && 'a-btn--block',
      loading && 'a-btn--loading',
    ]"
    @click="$emit('click', $event)"
  >
    <span v-if="loading" class="a-btn__spinner" aria-hidden="true" />
    <slot />
  </button>
</template>

<style scoped>
.a-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  cursor: pointer;
  white-space: nowrap;
  user-select: none;
  font-weight: var(--fw-medium);
  transition: background var(--t-fast) var(--ease-out),
              border-color var(--t-fast) var(--ease-out),
              color var(--t-fast) var(--ease-out),
              transform var(--t-fast) var(--ease-out),
              box-shadow var(--t-fast) var(--ease-out);
}
.a-btn:active:not(:disabled):not(.a-btn--loading) {
  transform: scale(0.97);
}
.a-btn:focus-visible {
  outline: none;
  box-shadow: var(--ring);
}
.a-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.a-btn--sm { min-height: 28px; padding: 0 10px; font-size: var(--fs-sm); border-radius: var(--r-xs); }
.a-btn--md { min-height: 34px; padding: 0 14px; font-size: var(--fs-base); }
.a-btn--lg { min-height: 42px; padding: 0 20px; font-size: var(--fs-md); border-radius: var(--r-md); }
.a-btn--block { width: 100%; }

.a-btn--primary {
  background: linear-gradient(180deg, #356268 0%, var(--primary) 100%);
  color: #fff;
  border-color: var(--primary-strong);
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.18) inset, 0 1px 2px rgba(47, 93, 98, 0.3);
}
.a-btn--primary:hover:not(:disabled) {
  background: linear-gradient(180deg, var(--primary) 0%, var(--primary-strong) 100%);
}

.a-btn--ghost {
  background: transparent;
  border-color: var(--border);
  color: var(--text);
}
.a-btn--ghost:hover:not(:disabled) {
  background: var(--panel-soft);
  border-color: var(--border-strong);
}

.a-btn--subtle {
  background: var(--panel-strong);
  color: var(--text);
}
.a-btn--subtle:hover:not(:disabled) {
  background: #e8e5dc;
}

.a-btn--danger {
  background: var(--danger-soft);
  color: var(--danger);
}
.a-btn--danger:hover:not(:disabled) {
  background: #f0d4cf;
}

.a-btn--text {
  background: transparent;
  color: var(--primary);
  padding: 0 6px;
  min-height: auto;
  font-weight: var(--fw-semibold);
}
.a-btn--text:hover:not(:disabled) {
  color: var(--primary-strong);
  text-decoration: underline;
}

.a-btn--loading { pointer-events: none; }
.a-btn__spinner {
  width: 12px;
  height: 12px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: a-spin 0.7s linear infinite;
}
</style>
