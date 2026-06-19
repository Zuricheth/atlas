<script setup lang="ts">
import { computed, ref } from 'vue'

type Notebook = { name: string; fileCount: number; lastModified: string }
type NotebookSearchResult = Notebook & { score: number; reason: string; snippet: string }
type VcpFile = { notebook: string; filename: string; size: number; lastModified: string }
type Draft = {
  id: number
  noteId: number
  notebookId: number
  title: string
  memoryContent: string
  suggestedDailyNote: string
  targetDailyNote: string
  status: string
  syncedPath: string
  createdAt: string
  updatedAt: string
}

const props = defineProps<{
  agents: any[]
  selectedAgentId: number | ''
  loading: boolean
  notebookSearchLoading: boolean
  transferLoading: boolean
  status: string
  notebooks: Notebook[]
  notebookQuery: string
  notebookResults: NotebookSearchResult[]
  newNotebook: string
  selectedNotebook: string
  drafts: Draft[]
  selectedDraftIds: number[]
  suggestion: string
  files: VcpFile[]
  selectedFile: string
  selectedFileNames: string[]
  fileContent: string
  transferTarget: string
  transferOverwrite: boolean
  transferMoveSynced: boolean
  transferDeleteSource: boolean
  transferLog: string
  renderedSuggestion: string
}>()

const emit = defineEmits<{
  'update:selectedAgentId': [value: number | '']
  'update:status': [value: string]
  'update:notebookQuery': [value: string]
  'update:newNotebook': [value: string]
  'update:selectedNotebook': [value: string]
  'update:selectedFile': [value: string]
  'update:fileContent': [value: string]
  'update:transferTarget': [value: string]
  'update:transferOverwrite': [value: boolean]
  'update:transferMoveSynced': [value: boolean]
  'update:transferDeleteSource': [value: boolean]
  refresh: []
  askAgent: []
  ensureAgents: []
  searchNotebooks: []
  selectNotebook: [name: string]
  createNotebook: []
  deleteNotebook: [notebook: Notebook]
  loadFiles: []
  updateDraft: [draft: Draft]
  syncDraft: [draft: Draft]
  suggestDraft: [draft: Draft]
  deleteDraft: [draft: Draft]
  openFile: [file: VcpFile]
  saveFile: []
  deleteFile: []
  toggleFileSelection: [filename: string, checked: boolean]
  toggleAllFiles: [checked: boolean]
  toggleDraftSelection: [id: number, checked: boolean]
  toggleAllDrafts: [checked: boolean]
  transferFiles: []
  transferNotebook: []
  transferDrafts: []
}>()

const selectedNotebookMeta = computed(() => props.notebooks.find((item) => item.name === props.selectedNotebook) || null)
const pendingDrafts = computed(() => props.drafts.filter((draft) => draft.status === 'pending' || draft.status === 'review').length)
const syncedDrafts = computed(() => props.drafts.filter((draft) => draft.status === 'synced').length)
const failedDrafts = computed(() => props.drafts.filter((draft) => draft.status === 'failed').length)
const totalFiles = computed(() => props.notebooks.reduce((sum, notebook) => sum + (notebook.fileCount || 0), 0))
const selectedFileCount = computed(() => props.selectedFileNames.length)
const selectedDraftCount = computed(() => props.selectedDraftIds.length)
const allFilesSelected = computed(() => props.files.length > 0 && props.selectedFileNames.length === props.files.length)
const allDraftsSelected = computed(() => props.drafts.length > 0 && props.selectedDraftIds.length === props.drafts.length)
const showingSearchResults = computed(() => props.notebookQuery.trim().length > 0)
const showNotebookBrowser = ref(false)
const activeNotebookDetail = ref<NotebookSearchResult | null>(null)
const notebookPendingDeleteName = ref('')
const confirmingDeleteFiles = ref(false)
const notebookDirectory = computed<NotebookSearchResult[]>(() => {
  if (showingSearchResults.value) return props.notebookResults
  return props.notebooks.map((notebook) => ({
    ...notebook,
    score: 0,
    reason: '全部日记本',
    snippet: `${notebook.fileCount || 0} 个文件，最近更新 ${notebook.lastModified}`,
  }))
})
const notebookPreviewRows = computed(() => notebookDirectory.value.slice(0, 7))
const fileDeleteTargetCount = computed(() => selectedFileCount.value || (props.selectedFile ? 1 : 0))
const fileDeleteLabel = computed(() => {
  if (fileDeleteTargetCount.value === 0) return '删除文件'
  if (confirmingDeleteFiles.value) return `确认删除 ${fileDeleteTargetCount.value} 个文件`
  if (selectedFileCount.value > 0) return `删除选中 ${selectedFileCount.value} 个`
  return '删除当前文件'
})
const notebookGroups = computed(() => {
  const groups = new Map<string, NotebookSearchResult[]>()
  for (const notebook of notebookDirectory.value) {
    const group = classifyNotebook(notebook)
    groups.set(group, [...(groups.get(group) || []), notebook])
  }
  return Array.from(groups.entries()).map(([name, items]) => ({ name, items }))
})

