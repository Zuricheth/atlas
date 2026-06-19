<script setup lang="ts">
defineProps<{
  rows: any[]
  currentId: number | null
  draggedId: number | null
}>()

const emit = defineEmits<{
  open: [row: any]
  rename: [row: any]
  dragStart: [row: any]
  dragEnd: []
  drop: [row: any]
}>()
</script>

<template>
  <div v-if="rows.length > 0" class="notebook-tree">
    <article
      v-for="row in rows"
      :key="row.id"
      class="notebook-tree-row"
      :class="[
        `notebook-kind-${row.nodeType || 'collection'}`,
        { active: row.id === currentId, dragging: draggedId === row.id }
      ]"
      :style="{ paddingLeft: `${8 + Number(row.level ?? row.relativeLevel ?? 0) * 16}px` }"
      draggable="true"
      @dragstart="emit('dragStart', row)"
      @dragend="emit('dragEnd')"
      @dragover.prevent
      @drop.prevent="emit('drop', row)"
    >
      <button class="notebook-tree-main" @click="emit('open', row)">
        <span class="tree-caret">{{ row.expandable ? (row.expanded ? '▾' : '▸') : '' }}</span>
        <span class="notebook-dot"></span>
        <span class="notebook-tree-text">
          <strong>{{ row.name }}</strong>
          <small>{{ row.description || row.path }}</small>
        </span>
      </button>
      <button class="text-button notebook-row-action" @click="emit('rename', row)">编辑</button>
    </article>
  </div>
  <p v-else class="empty compact">暂无知识库</p>
</template>
