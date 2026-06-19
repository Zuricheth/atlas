<script setup lang="ts">
defineProps<{
  size?: number
  color?: string
}>()
</script>

<template>
  <span
    class="a-spinner"
    :style="{
      width: (size || 14) + 'px',
      height: (size || 14) + 'px',
      borderColor: color || 'currentColor',
      borderRightColor: 'transparent',
    }"
    aria-label="加载中"
  />
</template>

<style scoped>
.a-spinner {
  display: inline-block;
  border: 2px solid;
  border-radius: 50%;
  animation: a-spin 0.7s linear infinite;
  vertical-align: -2px;
}
</style>
