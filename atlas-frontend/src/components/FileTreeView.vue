<script setup lang="ts">
withDefaults(defineProps<{
  rows: any[]
  currentNoteId: number | null
  large?: boolean
  emptyText?: string
  showOpenFile?: boolean
  showDeleteAsset?: boolean
}>(), {
  large: false,
  emptyText: '暂无文件',
  showOpenFile: false,
  showDeleteAsset: false,
})

const emit = defineEmits<{
  openNode: [row: any]
  openFile: [item: any]
  deleteAsset: [item: any]
}>()

function isActive(row: any, currentNoteId: number | null) {
  if (row.type === 'folder') return false
  return (row.note?.id && row.note.id === currentNoteId) || (row.item?.noteId && row.item.noteId === currentNoteId)
}
</script>

<template>
  <div v-if="rows.length > 0" class="file-tree" :class="{ 'large-tree': large }">
    <div
      v-for="row in rows"
      :key="row.key"
      class="tree-row"
      :class="[`tree-${row.type}`, { active: isActive(row, currentNoteId) }]"
      :style="{ paddingLeft: `${(large ? 10 : 8) + row.level * (large ? 22 : 16)}px` }"
    >
      <button class="tree-main" @click="emit('openNode', row)">
        <span class="tree-caret">{{ row.expandable ? (row.expanded ? '▾' : '▸') : '' }}</span>
        <span class="tree-icon" :class="`tree-icon-${row.type}`"></span>
        <span class="tree-name">{{ row.name }}</span>
      </button>
      <button v-if="showOpenFile && row.type === 'library'" class="text-button" @click="emit('openFile', row.item)">
        {{ large ? '打开原文件' : '打开' }}
      </button>
      <button v-if="showDeleteAsset && row.type === 'library'" class="text-button danger-link" @click="emit('deleteAsset', row.item)">删除</button>
    </div>
  </div>
  <p v-else class="empty" :class="{ compact: !large }">{{ emptyText }}</p>
</template>
