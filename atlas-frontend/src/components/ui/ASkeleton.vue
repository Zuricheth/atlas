<script setup lang="ts">
defineProps<{
  rows?: number
  width?: string
  height?: string
  circle?: boolean
}>()
</script>

<template>
  <div v-if="rows && rows > 1" class="a-skel-stack">
    <div
      v-for="i in rows"
      :key="i"
      class="skeleton a-skel-line"
      :style="{ width: i === rows ? '70%' : '100%' }"
    />
  </div>
  <div
    v-else
    class="skeleton"
    :class="circle && 'a-skel-circle'"
    :style="{ width: width || '100%', height: height || '14px', borderRadius: circle ? '999px' : undefined }"
  />
</template>

<style scoped>
.a-skel-stack { display: flex; flex-direction: column; gap: 8px; }
.a-skel-line { height: 12px; }
.a-skel-circle { aspect-ratio: 1 / 1; }
</style>
