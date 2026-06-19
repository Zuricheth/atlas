<script setup lang="ts">
defineProps<{
  title: string
  mode: 'read' | 'edit' | 'memory'
  agents: any[]
  selectedAgentId: number | ''
  source: 'attachments' | 'attachmentsAndNote'
  loadingAgentNote: boolean
  saving: boolean
  hasNote: boolean
  hasNotebook: boolean
}>()

const emit = defineEmits<{
  'update:title': [value: string]
  'update:mode': [value: 'read' | 'edit' | 'memory']
  'update:selectedAgentId': [value: number | '']
  'update:source': [value: 'attachments' | 'attachmentsAndNote']
  generateAgentNote: []
  overview: []
  history: []
  deleteNote: []
  save: []
}>()
</script>

<template>
  <div class="note-toolbar">
    <input
      :value="title"
      class="title-input"
      placeholder="未命名笔记"
      @input="emit('update:title', ($event.target as HTMLInputElement).value)"
    />
    <div class="toolbar-actions">
      <select
        :value="selectedAgentId"
        class="agent-select"
        title="选择知识库 Agent"
        @change="emit('update:selectedAgentId', Number(($event.target as HTMLSelectElement).value) || '')"
      >
        <option value="">知识库 Agent</option>
        <option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agent.name }}</option>
      </select>
      <select
        :value="source"
        class="agent-source-select"
        title="AI 做笔记发送范围"
        @change="emit('update:source', (($event.target as HTMLSelectElement).value as 'attachments' | 'attachmentsAndNote'))"
      >
        <option value="attachments">只发附件</option>
        <option value="attachmentsAndNote">附件+当前内容</option>
      </select>
      <button class="ghost" :disabled="loadingAgentNote || !hasNote" @click="emit('generateAgentNote')">
        {{ loadingAgentNote ? '生成中' : 'AI 做笔记' }}
      </button>
      <button class="ghost" @click="emit('overview')">总览</button>
      <button class="ghost" :disabled="!hasNote" title="查看历史版本并回滚" @click="emit('history')">历史</button>
      <button class="ghost" :class="{ active: mode === 'read' }" @click="emit('update:mode', 'read')">阅读</button>
      <button class="ghost" :class="{ active: mode === 'edit' }" @click="emit('update:mode', 'edit')">编辑</button>
      <button class="ghost" :class="{ active: mode === 'memory' }" @click="emit('update:mode', 'memory')">AI 记忆</button>
      <button class="ghost" :disabled="!hasNote" @click="emit('deleteNote')">删除</button>
      <button class="primary" :disabled="saving || !hasNotebook" @click="emit('save')">
        {{ saving ? '保存中' : '保存' }}
      </button>
    </div>
  </div>
</template>
