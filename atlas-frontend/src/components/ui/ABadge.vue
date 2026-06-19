<script setup lang="ts">
defineProps<{
  variant?: 'neutral' | 'primary' | 'success' | 'danger' | 'warn' | 'sage' | 'clay'
  dot?: boolean
  size?: 'sm' | 'md'
}>()
</script>

<template>
  <span
    :class="[
      'a-badge',
      `a-badge--${variant || 'neutral'}`,
      `a-badge--${size || 'sm'}`,
      dot && 'a-badge--with-dot',
    ]"
  >
    <span v-if="dot" class="a-badge__dot" />
    <slot />
  </span>
</template>

<style scoped>
.a-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: var(--r-pill);
  font-weight: var(--fw-medium);
  white-space: nowrap;
  border: 1px solid transparent;
}
.a-badge--sm { padding: 2px 8px; font-size: var(--fs-xs); }
.a-badge--md { padding: 4px 12px; font-size: var(--fs-sm); }

.a-badge--neutral { background: var(--panel-strong); color: var(--muted); }
.a-badge--primary { background: var(--primary-soft); color: var(--primary-strong); }
.a-badge--success { background: var(--success-soft); color: var(--success); }
.a-badge--danger { background: var(--danger-soft); color: var(--danger); }
.a-badge--warn { background: var(--warn-soft); color: var(--warn); }
.a-badge--sage { background: var(--accent-sage-soft); color: var(--accent-sage); }
.a-badge--clay { background: var(--accent-clay-soft); color: var(--accent-clay); }

.a-badge__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  animation: a-breathe 2.4s var(--ease-out) infinite;
}
</style>
