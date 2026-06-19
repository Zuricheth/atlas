<script setup lang="ts">
defineProps<{
  variant?: 'default' | 'glass' | 'soft' | 'outline'
  padding?: 'none' | 'sm' | 'md' | 'lg'
  lift?: boolean
  glow?: boolean
}>()
</script>

<template>
  <div
    :class="[
      'a-card',
      `a-card--${variant || 'default'}`,
      `a-card--p-${padding || 'md'}`,
      lift && 'a-card--lift',
      glow && 'a-card--glow',
    ]"
  >
    <slot />
  </div>
</template>

<style scoped>
.a-card {
  border-radius: var(--r-lg);
  position: relative;
  transition: transform var(--t) var(--ease-out), box-shadow var(--t) var(--ease-out);
}
.a-card--default {
  background: var(--panel);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-sm), var(--shadow-inset);
}
.a-card--soft {
  background: var(--panel-soft);
  border: 1px solid var(--border-soft);
}
.a-card--outline {
  background: transparent;
  border: 1px solid var(--border);
}
.a-card--glass {
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(16px) saturate(1.4);
  -webkit-backdrop-filter: blur(16px) saturate(1.4);
  border: 1px solid rgba(255, 255, 255, 0.6);
  box-shadow: var(--shadow), var(--shadow-inset);
}

.a-card--p-none { padding: 0; }
.a-card--p-sm { padding: 12px; }
.a-card--p-md { padding: 16px 18px; }
.a-card--p-lg { padding: 24px 26px; }

.a-card--lift {
  cursor: pointer;
}
.a-card--lift:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-lg), var(--shadow-inset);
}

.a-card--glow::after {
  content: "";
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  pointer-events: none;
  background: linear-gradient(135deg, rgba(47, 93, 98, 0.18), transparent 40%);
  opacity: 0;
  transition: opacity var(--t-slow) var(--ease-out);
}
.a-card--glow:hover::after { opacity: 1; }
</style>