function statusLabel(status: string) {
  if (status === 'pending') return '待同步'
  if (status === 'review') return '需复核'
  if (status === 'failed') return '失败'
  if (status === 'synced') return '已同步'
  if (status === 'ignored') return '已忽略'
  return '草稿'
}

function scoreLabel(score: number) {
  if (score >= 80) return '强匹配'
  if (score >= 45) return '相关'
  if (score > 0) return '可参考'
  return '目录'
}

function classifyNotebook(notebook: NotebookSearchResult) {
  const text = `${notebook.name} ${notebook.reason} ${notebook.snippet}`.toLowerCase()
  if (/钢琴|音乐|奏鸣|月光|练习曲|贝多芬|肖邦/.test(text)) return '音乐与练习'
  if (/叙澜|写作|设定|神兵|小说|书房|角色|剧情|论坛/.test(text)) return '创作与世界观'
  if (/atlas|vcp|agent|deepwiki|rag|ai|系统|架构|智能体/.test(text)) return 'Atlas 与系统'
  if (/日常|日志|工作|生活|爱弥斯|微明/.test(text)) return '日常与工作'
  if ((notebook.fileCount || 0) === 0) return '空日记本'
  return '知识与资料'
}

function useSelectedNotebook(draft: Draft) {
  if (!props.selectedNotebook) return
  draft.targetDailyNote = props.selectedNotebook
}

function useNotebookAsTransferTarget(name: string) {
  emit('update:transferTarget', name)
}

function openNotebookDetail(notebook: NotebookSearchResult) {
  activeNotebookDetail.value = notebook
}

function chooseNotebookFromBrowser(name: string) {
  emit('selectNotebook', name)
  showNotebookBrowser.value = false
}

function requestDeleteNotebook() {
  if (!selectedNotebookMeta.value) return
  if (notebookPendingDeleteName.value !== selectedNotebookMeta.value.name) {
    notebookPendingDeleteName.value = selectedNotebookMeta.value.name
    return
  }
  emit('deleteNotebook', selectedNotebookMeta.value)
  notebookPendingDeleteName.value = ''
}

function requestDeleteFiles() {
  if (fileDeleteTargetCount.value === 0) return
  if (!confirmingDeleteFiles.value) {
    confirmingDeleteFiles.value = true
    return
  }
  emit('deleteFile')
  confirmingDeleteFiles.value = false
}

function isFileSelected(filename: string) {
  return props.selectedFileNames.includes(filename)
}

function isDraftSelected(id: number) {
  return props.selectedDraftIds.includes(id)
}
</script>

