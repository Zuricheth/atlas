<script setup lang="ts">
defineProps<{
  title?: string
  description?: string
  icon?: string
}>()
</script>

<template>
  <div class="a-empty">
    <div class="a-empty__icon" v-if="icon || $slots.icon">
      <slot name="icon">
        <span class="a-empty__icon-default">{{ icon }}</span>
      </slot>
    </div>
    <h4 v-if="title" class="a-empty__title">{{ title }}</h4>
    <p v-if="description" class="a-empty__desc">{{ description }}</p>
    <div v-if="$slots.action" class="a-empty__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.a-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 32px;
  text-align: center;
  color: var(--muted);
}
.a-empty__icon {
  width: 56px;
  height: 56px;
  border-radius: var(--r-pill);
  background: var(--primary-soft);
  color: var(--primary);
  display: grid;
  place-items: center;
  margin-bottom: 16px;
  box-shadow: var(--shadow-inset);
}
.a-empty__icon-default {
  font-size: 22px;
  font-weight: var(--fw-bold);
  letter-spacing: -0.02em;
}
.a-empty__title {
  margin: 0 0 6px;
  font-size: var(--fs-lg);
  color: var(--text-strong);
}
.a-empty__desc {
  margin: 0;
  font-size: var(--fs-sm);
  line-height: 1.6;
  max-width: 360px;
}
.a-empty__action { margin-top: 16px; }
</style>
