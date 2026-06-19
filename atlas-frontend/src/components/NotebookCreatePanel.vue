<script setup lang="ts">
defineProps<{
  name: string
  mode: 'top' | 'child'
  currentName?: string
  canCreateChild: boolean
  loading?: boolean
}>()

const emit = defineEmits<{
  'update:name': [value: string]
  'update:mode': [value: 'top' | 'child']
  create: []
}>()
</script>

<template>
  <section class="notebook-create-panel">
    <input
      :value="name"
      placeholder="新知识库"
      @input="emit('update:name', ($event.target as HTMLInputElement).value)"
      @keydown.enter.prevent="emit('create')"
    />
    <div class="notebook-create-mode" role="group" aria-label="创建位置">
      <button
        type="button"
        :class="{ active: mode === 'top' }"
        @click="emit('update:mode', 'top')"
      >顶层</button>
      <button
        type="button"
        :class="{ active: mode === 'child' }"
        :disabled="!canCreateChild"
        @click="emit('update:mode', 'child')"
      >子库</button>
    </div>
    <p class="notebook-create-hint">
      {{ mode === 'child' && canCreateChild ? `建在：${currentName || '当前节点'}` : '建在顶层知识库' }}
    </p>
    <button class="primary wide" :disabled="loading || !name.trim()" @click="emit('create')">
      {{ loading ? '创建中' : '创建知识库' }}
    </button>
  </section>
</template>