<template>
  <section class="vcp-page vcp-command-center vcp-atelier">
    <header class="vcp-atelier-header">
      <div class="vcp-title-block">
        <span>VCP Memory Console</span>
        <h1>记忆同步与日记本管理</h1>
        <p>先选源日记本，再指定目标；文件、记忆、整本内容都从同一个转移台处理。</p>
      </div>
      <div class="vcp-top-controls">
        <select :value="selectedAgentId" title="选择 VCP Sync Agent" @change="emit('update:selectedAgentId', Number(($event.target as HTMLSelectElement).value) || '')">
          <option value="">默认 VCP Agent</option>
          <option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agent.name }}</option>
        </select>
        <select :value="status" @change="emit('update:status', ($event.target as HTMLSelectElement).value); emit('refresh')">
          <option value="pending">待同步</option>
          <option value="review">需复核</option>
          <option value="failed">失败</option>
          <option value="synced">已同步</option>
          <option value="ignored">已忽略</option>
          <option value="">全部</option>
        </select>
        <button class="ghost" :disabled="loading" @click="emit('refresh')">刷新</button>
        <button class="primary" :disabled="loading || drafts.length === 0" @click="emit('askAgent')">{{ loading ? '分析中' : '问 Agent' }}</button>
      </div>
    </header>

    <section class="vcp-scoreboard" aria-live="polite">
      <article>
        <span>日记本</span>
        <strong>{{ notebooks.length }}</strong>
        <small>{{ totalFiles }} 个文件</small>
      </article>
      <article>
        <span>待处理</span>
        <strong>{{ pendingDrafts }}</strong>
        <small>待同步 / 需复核</small>
      </article>
      <article>
        <span>已同步</span>
        <strong>{{ syncedDrafts }}</strong>
        <small>可继续转移</small>
      </article>
      <article>
        <span>注意项</span>
        <strong>{{ failedDrafts }}</strong>
        <small>失败或需重分配</small>
      </article>
    </section>

    <section class="vcp-studio-grid">
      <aside class="vcp-notebook-rail">
        <div class="vcp-panel-head">
          <div>
            <span>日记本目录</span>
            <strong>{{ showingSearchResults ? '搜索结果' : '全部日记本' }}</strong>
          </div>
          <button class="vcp-count-button" @click="showNotebookBrowser = true">{{ notebookDirectory.length }} 本 · 展开</button>
        </div>

        <div class="vcp-directory-search">
          <label>
            <span>搜索日记本</span>
            <input
              :value="notebookQuery"
              placeholder="名称、主题、人物或关键词"
              @input="emit('update:notebookQuery', ($event.target as HTMLInputElement).value)"
              @keydown.enter="emit('searchNotebooks')"
            />
          </label>
          <button class="primary" :disabled="notebookSearchLoading" @click="emit('searchNotebooks')">{{ notebookSearchLoading ? '检索中' : '检索' }}</button>
        </div>

        <div class="vcp-directory-list">
          <article v-for="notebook in notebookPreviewRows" :key="notebook.name" class="vcp-directory-row" :class="{ active: selectedNotebook === notebook.name }">
            <button class="vcp-directory-main" @click="emit('selectNotebook', notebook.name)">
              <strong>{{ notebook.name }}</strong>
              <small>{{ notebook.fileCount }} 文件 · {{ notebook.lastModified }}</small>
              <em>{{ notebook.reason }} · {{ scoreLabel(notebook.score) }}</em>
              <p>{{ notebook.snippet }}</p>
            </button>
            <div class="vcp-hit-actions">
              <button class="text-button" @click="openNotebookDetail(notebook)">详情</button>
              <button class="text-button" @click="useNotebookAsTransferTarget(notebook.name)">设为目标</button>
            </div>
          </article>
          <button v-if="notebookDirectory.length > notebookPreviewRows.length" class="vcp-open-library" @click="showNotebookBrowser = true">
            打开全库视图，查看 {{ notebookDirectory.length }} 本日记本
          </button>
          <p v-if="notebookDirectory.length === 0" class="empty compact">没有匹配的日记本。</p>
        </div>
      </aside>

      <main class="vcp-operation-deck">
        <section class="vcp-transfer-panel vcp-orbit-panel">
          <div class="vcp-panel-head">
            <div>
              <span>转移工作台</span>
              <strong>从源日记本到目标日记本</strong>
            </div>
            <small>{{ selectedDraftCount }} 条记忆 · {{ selectedFileCount }} 个文件已选</small>
          </div>

          <div class="vcp-transfer-route vcp-orbit-route">
            <article>
              <span>源日记本</span>
              <strong>{{ selectedNotebookMeta?.name || '未选择' }}</strong>
              <small>{{ selectedNotebookMeta ? `${selectedNotebookMeta.fileCount} 个文件` : '从左侧目录选择' }}</small>
            </article>
            <div class="vcp-route-connector">
              <i></i>
              <b>到</b>
            </div>
            <article>
              <span>目标日记本</span>
              <input
                :value="transferTarget"
                placeholder="从左侧设为目标，或输入新日记本"
                @input="emit('update:transferTarget', ($event.target as HTMLInputElement).value)"
              />
            </article>
          </div>

          <div class="vcp-transfer-options">
            <label>
              <input type="checkbox" :checked="transferOverwrite" @change="emit('update:transferOverwrite', ($event.target as HTMLInputElement).checked)" />
              <span>覆盖同名文件</span>
            </label>
            <label>
              <input type="checkbox" :checked="transferMoveSynced" @change="emit('update:transferMoveSynced', ($event.target as HTMLInputElement).checked)" />
              <span>同步文件随记忆搬迁</span>
            </label>
            <label>
              <input type="checkbox" :checked="transferDeleteSource" @change="emit('update:transferDeleteSource', ($event.target as HTMLInputElement).checked)" />
              <span>整本搬空后删除源本</span>
            </label>
          </div>

          <div class="vcp-transfer-actions">
            <button class="primary" :disabled="transferLoading || selectedDraftCount === 0" @click="emit('transferDrafts')">转移选中记忆</button>
            <button class="primary" :disabled="transferLoading || selectedFileCount === 0" @click="emit('transferFiles')">转移选中文件</button>
            <button class="ghost" :disabled="transferLoading || !selectedNotebook" @click="emit('transferNotebook')">转移整本内容</button>
          </div>

          <pre v-if="transferLog" class="vcp-transfer-log">{{ transferLog }}</pre>
        </section>

        <section class="vcp-drafts-panel">
          <div class="vcp-panel-head">
            <div>
              <span>记忆草稿</span>
              <strong>{{ drafts.length }} 条</strong>
            </div>
            <label class="vcp-checkline">
              <input type="checkbox" :checked="allDraftsSelected" @change="emit('toggleAllDrafts', ($event.target as HTMLInputElement).checked)" />
              全选记忆
            </label>
          </div>

          <article v-if="suggestion" class="vcp-suggestion note-reader" v-html="renderedSuggestion"></article>

          <div class="vcp-draft-stack">
            <article v-for="draft in drafts" :key="draft.id" class="vcp-draft-card">
              <header>
                <label class="vcp-checkline">
                  <input type="checkbox" :checked="isDraftSelected(draft.id)" @change="emit('toggleDraftSelection', draft.id, ($event.target as HTMLInputElement).checked)" />
                  <span>{{ statusLabel(draft.status) }}</span>
                </label>
                <div>
                  <strong>{{ draft.title }}</strong>
                  <small>{{ draft.updatedAt || '未记录更新时间' }}</small>
                </div>
              </header>

              <div class="vcp-target-row">
                <label>
                  <span>目标日记本</span>
                  <input v-model="draft.targetDailyNote" placeholder="选择或输入目标日记本" />
                </label>
                <button class="ghost" :disabled="!selectedNotebook" @click="useSelectedNotebook(draft)">使用源</button>
                <button class="ghost" :disabled="loading" @click="emit('suggestDraft', draft)">问 Agent</button>
              </div>

              <small v-if="draft.suggestedDailyNote && draft.suggestedDailyNote !== draft.targetDailyNote" class="vcp-auto-hint">自动匹配建议：{{ draft.suggestedDailyNote }}</small>
              <textarea v-model="draft.memoryContent" rows="5" />

              <div class="vcp-draft-actions">
                <select v-model="draft.status">
                  <option value="pending">待同步</option>
                  <option value="review">需复核</option>
                  <option value="ignored">忽略</option>
                  <option value="failed">失败</option>
                  <option value="synced">已同步</option>
                </select>
                <button class="ghost" @click="emit('updateDraft', draft)">保存</button>
                <button class="primary" :disabled="draft.status === 'synced'" @click="emit('syncDraft', draft)">同步</button>
                <button class="ghost danger-link" @click="emit('deleteDraft', draft)">删除</button>
              </div>
            </article>
          </div>

          <p v-if="drafts.length === 0" class="empty">当前筛选下没有草稿。AI 做笔记后，VCP_AI_MEMORY 会先进入这里。</p>
        </section>
      </main>

      <aside class="vcp-file-dock">
        <section class="vcp-notebook-tools">
          <div class="vcp-panel-head">
            <div>
              <span>日记本维护</span>
              <strong>{{ selectedNotebookMeta?.name || '未选择' }}</strong>
            </div>
          </div>
          <div class="vcp-create-line">
            <input :value="newNotebook" placeholder="新日记本名称" @input="emit('update:newNotebook', ($event.target as HTMLInputElement).value)" />
            <button class="primary" @click="emit('createNotebook')">创建</button>
          </div>
          <div v-if="selectedNotebookMeta" class="vcp-delete-confirm">
            <button type="button" class="danger-button wide" data-testid="vcp-delete-notebook" @click.stop.prevent="requestDeleteNotebook">
              {{ notebookPendingDeleteName === selectedNotebookMeta.name ? `确认删除「${selectedNotebookMeta.name}」` : '删除当前日记本' }}
            </button>
            <button
              v-if="notebookPendingDeleteName === selectedNotebookMeta.name"
              type="button"
              class="ghost wide"
              @click.stop.prevent="notebookPendingDeleteName = ''"
            >
              取消删除
            </button>
          </div>
        </section>

        <section class="vcp-file-panel">
          <div class="vcp-panel-head">
            <div>
              <span>文件列表</span>
              <strong>{{ files.length }} 个文件</strong>
            </div>
            <label class="vcp-checkline">
              <input type="checkbox" :checked="allFilesSelected" @change="emit('toggleAllFiles', ($event.target as HTMLInputElement).checked)" />
              全选
            </label>
          </div>

          <div class="vcp-file-list">
            <article v-for="file in files" :key="file.filename" class="vcp-file" :class="{ active: selectedFile === file.filename }">
              <label class="vcp-file-check">
                <input type="checkbox" :checked="isFileSelected(file.filename)" @change="emit('toggleFileSelection', file.filename, ($event.target as HTMLInputElement).checked)" />
              </label>
              <button class="vcp-file-main" type="button" @click="emit('openFile', file)">
                <strong>{{ file.filename }}</strong>
                <span>{{ file.size }} B · {{ file.lastModified }}</span>
              </button>
            </article>
            <p v-if="selectedNotebook && files.length === 0" class="empty compact">这个日记本还没有 md/txt 文件。</p>
            <p v-if="!selectedNotebook" class="empty compact">先从左侧选择一个日记本。</p>
          </div>

          <div class="vcp-editor-actions">
            <button class="ghost" :disabled="!selectedFile" @click="emit('saveFile')">{{ selectedFile ? '保存文件' : '打开文件后保存' }}</button>
            <button class="ghost danger-link" :class="{ armed: confirmingDeleteFiles }" :disabled="fileDeleteTargetCount === 0" @click.stop.prevent="requestDeleteFiles">
              {{ fileDeleteLabel }}
            </button>
          </div>
          <textarea
            :value="fileContent"
            class="vcp-file-editor"
            placeholder="选择一个日记文件后可查看和编辑内容"
            @input="emit('update:fileContent', ($event.target as HTMLTextAreaElement).value)"
          />
        </section>
      </aside>
    </section>

    <div v-if="showNotebookBrowser" class="vcp-library-overlay" @click.self="showNotebookBrowser = false">
      <section class="vcp-library-modal" role="dialog" aria-modal="true" aria-label="全部日记本">
        <header class="vcp-library-modal-head">
          <div>
            <span>Notebook Atlas</span>
            <h2>{{ showingSearchResults ? '语义命中卡片' : '全部日记本地图' }}</h2>
            <p>按语义用途分组浏览。点击卡片选择源日记本，也可以直接设为转移目标。</p>
          </div>
          <button class="ghost" @click="showNotebookBrowser = false">关闭</button>
        </header>

        <div class="vcp-library-modal-search">
          <input
            :value="notebookQuery"
            placeholder="搜索日记本、主题、人物或文件内容"
            @input="emit('update:notebookQuery', ($event.target as HTMLInputElement).value)"
            @keydown.enter="emit('searchNotebooks')"
          />
          <button class="primary" :disabled="notebookSearchLoading" @click="emit('searchNotebooks')">{{ notebookSearchLoading ? '检索中' : '语义检索' }}</button>
        </div>

        <div class="vcp-library-groups">
          <section v-for="group in notebookGroups" :key="group.name" class="vcp-library-group">
            <div class="vcp-group-title">
              <strong>{{ group.name }}</strong>
              <small>{{ group.items.length }} 本</small>
            </div>
            <div class="vcp-library-card-grid">
              <article v-for="notebook in group.items" :key="notebook.name" class="vcp-library-card" :class="{ active: selectedNotebook === notebook.name }">
                <button class="vcp-library-card-main" @click="chooseNotebookFromBrowser(notebook.name)">
                  <span>{{ scoreLabel(notebook.score) }}</span>
                  <strong>{{ notebook.name }}</strong>
                  <small>{{ notebook.fileCount }} 文件 · {{ notebook.lastModified }}</small>
                  <p>{{ notebook.snippet }}</p>
                </button>
                <div>
                  <button class="text-button" @click="openNotebookDetail(notebook)">查看详情</button>
                  <button class="text-button" @click="useNotebookAsTransferTarget(notebook.name)">设为目标</button>
                </div>
              </article>
            </div>
          </section>
        </div>
      </section>
    </div>

    <aside v-if="activeNotebookDetail" class="vcp-detail-popover" role="dialog" aria-label="日记本详情">
      <button class="vcp-detail-close" @click="activeNotebookDetail = null">关闭</button>
      <span>命中详情</span>
      <strong>{{ activeNotebookDetail.name }}</strong>
      <small>{{ activeNotebookDetail.fileCount }} 文件 · {{ activeNotebookDetail.lastModified }}</small>
      <em>{{ activeNotebookDetail.reason }} · {{ scoreLabel(activeNotebookDetail.score) }}</em>
      <p>{{ activeNotebookDetail.snippet }}</p>
      <div>
        <button class="primary" @click="chooseNotebookFromBrowser(activeNotebookDetail.name); activeNotebookDetail = null">设为源日记本</button>
        <button class="ghost" @click="useNotebookAsTransferTarget(activeNotebookDetail.name); activeNotebookDetail = null">设为目标</button>
      </div>
    </aside>
  </section>
</template>
