<script setup lang="ts">
import { computed, ref, watch, nextTick, onMounted } from 'vue'

type Tab = { value: string; label: string; count?: number }

const props = defineProps<{
  modelValue: string
  tabs: Tab[]
  variant?: 'underline' | 'pill'
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const trackEl = ref<HTMLElement | null>(null)
const indicator = ref({ left: 0, width: 0 })

const activeIndex = computed(() => props.tabs.findIndex((t) => t.value === props.modelValue))

function updateIndicator() {
  if (!trackEl.value) return
  const idx = activeIndex.value
  if (idx < 0) return
  const child = trackEl.value.children[idx] as HTMLElement | undefined
  if (!child) return
  indicator.value = { left: child.offsetLeft, width: child.offsetWidth }
}

watch(() => props.modelValue, () => nextTick(updateIndicator))
watch(() => props.tabs, () => nextTick(updateIndicator))
onMounted(() => nextTick(updateIndicator))
</script>

<template>
  <div :class="['a-tabs', `a-tabs--${variant || 'underline'}`]">
    <div ref="trackEl" class="a-tabs__track">
      <button
        v-for="tab in tabs"
        :key="tab.value"
        :class="['a-tabs__tab', tab.value === modelValue && 'is-active']"
        @click="emit('update:modelValue', tab.value)"
      >
        {{ tab.label }}
        <span v-if="tab.count != null" class="a-tabs__count">{{ tab.count }}</span>
      </button>
      <span
        v-if="variant !== 'pill'"
        class="a-tabs__indicator"
        :style="{ transform: `translateX(${indicator.left}px)`, width: indicator.width + 'px' }"
      />
    </div>
  </div>
</template>

<style scoped>
.a-tabs__track {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.a-tabs__tab {
  position: relative;
  padding: 8px 12px;
  background: transparent;
  border: 0;
  cursor: pointer;
  color: var(--muted);
  font-weight: var(--fw-medium);
  font-size: var(--fs-base);
  border-radius: var(--r-sm);
  transition: color var(--t) var(--ease-out), background var(--t-fast) var(--ease-out);
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.a-tabs__tab:hover { color: var(--text); }
.a-tabs__tab.is-active { color: var(--primary-strong); }

.a-tabs__count {
  background: var(--panel-strong);
  color: var(--muted);
  padding: 1px 7px;
  font-size: var(--fs-xs);
  border-radius: var(--r-pill);
  font-weight: var(--fw-semibold);
}
.is-active .a-tabs__count { background: var(--primary-soft); color: var(--primary-strong); }

.a-tabs--underline .a-tabs__track {
  border-bottom: 1px solid var(--border-soft);
}
.a-tabs--underline .a-tabs__indicator {
  position: absolute;
  bottom: -1px;
  left: 0;
  height: 2px;
  border-radius: 2px;
  background: var(--primary);
  transition: transform var(--t-slow) var(--ease-out), width var(--t-slow) var(--ease-out);
  pointer-events: none;
}

.a-tabs--pill .a-tabs__tab.is-active {
  background: var(--primary-soft);
  color: var(--primary-strong);
}
</style>
