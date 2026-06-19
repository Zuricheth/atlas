<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import AiSettings from './AiSettings.vue'
import DeepWikiPage from './components/DeepWikiPage.vue'
import FileTreeView from './components/FileTreeView.vue'
import NotebookCreatePanel from './components/NotebookCreatePanel.vue'
import NotebookTree from './components/NotebookTree.vue'
import NoteToolbar from './components/NoteToolbar.vue'
import VcpCenter from './components/VcpCenter.vue'
import { AEmpty, ASkeleton, ABadge, ASelect, ThemePicker, AiTracePanel, AiActivityDrawer, AiActivityButton } from './components/ui'
import NocturneArt from './components/art/NocturneArt.vue'
import NocturneHero from './components/art/NocturneHero.vue'
import { API_ORIGIN, apiUrl } from './lib/api'
import { request, requestRaw } from './lib/apiClient'
import { escapeHtml, markdownToHtml } from './lib/markdown'
import { parseAgentNoteContent, replaceDelimitedBlock, wrapDelimitedBlock } from './lib/noteBlocks'
import { useTheme } from './lib/theme'
import { useAuthStore } from './stores/auth'
import { useUiStore } from './stores/ui'
import { useTrashStore } from './stores/trash'
import { useNotebookStore } from './stores/notebook'
import { useAiActivityStore } from './stores/aiActivity'
import type {
  AiExchangeLog,
  AssetItem,
  AssetSort,
  DeleteMode,
  DeleteTarget,
  DeepWikiResult,
  FilePreview,
  FileTreeNode,
  FileTreeRow,
  FolderFailedImport,
  FolderImportLog,
  NotebookTreeRow,
  PlanTreeRow,
  SearchMode,
  SearchTreeRow,
  SidebarNotebookRow,
  SpaceBucket,
  SpaceSummary,
  Toast,
  TrashItem,
  VcpDraft,
  VcpFile,
  VcpNotebook,
  VcpNotebookSearchResult,
  VcpTransferResult,
} from './types'

const { themeId } = useTheme()
const authStore = useAuthStore()
const uiStore = useUiStore()

// 兼容:本文件内继续用 token/username/notify/loading/appView/toast 等名字,
// 实际指向 store,迁移视图后逐步删掉这些局部别名。
const { token, username, isAuthed, authMode, authForm } = storeToRefs(authStore)
const { toast, appView, showAiSettings, showMatchDetails } = storeToRefs(uiStore)
const trashStore = useTrashStore()
const trashItems = trashStore.items
const aiActivity = useAiActivityStore()
const notebookStore = useNotebookStore()
// notebook 域:可写状态走 storeToRefs(拿到 ref,可 .value 赋值),computed/表单直接读
const {
  notebooks,
  currentNotebookId,
  expandedSidebarKeys: expandedSidebarNotebookKeys,
  expandedTreeKeys: expandedNotebookTreeKeys,
  createMode: notebookCreateMode,
  editTarget: notebookEditTarget,
  current: currentNotebook,
  currentIsCollection: currentNotebookIsCollection,
  flatRows: notebookTreeRows,
  rootRows: rootNotebookRows,
  sidebarRows: sidebarNotebookRows,
} = storeToRefs(notebookStore)
// reactive 表单直接从 store 取(不走 storeToRefs,避免被包成 Ref),保持 .name 直接访问
const notebookForm = notebookStore.createForm
const notebookEditForm = notebookStore.editForm
// loading 是 reactive 对象,不进 storeToRefs(避免被包成 Ref),直接取
const loading = uiStore.loading
function notify(type: Toast['type'], text: string) {
  uiStore.notify(type, text)
}


const notes = ref<any[]>([])
const searchHits = ref<any[]>([])
const searchTree = ref<any[]>([])
const expandedSearchTreeKeys = ref<Set<string>>(new Set())
const allSearchTreeExpandedKeys = ref<string[]>([])
const showAllSearchHits = ref(false)
const showFullSearchTree = ref(false)
const matchDetails = ref<any[]>([])
const citations = ref<any[]>([])
const ragAiTrace = ref<any[]>([])
const currentNoteId = ref<number | null>(null)
const agents = ref<any[]>([])
const selectedKnowledgeAgentId = ref<number | ''>(Number(localStorage.getItem('atlas_selected_knowledge_agent_id') || localStorage.getItem('atlas_selected_agent_id') || '') || '')
const selectedDeepWikiAgentId = ref<number | ''>(Number(localStorage.getItem('atlas_selected_deepwiki_agent_id') || '') || '')
const selectedVcpAgentId = ref<number | ''>(Number(localStorage.getItem('atlas_selected_vcp_agent_id') || '') || '')
const agentNoteSource = ref<'attachments' | 'attachmentsAndNote'>((localStorage.getItem('atlas_agent_note_source') as 'attachments' | 'attachmentsAndNote') || 'attachments')
const searchMode = ref<SearchMode>('keyword')
const keyword = ref('资料导入后如何进入知识库')
const question = ref('请总结我导入的资料，并列出可以继续追问的方向。')
const answer = ref('')
const noteHistory = ref<Array<{ id: number; noteVersion: number; title: string; summaryExcerpt: string; createdAt: string }>>([])
const showNoteHistoryModal = ref(false)
const loadingNoteHistory = ref(false)
const rollingBack = ref(false)
const deepWikiReturnFrom = ref<{ notebookId: number; mode: string; focus: string } | null>(null)
const filePreview = ref<FilePreview | null>(null)
const diagramPreview = ref<{ title: string; html: string; source: string; zoom: number } | null>(null)
const assistantTab = ref<'search' | 'ask' | 'import'>('search')
const importMode = ref<'smart' | 'folder' | 'single' | 'paper'>('smart')
const documentImportAccept = '.txt,.md,.markdown,.html,.htm,.pdf,.doc,.docx,text/plain,text/markdown,text/html,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document'
const paperFileName = ref('')
const libraryFileName = ref('')
const libraryItems = ref<any[]>([])
const ingestResults = ref<any[]>([])
const inboxRequests = ref<any[]>([])
const selectedInboxRequestId = ref<number | null>(null)
const selectedInboxFileIds = ref<Set<number>>(new Set())
const inboxImportMode = ref<'ai' | 'manual'>('ai')
const inboxReviewNotebookId = ref<number | null>(null)
const inboxReviewAgentId = ref<number | ''>('')
const inboxCategoryPrefix = ref('外部投递')
const inboxAiPlan = ref<any | null>(null)
const inboxGenerateNotes = ref(false)
const folderPlanTree = ref<string[]>([])
const folderImportStatus = ref('')
const folderImportLogs = ref<FolderImportLog[]>([])
const folderAiLogs = ref<AiExchangeLog[]>([])
const folderAiTrace = ref<any[]>([])
const folderLogTab = ref<'import' | 'ai'>('import')
const pendingFolderFiles = ref<File[]>([])
const pendingFolderPlan = ref<any | null>(null)
const failedFolderImports = ref<FolderFailedImport[]>([])
const folderCorrection = ref('')
const noteFileLinks = ref<Array<{ label: string; url: string; contentType?: string; type?: string }>>([])
const deleteTarget = ref<DeleteTarget | null>(null)
const noteMode = ref<'overview' | 'read' | 'edit' | 'memory'>('overview')
const deepWikiMode = ref<'home' | 'topic' | 'map'>('home')
const deepWikiFocus = ref('')
const deepWikiResult = ref<DeepWikiResult | null>(null)
const deepWikiQuestion = ref('')
const deepWikiAnswer = ref('')
const deepWikiCitations = ref<any[]>([])
const vcpNotebooks = ref<VcpNotebook[]>([])
const vcpNotebookQuery = ref('')
const vcpNotebookResults = ref<VcpNotebookSearchResult[]>([])
const vcpDrafts = ref<VcpDraft[]>([])
const vcpFiles = ref<VcpFile[]>([])
const vcpStatus = ref('pending')
const vcpNewNotebook = ref('')
const selectedVcpNotebook = ref('')
const selectedVcpFile = ref('')
const vcpFileContent = ref('')
const vcpBatchSuggestion = ref('')
const vcpSelectedFileNames = ref<Set<string>>(new Set())
const vcpSelectedDraftIds = ref<Set<number>>(new Set())
const vcpTransferTarget = ref('')
const vcpTransferOverwrite = ref(false)
const vcpTransferMoveSynced = ref(true)
const vcpTransferDeleteSource = ref(false)
const vcpTransferLog = ref('')
const assetSummary = ref<SpaceSummary | null>(null)
const assetItems = ref<AssetItem[]>([])
const assetImageUrls = ref<Record<string, string>>({})
const assetScope = ref<'current' | 'all'>('current')
const assetTypeFilter = ref('全部')
const assetQuery = ref('')
const assetSort = ref<AssetSort>('updated-desc')
const selectedAssetKey = ref('')
const selectedAssetKeys = ref<Set<string>>(new Set())
const assetBulkAction = ref<'' | 'export' | 'trash'>('')
const expandedTreeKeys = ref<Set<string>>(new Set(['folder:笔记', 'folder:角色资产', 'folder:总纲资产', 'folder:工具脚本']))
const draggedNotebookId = ref<number | null>(null)
const humanNoteHost = ref<HTMLElement | null>(null)

const paperForm = reactive({
  title: '',
  summary: '',
  markdownContent: `# 论文阅读笔记

## 研究问题

这篇论文主要关注…

## 方法概述

作者采用…

## 关键结论

- 结论 1
- 结论 2

## 我的思考

这篇论文可以和我的研究方向结合在…`,
  file: null as File | null,
})

const libraryForm = reactive({
  title: '',
  category: '',
  file: null as File | null,
})

const defaultNote = {
  title: '收件箱/新建笔记',
  summary: '这是一个新的 Markdown 笔记。保存后会进入后台整理，稍后即可被搜索和问答引用。',
  content: `# 新建笔记

这里可以像 Obsidian 一样记录 Markdown 内容。

保存后，系统会在后台整理笔记内容，让它可以被搜索，也可以在提问时作为参考资料。`,
}

const noteForm = reactive({ ...defaultNote })
const folderPlanTreeRows = computed<PlanTreeRow[]>(() => buildPlanTreeRows(folderPlanTree.value))
const selectedInboxRequest = computed(() => inboxRequests.value.find((item) => item.id === selectedInboxRequestId.value) || null)
const inboxSelectableFiles = computed(() => (selectedInboxRequest.value?.files || []).filter((file: any) => file.status !== 'imported' && file.status !== 'skipped'))
const selectedInboxFiles = computed(() => inboxSelectableFiles.value.filter((file: any) => selectedInboxFileIds.value.has(file.id)))
const selectedInboxFileCount = computed(() => selectedInboxFiles.value.length)
const selectedVcpFileNames = computed(() => Array.from(vcpSelectedFileNames.value))
const selectedVcpDraftIds = computed(() => Array.from(vcpSelectedDraftIds.value))
const collectionNotebookOptions = computed(() => notebookTreeRows.value.filter((notebook) => notebookType(notebook) === 'collection'))
const notebookSelectOptions = computed(() => notebookTreeRows.value.map((notebook: any) => ({
  value: notebook.id,
  label: notebook.path || notebook.name || `#${notebook.id}`,
})))
const inboxPlanPreview = computed(() => {
  const request = selectedInboxRequest.value
  if (!request) return null
  if (inboxAiPlan.value) {
    return {
      notebookName: inboxAiPlan.value.notebookPath || inboxAiPlan.value.notebookName || '待创建/待匹配知识库',
      category: inboxAiPlan.value.categoryPrefix || '未分类',
      summary: inboxAiPlan.value.summary || 'AI 已根据 README 生成入库计划。',
      readmeFilename: inboxAiPlan.value.readmeFilename || '',
      planSource: inboxAiPlan.value.planSource || '',
      steps: inboxAiPlan.value.steps || [],
    }
  }
  const topFolder = inboxCommonTopFolder(request)
  const selectedNotebook = collectionNotebookOptions.value.find((notebook) => notebook.id === inboxReviewNotebookId.value)
  const suggestedNotebook = selectedNotebook || suggestInboxNotebook(request)
  const notebookName = selectedNotebook
    ? `${selectedNotebook.path || selectedNotebook.name}（你已指定）`
    : suggestedNotebook
      ? `${suggestedNotebook.path || suggestedNotebook.name}（Atlas 默认匹配）`
      : '自动创建一个外部投递资料库'
  const isImagePack = inboxLooksLikeImageStudio(request) || selectedInboxLooksImageOnly()
  const category = inboxImportMode.value === 'ai'
    ? [isImagePack ? '表情包' : '外部投递', isImagePack ? topFolder : request.sourceProject, isImagePack ? '' : topFolder].filter(Boolean).join('/')
    : [inboxCategoryPrefix.value || '外部投递', topFolder].filter(Boolean).join('/')
  return {
    notebookName,
    category: category || '未分类',
    summary: inboxImportMode.value === 'ai'
      ? 'AI 辅助只负责整包匹配一个知识库，不会再按每张图乱建多个知识库。文件会按包名进入库内分类；是否生成 Agent 笔记由下方开关决定。'
      : '手动模式会使用你选择的知识库和分类前缀，文件保持原始相对路径进入库内，不会新建其他知识库。',
    readmeFilename: '',
    planSource: 'local-preview',
    steps: [],
  }
})
const childCollections = computed(() => {
  if (!currentNotebook.value) return []
  const prefix = `${currentNotebook.value.path || currentNotebook.value.name} / `
  return notebookTreeRows.value.filter((node) => notebookType(node) === 'collection' && node.path.startsWith(prefix))
})
const childNotebookRows = computed(() => {
  if (!currentNotebook.value) return []
  return notebookTreeRows.value.filter((node) => {
    const prefix = `${currentNotebook.value.path || currentNotebook.value.name} / `
    return node.path.startsWith(prefix)
  })
})
const internalNotebookTreeRows = computed(() => notebookStore.internalRows())
const fileTreeRows = computed<FileTreeRow[]>(() => flattenFileTree(buildFileTree()))
const typeTreeRows = computed<FileTreeRow[]>(() => flattenFileTree(buildTypeTree()))
const notebookOverviewStats = computed(() => {
  const categories = new Set<string>()
  const types = new Set<string>()
  for (const item of libraryItems.value) {
    if (item.category) categories.add(item.category)
    types.add(fileTypeLabel(item))
  }
  if (notes.value.some((note) => !libraryItems.value.some((item) => item.noteId === note.id))) {
    types.add('Markdown 笔记')
  }
  return {
    notes: notes.value.length,
    files: libraryItems.value.length,
    categories: categories.size,
    types: types.size,
  }
})
const imageAssets = computed(() => libraryItems.value.filter((item) => isImageItem(item)))
const assetTypeOptions = computed(() => ['全部', ...(assetSummary.value?.fileTypes || []).map((item) => item.label)])
const sortedAssetItems = computed(() => {
  const [field, direction] = assetSort.value.split('-') as ['updated' | 'name' | 'type' | 'size', 'asc' | 'desc']
  const multiplier = direction === 'asc' ? 1 : -1
  return [...assetItems.value].sort((left, right) => {
    if (field === 'size') return ((left.fileSize || 0) - (right.fileSize || 0)) * multiplier
    if (field === 'updated') {
      const leftTime = Date.parse(left.updatedAt || left.createdAt || '') || 0
      const rightTime = Date.parse(right.updatedAt || right.createdAt || '') || 0
      return (leftTime - rightTime) * multiplier
    }
    const leftText = field === 'type' ? left.typeLabel : (left.originalFilename || left.title)
    const rightText = field === 'type' ? right.typeLabel : (right.originalFilename || right.title)
    return leftText.localeCompare(rightText, 'zh-CN', { numeric: true, sensitivity: 'base' }) * multiplier
  })
})
const selectedAsset = computed(() => assetItems.value.find((item) => item.key === selectedAssetKey.value) || sortedAssetItems.value[0] || null)
const assetImageItems = computed(() => sortedAssetItems.value.filter((item) => item.image))
const assetTotalSize = computed(() => assetItems.value.reduce((sum, item) => sum + (item.fileSize || 0), 0))
const selectedAssetItems = computed(() => sortedAssetItems.value.filter((item) => selectedAssetKeys.value.has(item.key)))
const selectedAssetSize = computed(() => selectedAssetItems.value.reduce((sum, item) => sum + (item.fileSize || 0), 0))
const allVisibleAssetsSelected = computed(() => assetItems.value.length > 0 && assetItems.value.every((item) => selectedAssetKeys.value.has(item.key)))
const folderPlanSummary = computed(() => {
  const plan = pendingFolderPlan.value
  const files = pendingFolderFiles.value
  if (!plan || files.length === 0) return ''
  const notebooks = new Set<string>((plan.files || []).map((file: any) => [file.domainName, file.projectName, file.collectionName || file.notebookName].filter(Boolean).join(' / ')).filter(Boolean))
  const uncertain = (plan.files || []).filter((file: any) => file.uncertain).length
  const images = files.filter((file) => isImageFile(file)).length
  const scripts = files.filter((file) => isScriptFile(file)).length
  return `待入库 ${files.length} 个文件，规划为 ${notebooks.size || 1} 个知识库、${plan.tree?.length || 0} 条分类路径；图片 ${images} 个按文件名归类，脚本 ${scripts} 个先按路径规划，仍有 ${uncertain} 个需要入库时复判。`
})
const currentFileLinks = computed(() => {
  const byUrl = new Map<string, { label: string; url: string; type: string }>()
  for (const link of noteFileLinks.value) {
    byUrl.set(link.url, {
      label: link.label,
      url: link.url,
      type: link.contentType || link.type || '',
    })
  }
  for (const link of extractFileLinks(noteForm.content)) {
    if (!byUrl.has(link.url)) byUrl.set(link.url, link)
  }
  return Array.from(byUrl.values())
})
const parsedAgentNote = computed(() => parseAgentNoteContent(noteForm.content))
const renderedNoteContent = computed(() => markdownToHtml(parsedAgentNote.value.body))
const renderedDeepWiki = computed(() => markdownToHtml(deepWikiResult.value?.markdown || ''))
const deepWikiSuggestedQuestions = computed(() => extractDeepWikiQuestions(deepWikiResult.value?.markdown || ''))
const deepWikiSourceLinks = computed(() => extractDeepWikiSources(deepWikiResult.value?.markdown || ''))
const previewKind = computed(() => {
  const type = (filePreview.value?.type || '').toLowerCase()
  const title = (filePreview.value?.title || '').toLowerCase()
  if (type.startsWith('image/') || /\.(png|jpe?g|webp|gif|bmp|svg)$/.test(title)) return 'image'
  if (type.includes('pdf') || title.endsWith('.pdf')) return 'pdf'
  if (type.includes('html') || /\.(html?|xhtml)$/.test(title)) return 'html'
  if (type.startsWith('text/') || /\.(txt|md|markdown|json|xml|csv|log|yml|yaml|toml|js|ts|py|java|css)$/.test(title)) return 'text'
  return 'binary'
})
const previewImageStyle = computed(() => {
  const preview = filePreview.value
  if (!preview) return {}
  if (preview.fit === 'actual') return { maxWidth: 'none', maxHeight: 'none', width: `${preview.zoom * 100}%` }
  if (preview.fit === 'width') return { width: `${preview.zoom * 100}%`, maxWidth: 'none', maxHeight: 'none' }
  return { maxWidth: `${preview.zoom * 100}%`, maxHeight: `${preview.zoom * 100}%` }
})
const diagramPreviewStyle = computed(() => {
  if (!diagramPreview.value) return {}
  return { zoom: diagramPreview.value.zoom }
})

function notebookType(notebook: any) {
  return notebook?.nodeType || 'collection'
}

function notebookTypeLabel(notebook: any) {
  const type = notebookType(notebook)
  if (type === 'domain') return '领域'
  if (type === 'project') return '项目'
  return '知识库'
}

function toggleSidebarNotebookNode(row: SidebarNotebookRow) {
  notebookStore.toggleSidebar(row)
}

async function openSidebarNotebookNode(row: SidebarNotebookRow) {
  if (row.expandable) toggleSidebarNotebookNode(row)
  await selectNotebook(row.id)
}

function toggleNotebookTreeNode(row: NotebookTreeRow) {
  notebookStore.toggleTreeNode(row)
}

async function openNotebookTreeNode(row: NotebookTreeRow) {
  if (row.expandable) {
    toggleNotebookTreeNode(row)
    return
  }
  await selectNotebook(row.id)
}

function buildPlanTreeRows(paths: string[]) {
  const seen = new Set<string>()
  const rows: PlanTreeRow[] = []
  for (const path of [...paths].sort((a, b) => a.localeCompare(b, 'zh-Hans-CN'))) {
    const parts = splitTreePath(path)
    let key = ''
    parts.forEach((part, index) => {
      key = key ? `${key}/${part}` : part
      if (seen.has(key)) return
      seen.add(key)
      rows.push({ key, name: part, level: index })
    })
  }
  return rows
}

function buildFileTree() {
  const roots: FileTreeNode[] = []
  const linkedNoteIds = new Set<number>()
  for (const item of libraryItems.value) {
    if (item.noteId) linkedNoteIds.add(item.noteId)
    const parts = fileCategoryParts(item)
    const parent = ensureTreePath(roots, parts.length ? parts : ['未分类'])
    parent.children.push({
      key: `library:${item.id}`,
      name: item.title || item.originalFilename || '未命名资料',
      type: 'library',
      children: [],
      item,
    })
  }

  const manualNotes = notes.value.filter((note) => !linkedNoteIds.has(note.id))
  if (manualNotes.length > 0) {
    for (const note of manualNotes) {
      const titleParts = splitTreePath(note.title || '未命名笔记')
      const noteName = titleParts.pop() || note.title || '未命名笔记'
      const noteRoot = ensureTreePath(roots, ['笔记', ...titleParts])
      noteRoot.children.push({
        key: `note:${note.id}`,
        name: noteName,
        type: 'note',
        children: [],
        note,
      })
    }
  }

  sortTree(roots)
  return roots
}

function buildTypeTree() {
  const roots: FileTreeNode[] = []
  const linkedNoteIds = new Set<number>()
  for (const item of libraryItems.value) {
    if (item.noteId) linkedNoteIds.add(item.noteId)
    const parent = ensureTreePath(roots, ['文件类型', fileTypeLabel(item)])
    parent.children.push({
      key: `type-library:${item.id}`,
      name: item.originalFilename || item.title || '未命名资料',
      type: 'library',
      children: [],
      item,
    })
  }

  const manualNotes = notes.value.filter((note) => !linkedNoteIds.has(note.id))
  if (manualNotes.length > 0) {
    const parent = ensureTreePath(roots, ['文件类型', 'Markdown 笔记'])
    for (const note of manualNotes) {
      parent.children.push({
        key: `type-note:${note.id}`,
        name: note.title || '未命名笔记',
        type: 'note',
        children: [],
        note,
      })
    }
  }
  sortTree(roots)
  return roots
}

function fileCategoryParts(item: any) {
  const parts = splitTreePath(item.category || '未分类')
  if (currentNotebookIsCollection.value) return parts
  const notebookPath = String(item.notebookPath || '')
  const currentPath = String(currentNotebook.value?.path || currentNotebook.value?.name || '')
  if (!notebookPath || !currentPath || !notebookPath.startsWith(currentPath)) return parts
  const relativePath = notebookPath === currentPath ? '' : notebookPath.slice(currentPath.length).replace(/^ *\/ */, '')
  const prefixParts = splitTreePath(relativePath)
  return [...prefixParts, ...parts]
}

function fileTypeLabel(item: any) {
  const ext = String(item.fileExt || '').toLowerCase()
  const contentType = String(item.contentType || '').toLowerCase()
  if (contentType.startsWith('image/') || ['png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp', 'svg'].includes(ext)) return '图片'
  if (ext === 'pdf') return 'PDF'
  if (['html', 'htm'].includes(ext)) return 'HTML'
  if (['md', 'markdown'].includes(ext)) return 'Markdown'
  if (ext === 'txt') return 'TXT'
  if (['py', 'js', 'ts', 'tsx', 'jsx', 'java', 'go', 'rs', 'cpp', 'c', 'cs', 'sh', 'bat', 'ps1'].includes(ext)) return '脚本/代码'
  if (contentType) return contentType
  return ext ? ext.toUpperCase() : '未知类型'
}

function isImageItem(item: any) {
  return fileTypeLabel(item) === '图片'
}

function splitTreePath(path: string) {
  return path
    .replace(/\\/g, '/')
    .split('/')
    .map((part) => part.trim())
    .filter(Boolean)
}

function ensureTreePath(roots: FileTreeNode[], parts: string[]) {
  let children = roots
  let current: FileTreeNode | null = null
  let keyPath = ''
  for (const part of parts) {
    keyPath = keyPath ? `${keyPath}/${part}` : part
    let node = children.find((item) => item.type === 'folder' && item.name === part)
    if (!node) {
      node = { key: `folder:${keyPath}`, name: part, type: 'folder', children: [] }
      children.push(node)
    }
    current = node
    children = node.children
  }
  return current || { key: 'folder:root', name: 'root', type: 'folder', children: roots }
}

function sortTree(nodes: FileTreeNode[]) {
  nodes.sort((a, b) => {
    if (a.type === 'folder' && b.type !== 'folder') return -1
    if (a.type !== 'folder' && b.type === 'folder') return 1
    return a.name.localeCompare(b.name, 'zh-Hans-CN')
  })
  for (const node of nodes) sortTree(node.children)
}

function flattenFileTree(nodes: FileTreeNode[], level = 0): FileTreeRow[] {
  const rows: FileTreeRow[] = []
  for (const node of nodes) {
    const expandable = node.type === 'folder' && node.children.length > 0
    const expanded = !expandable || expandedTreeKeys.value.has(node.key)
    rows.push({ ...node, level, expandable, expanded })
    if (expandable && expanded) rows.push(...flattenFileTree(node.children, level + 1))
  }
  return rows
}

const searchTreeRows = computed<SearchTreeRow[]>(() => flattenSearchTree(searchTree.value))
const visibleSearchHits = computed(() => showAllSearchHits.value ? searchHits.value : searchHits.value.slice(0, 5))
const visibleSearchTreeRows = computed(() => showFullSearchTree.value ? searchTreeRows.value : searchTreeRows.value.slice(0, 5))

function flattenSearchTree(nodes: any[], level = 0): SearchTreeRow[] {
  const rows: SearchTreeRow[] = []
  for (const node of nodes || []) {
    const expandable = Array.isArray(node.children) && node.children.length > 0
    const expanded = !expandable || expandedSearchTreeKeys.value.has(node.key)
    rows.push({ ...node, level, expandable, expanded })
    if (expandable && expanded) rows.push(...flattenSearchTree(node.children, level + 1))
  }
  return rows
}

function toggleSearchTreeNode(row: SearchTreeRow) {
  if (!row.expandable) return
  const next = new Set(expandedSearchTreeKeys.value)
  if (next.has(row.key)) next.delete(row.key)
  else next.add(row.key)
  expandedSearchTreeKeys.value = next
}

async function openSearchTreeNode(row: SearchTreeRow) {
  if (row.type === 'folder') {
    toggleSearchTreeNode(row)
    return
  }
  if (row.noteId) {
    await openHit({ noteId: row.noteId })
  }
}

function toggleTreeNode(row: FileTreeRow) {
  if (!row.expandable) return
  const next = new Set(expandedTreeKeys.value)
  if (next.has(row.key)) next.delete(row.key)
  else next.add(row.key)
  expandedTreeKeys.value = next
}

function openTreeNode(row: FileTreeRow) {
  if (row.type === 'folder') {
    toggleTreeNode(row)
    return
  }
  if (row.type === 'library' && row.item) {
    void openLibraryItem(row.item)
    return
  }
  if (row.type === 'note' && row.note) editNote(row.note)
}

function searchSourceName(source?: string) {
  if (source === 'keyword') return '精确查找'
  if (source === 'semantic-exact') return '语义 · 内容命中'
  if (source === 'semantic') return '智能推荐'
  if (source === 'hybrid-strong') return '综合 · 强命中'
  if (source === 'hybrid-exact') return '综合 · 关键词/路径命中'
  if (source === 'hybrid-keyword') return '综合 · 内容命中'
  if (source === 'hybrid-semantic') return '综合 · 语义补充'
  if (source === 'hybrid') return '综合搜索'
  return '搜索结果'
}

function sleep(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

function elapsedText(startedAt: number) {
  return `${Math.max(0.1, (performance.now() - startedAt) / 1000).toFixed(1)}s`
}

function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let value = bytes
  let index = 0
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024
    index += 1
  }
  return `${value >= 10 || index === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`
}

function extensionOfFile(file: File) {
  const path = ((file as any).webkitRelativePath || file.name || '').toLowerCase()
  const dot = path.lastIndexOf('.')
  return dot >= 0 ? path.slice(dot + 1) : ''
}

function isImageFile(file: File) {
  const ext = extensionOfFile(file)
  return file.type.startsWith('image/') || ['png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp', 'svg'].includes(ext)
}

function isScriptFile(file: File) {
  const ext = extensionOfFile(file)
  return [
    'py', 'js', 'ts', 'tsx', 'jsx', 'java', 'kt', 'go', 'rs', 'cpp', 'c', 'h',
    'cs', 'php', 'rb', 'lua', 'sh', 'bat', 'cmd', 'ps1', 'sql', 'yml', 'yaml',
    'json', 'toml', 'xml',
  ].includes(ext)
}

async function folderPlanInputs(files: File[]) {
  return files.map((file) => {
    const path = (file as any).webkitRelativePath || file.name
    return {
      path,
      size: file.size,
      type: file.type || '',
      text: '',
    }
  })
}

function addFolderLog(text: string, type: FolderImportLog['type'] = 'info') {
  const time = new Date().toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
  folderImportLogs.value = [{ time, type, text }, ...folderImportLogs.value].slice(0, 300)
}

function compactLogText(text: string, max = 220) {
  const clean = text.replace(/\s+/g, ' ').trim()
  return clean.length > max ? `${clean.slice(0, max)}...` : clean
}

function addAiLog(title: string, request: string, response: string) {
  const time = new Date().toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
  folderAiLogs.value = [{
    time,
    title,
    request: compactLogText(request, 1200),
    response: compactLogText(response, 1200),
    open: false,
  }, ...folderAiLogs.value].slice(0, 80)
}

async function requestWithRetry<T>(
  path: string,
  options: RequestInit,
  label: string,
  retries = 2,
  timeoutMs = 180_000,
): Promise<T> {
  let lastError: unknown = null
  for (let attempt = 1; attempt <= retries + 1; attempt++) {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), timeoutMs)
    const startedAt = performance.now()
    try {
      addFolderLog(`${label}：第 ${attempt}/${retries + 1} 次开始`)
      const requestPreview = options.body instanceof FormData
        ? Array.from(options.body.entries()).map(([key, value]) => `${key}: ${value instanceof File ? `${value.name} (${formatBytes(value.size)})` : compactLogText(String(value), 500)}`).join('\n')
        : compactLogText(String(options.body || ''), 1200)
      const data = await request<T>(path, { ...options, signal: controller.signal })
      window.clearTimeout(timeout)
      addFolderLog(`${label}：成功，用时 ${elapsedText(startedAt)}`, 'success')
      if (path.includes('/folder-plan')) {
        addAiLog(label, requestPreview, JSON.stringify(data))
      }
      return data
    } catch (error) {
      window.clearTimeout(timeout)
      lastError = error
      const message = error instanceof Error && error.name === 'AbortError'
        ? `超过 ${Math.round(timeoutMs / 1000)} 秒未响应`
        : error instanceof Error ? compactLogText(error.message, 180) : '请求失败'
      addFolderLog(`${label}：失败，用时 ${elapsedText(startedAt)}，${message}`, 'error')
      if (attempt <= retries) {
        await sleep(900 * attempt)
        addFolderLog(`${label}：准备重试`)
      }
    }
  }
  throw lastError instanceof Error ? lastError : new Error(`${label}失败`)
}

function extractFileLinks(markdown = '') {
  const links: Array<{ label: string; url: string; type: string }> = []
  const pattern = /\[([^\]]+)]\((\/api\/(?:library|papers)\/\d+\/file)\)/g
  let match: RegExpExecArray | null
  while ((match = pattern.exec(markdown)) !== null) {
    const url = match[2]
    if (links.some((link) => link.url === url)) continue
    links.push({
      label: match[1] || '打开原文件',
      url,
      type: url.includes('/papers/') ? 'application/pdf' : '',
    })
  }
  return links
}

function updateVcpMemoryDraft(value: string) {
  noteForm.content = replaceDelimitedBlock(noteForm.content, 'VCP_AI_MEMORY', value)
}

function handleVcpMemoryInput(event: Event) {
  updateVcpMemoryDraft((event.target as HTMLTextAreaElement).value)
}

function updateBaseNoteDraft(value: string) {
  const parsed = parsedAgentNote.value
  if (!parsed.hasDualBlocks) {
    noteForm.content = value
    return
  }
  noteForm.content = canonicalAgentNoteContent(value, parsed.humanBlocks, parsed.memoryBlocks)
}

function handleBaseNoteInput(event: Event) {
  updateBaseNoteDraft((event.target as HTMLTextAreaElement).value)
}

function appendAgentNoteContent(content: string) {
  const parsed = parseAgentNoteContent(content)
  const addition = [
    ...parsed.humanBlocks.map((block) => wrapDelimitedBlock('ATLAS_HUMAN_NOTE', block)),
    ...parsed.memoryBlocks.map((block) => wrapDelimitedBlock('VCP_AI_MEMORY', block)),
  ].join('\n\n').trim()
  if (!addition) return false
  const base = noteForm.content.trimEnd()
  noteForm.content = base ? `${base}\n\n${addition}` : addition
  return true
}

function canonicalAgentNoteContent(body: string, humanBlocks: string[], memoryBlocks: string[]) {
  const blocks = [
    ...humanBlocks.map((block) => wrapDelimitedBlock('ATLAS_HUMAN_NOTE', block)),
    ...memoryBlocks.map((block) => wrapDelimitedBlock('VCP_AI_MEMORY', block)),
  ]
  return [body.trimEnd(), ...blocks].filter(Boolean).join('\n\n')
}

function renderAgentHumanBlock(block: string) {
  const trimmed = block.trim()
  if (!trimmed) return ''
  return looksLikeHtml(trimmed) ? trimmed : markdownToHtml(trimmed)
}

function looksLikeHtml(text: string) {
  return /^<\s*(?:!doctype|html|head|body|style|script|div|section|article|aside|main|header|footer|nav|p|h[1-6]|ul|ol|li|table|thead|tbody|tr|td|th|pre|code|blockquote|figure|figcaption|img|svg|details|summary|span|button|a|canvas|iframe)\b/i.test(text)
}

function goVcpMemoryDrafts() {
  void openVcpCenter()
}

function renderHumanNoteBubble() {
  const host = humanNoteHost.value
  if (!host || noteMode.value !== 'read' || !parsedAgentNote.value.hasDualBlocks) return
  const root = host.shadowRoot || host.attachShadow({ mode: 'open' })
  root.innerHTML = ''
  const parsed = parsedAgentNote.value
  const originalHtml = parsed.body ? markdownToHtml(parsed.body) : ''
  const agentBlocks = parsed.humanBlocks
    .map((block) => renderAgentHumanBlock(block))
    .filter(Boolean)
    .map((html, index) => `
      <section class="atlas-agent-human-block" data-agent-block="${index + 1}">
        ${html}
      </section>
    `)
    .join('')
  const shell = `
    <style>
      :host { display: block; width: 100%; }
      .atlas-note-body { line-height: 1.85; color: #1f2937; word-break: break-word; }
      .atlas-note-body h1,
      .atlas-note-body h2,
      .atlas-note-body h3 { color: #0f172a; line-height: 1.25; margin: 22px 0 12px; }
      .atlas-note-body h1 { font-size: 28px; }
      .atlas-note-body h2 { font-size: 22px; }
      .atlas-note-body h3 { font-size: 18px; }
      .atlas-note-body p { margin: 10px 0; }
      .atlas-note-body ul,
      .atlas-note-body ol { padding-left: 24px; margin: 10px 0; }
      .atlas-note-body blockquote { margin: 14px 0; padding: 10px 14px; border-left: 3px solid #2563eb; background: #eff6ff; color: #1e3a8a; }
      .atlas-note-body code { padding: 2px 5px; border-radius: 6px; background: #f1f5f9; color: #334155; }
      .atlas-note-body pre { overflow: auto; padding: 14px; border-radius: 12px; background: #0f172a; color: #e2e8f0; }
      .atlas-agent-human-block { margin-top: 26px; }
      a { cursor: pointer; }
      img { max-width: 100%; height: auto; }
      button { cursor: pointer; }
    </style>
    ${originalHtml ? `<section class="atlas-note-body">${originalHtml}</section>` : ''}
    ${agentBlocks}
  `
  const range = document.createRange()
  range.selectNode(host)
  const fragment = range.createContextualFragment(shell)
  root.appendChild(fragment)
  renderMermaidBlocks(root)
  root.removeEventListener('click', handleHumanNoteClick as EventListener)
  root.addEventListener('click', handleHumanNoteClick as EventListener)
}

function renderMermaidBlocks(root: ShadowRoot) {
  const blocks = Array.from(root.querySelectorAll('pre.mermaid'))
  blocks.forEach((block, index) => {
    const source = block.textContent || ''
    const rendered = renderSimpleMermaidGraph(source)
    if (!rendered.html) return
    const wrapper = document.createElement('div')
    wrapper.className = rendered.complex ? 'atlas-mermaid-card' : 'atlas-mermaid-graph'
    const title = rendered.title || `图表 ${index + 1}`
    if (rendered.complex) {
      wrapper.innerHTML = `
        <div style="display:grid; gap:10px; padding:14px; border:1px solid #334155; border-radius:10px; background:#1e293b; color:#e2e8f0;">
          <div style="display:flex; justify-content:space-between; align-items:center; gap:12px;">
            <div style="display:grid; gap:4px;">
              <strong style="font-size:15px;">${escapeHtml(title)}</strong>
              <span style="color:#94a3b8; font-size:12px;">图表较大，已切换为大屏阅读模式 · ${rendered.nodeCount} 个节点 / ${rendered.edgeCount} 条关系</span>
            </div>
            <button type="button" style="min-height:34px; border:1px solid #60a5fa; border-radius:8px; padding:0 12px; background:#2563eb; color:white; font-weight:800; cursor:pointer;">大屏查看</button>
          </div>
        </div>
      `
      wrapper.querySelector('button')?.addEventListener('click', () => openDiagramPreview(title, rendered.html, source))
    } else {
      wrapper.innerHTML = rendered.html
    }
    block.replaceWith(wrapper)
  })
}

function renderSimpleMermaidGraph(source: string) {
  const trimmed = source.trim()
  if (!/^graph\s+(TD|TB|LR)\b/i.test(trimmed)) return { html: '', complex: false, title: '', nodeCount: 0, edgeCount: 0 }
  const direction = /^graph\s+(LR)\b/i.test(trimmed) ? 'LR' : 'TD'
  const nodes = new Map<string, string>()
  const edges: Array<{ from: string; to: string }> = []
  const nodePattern = /^\s*([A-Za-z0-9_]+)(?:\[(.*?)])?\s*-->\s*([A-Za-z0-9_]+)(?:\[(.*?)])?/gm
  let match: RegExpExecArray | null
  while ((match = nodePattern.exec(trimmed)) !== null) {
    const from = match[1]
    const to = match[3]
    nodes.set(from, (match[2] || nodes.get(from) || from).trim())
    nodes.set(to, (match[4] || nodes.get(to) || to).trim())
    edges.push({ from, to })
  }
  if (!edges.length) return { html: '', complex: false, title: '', nodeCount: 0, edgeCount: 0 }

  const incoming = new Map<string, number>()
  for (const id of nodes.keys()) incoming.set(id, 0)
  for (const edge of edges) incoming.set(edge.to, (incoming.get(edge.to) || 0) + 1)
  const roots = Array.from(nodes.keys()).filter((id) => (incoming.get(id) || 0) === 0)
  const levels = new Map<string, number>()
  const queue = roots.length ? roots.map((id) => ({ id, level: 0 })) : [{ id: Array.from(nodes.keys())[0], level: 0 }]
  while (queue.length) {
    const current = queue.shift()!
    if ((levels.get(current.id) ?? -1) >= current.level) continue
    levels.set(current.id, current.level)
    for (const edge of edges.filter((item) => item.from === current.id)) {
      queue.push({ id: edge.to, level: current.level + 1 })
    }
  }
  for (const id of nodes.keys()) {
    if (!levels.has(id)) levels.set(id, 0)
  }

  const grouped = new Map<number, string[]>()
  for (const id of nodes.keys()) {
    const level = levels.get(id) || 0
    if (!grouped.has(level)) grouped.set(level, [])
    grouped.get(level)!.push(id)
  }

  const nodeWidth = 260
  const nodeHeight = 66
  const gapX = 72
  const gapY = 48
  const maxCount = Math.max(...Array.from(grouped.values()).map((items) => items.length))
  const maxLevel = Math.max(...Array.from(grouped.keys()))
  const width = direction === 'LR'
    ? (maxLevel + 1) * (nodeWidth + gapX) + 32
    : maxCount * (nodeWidth + gapX) + 32
  const height = direction === 'LR'
    ? maxCount * (nodeHeight + gapY) + 32
    : (maxLevel + 1) * (nodeHeight + gapY) + 32
  const positions = new Map<string, { x: number; y: number }>()
  for (const [level, ids] of grouped.entries()) {
    const span = ids.length * nodeWidth + (ids.length - 1) * gapX
    ids.forEach((id, index) => {
      if (direction === 'LR') {
        positions.set(id, {
          x: 16 + level * (nodeWidth + gapX),
          y: 16 + index * (nodeHeight + gapY),
        })
      } else {
        positions.set(id, {
          x: Math.max(16, (width - span) / 2) + index * (nodeWidth + gapX),
          y: 16 + level * (nodeHeight + gapY),
        })
      }
    })
  }

  const edgeSvg = edges.map((edge) => {
    const from = positions.get(edge.from)
    const to = positions.get(edge.to)
    if (!from || !to) return ''
    const x1 = direction === 'LR' ? from.x + nodeWidth : from.x + nodeWidth / 2
    const y1 = direction === 'LR' ? from.y + nodeHeight / 2 : from.y + nodeHeight
    const x2 = direction === 'LR' ? to.x : to.x + nodeWidth / 2
    const y2 = direction === 'LR' ? to.y + nodeHeight / 2 : to.y
    const mid = direction === 'LR' ? (x1 + x2) / 2 : (y1 + y2) / 2
    const path = direction === 'LR'
      ? `M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`
      : `M ${x1} ${y1} C ${x1} ${mid}, ${x2} ${mid}, ${x2} ${y2}`
    return `<path d="${path}" fill="none" stroke="#38bdf8" stroke-width="1.6" marker-end="url(#arrow)" opacity="0.8"/>`
  }).join('')

  const nodeSvg = Array.from(nodes.entries()).map(([id, label]) => {
    const pos = positions.get(id)!
    const lines = wrapSvgText(label, 18)
    const startY = pos.y + nodeHeight / 2 - ((lines.length - 1) * 9)
    const text = lines.map((line, index) => (
      `<text x="${pos.x + nodeWidth / 2}" y="${startY + index * 18}" text-anchor="middle" dominant-baseline="middle" fill="#e2e8f0" font-size="15" font-weight="700">${escapeHtml(line)}</text>`
    )).join('')
    return `
      <g>
        <rect x="${pos.x}" y="${pos.y}" width="${nodeWidth}" height="${nodeHeight}" rx="10" fill="#0f172a" stroke="#475569" stroke-width="1.2"/>
        ${text}
      </g>
    `
  }).join('')

  const html = `
    <div style="overflow:auto; border:1px solid #334155; border-radius:8px; background:#1e293b; padding:16px; max-width:100%;">
      <svg viewBox="0 0 ${width} ${height}" width="${width}" height="${height}" style="display:block; max-width:none;" role="img">
        <defs>
          <marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="strokeWidth">
            <path d="M 0 0 L 8 4 L 0 8 z" fill="#38bdf8"></path>
          </marker>
        </defs>
        ${edgeSvg}
        ${nodeSvg}
      </svg>
    </div>
  `
  const title = nodes.get(roots[0]) || 'Mermaid 图表'
  return {
    html,
    title,
    complex: nodes.size > 10 || edges.length > 12 || width > 1100,
    nodeCount: nodes.size,
    edgeCount: edges.length,
  }
}

function wrapSvgText(text: string, maxChars: number) {
  const clean = text.trim()
  if (clean.length <= maxChars) return [clean]
  const lines: string[] = []
  let current = ''
  for (const char of clean) {
    current += char
    if (current.length >= maxChars) {
      lines.push(current)
      current = ''
    }
  }
  if (current) lines.push(current)
  return lines.slice(0, 3)
}

function openDiagramPreview(title: string, html: string, source: string) {
  diagramPreview.value = { title, html, source, zoom: 1 }
}

function closeDiagramPreview() {
  diagramPreview.value = null
}

function zoomDiagramPreview(delta: number) {
  if (!diagramPreview.value) return
  diagramPreview.value.zoom = Math.min(2.5, Math.max(0.5, Number((diagramPreview.value.zoom + delta).toFixed(2))))
}

function handleHumanNoteClick(event: MouseEvent) {
  const target = event.target as HTMLElement | null
  const button = target?.closest?.('button')
  if (button) {
    const onClick = button.getAttribute('onclick') || ''
    const match = onClick.match(/input\((['"])([\s\S]*?)\1\)/)
    if (match) {
      event.preventDefault()
      event.stopPropagation()
      question.value = match[2]
      assistantTab.value = 'ask'
      notify('info', '已把按钮内容放入问答框')
      return
    }
  }

  const anchor = target?.closest?.('a')
  if (!anchor) return
  const href = anchor.getAttribute('href') || ''
  if (!href) return
  if (href.startsWith('/api/') || href.startsWith(`${API_ORIGIN}/api/`)) {
    event.preventDefault()
    event.stopPropagation()
    void openFileLink({ label: anchor.textContent?.trim() || '链接', url: href, type: '' })
  }
}

function extractDeepWikiQuestions(markdown: string) {
  const lines = markdown.split(/\r?\n/)
  const questions: string[] = []
  let inSection = false
  for (const rawLine of lines) {
    const line = rawLine.trim()
    if (/^#{2,3}\s+/.test(line)) {
      inSection = /可继续追问|继续追问|追问/.test(line)
      continue
    }
    if (!inSection) continue
    const match = line.match(/^(?:[-*]|\d+\.)\s*(.+)$/)
    if (!match) continue
    const questionText = match[1].replace(/^\[[^\]]+]\([^)]+\)\s*/, '').trim()
    if (questionText && !questions.includes(questionText)) questions.push(questionText)
    if (questions.length >= 8) break
  }
  return questions
}

function extractDeepWikiSources(markdown: string) {
  const sources: Array<{ kind: 'note' | 'library'; id: number; label: string }> = []
  const pattern = /\[([^\]]+)]\(([^)]+)\)/gi
  let match: RegExpExecArray | null
  while ((match = pattern.exec(markdown)) !== null) {
    collectDeepWikiSourceRefs(`${match[1]} ${match[2]}`, sources)
    if (sources.length >= 16) break
  }
  collectDeepWikiSourceRefs(markdown, sources)
  return sources
}

function collectDeepWikiSourceRefs(text: string, sources: Array<{ kind: 'note' | 'library'; id: number; label: string }>) {
  const notePattern = /(?:atlas:\/\/note\/|note\s*#?\s*)(\d+)/gi
  const filePattern = /(?:atlas:\/\/(?:library|file)\/|(?:file|library)\s*#?\s*)(\d+)/gi
  let match: RegExpExecArray | null
  while ((match = notePattern.exec(text)) !== null) {
    const id = Number(match[1])
    if (id && !sources.some((source) => source.kind === 'note' && source.id === id)) {
      sources.push({ kind: 'note', id, label: `Note #${id}` })
    }
    if (sources.length >= 16) return
  }
  while ((match = filePattern.exec(text)) !== null) {
    const id = Number(match[1])
    if (id && !sources.some((source) => source.kind === 'library' && source.id === id)) {
      sources.push({ kind: 'library', id, label: `File #${id}` })
    }
    if (sources.length >= 16) return
  }
}

async function submitAuth() {
  try {
    const isLogin = authStore.authMode === 'login'
    await authStore.loginOrRegister(authStore.authMode)
    notify('success', isLogin ? '登录成功' : '注册成功')
    await loadWorkspace()
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '认证失败')
  }
}

function logout() {
  // 业务状态由 auth store 的 onLogout 钩子统一重置(见下方注册),
  // 这里只触发登出本身 + UI 归位。
  authStore.logout()
  currentNotebookId.value = null
  currentNoteId.value = null
  appView.value = 'workspace'
  deepWikiResult.value = null
  answer.value = ''
}

// 登出时重置所有业务状态(避免 auth store 反向依赖业务 store)
authStore.onLogout(() => {
  notebooks.value = []
  notes.value = []
  libraryItems.value = []
  searchHits.value = []
  searchTree.value = []
  expandedSearchTreeKeys.value = new Set()
  matchDetails.value = []
  citations.value = []
  noteFileLinks.value = []
  agents.value = []
  inboxRequests.value = []
  selectedInboxRequestId.value = null
  selectedInboxFileIds.value = new Set()
  selectedKnowledgeAgentId.value = ''
  selectedDeepWikiAgentId.value = ''
  selectedVcpAgentId.value = ''
  trashStore.clear()
  aiActivity.clear()
})

async function loadWorkspace() {
  const [loadedNotebooks, loadedAgents] = await Promise.all([
    request<any[]>('/notebooks'),
    request<any[]>('/admin/ai/agents').catch(() => []),
  ])
  notebooks.value = loadedNotebooks
  agents.value = loadedAgents
  ensureSceneAgentDefaults()
  if (notebooks.value.length > 0) await selectNotebook(rootNotebookRows.value[0]?.id || notebooks.value[0].id)
  await loadInboxRequests()
}

function ensureSceneAgentDefaults() {
  if (agents.value.length === 0) return
  if (!selectedKnowledgeAgentId.value) {
    selectedKnowledgeAgentId.value = pickAgent(['知识库', 'Knowledge', '默认'], true)
  }
  if (!selectedDeepWikiAgentId.value) {
    selectedDeepWikiAgentId.value = pickAgent(['DeepWiki', 'Wiki'])
  }
  if (!selectedVcpAgentId.value) {
    selectedVcpAgentId.value = pickAgent(['VCP Sync', 'VCP', '同步'])
  }
}

function pickAgent(keywords: string[], allowDefault = false) {
  const lowered = keywords.map((keyword) => keyword.toLowerCase())
  const matched = agents.value.find((agent) => lowered.some((keyword) => String(agent.name || '').toLowerCase().includes(keyword)))
  if (matched) return matched.id
  const defaultAgent = agents.value.find((agent) => agent.isDefault === 1)
  if (allowDefault && defaultAgent) return defaultAgent.id
  return agents.value[0]?.id || ''
}

async function createNotebook() {
  if (!notebookForm.name.trim()) {
    notify('error', '请填写知识库名称')
    return null
  }

  loading.creatingNotebook = true
  try {
    const parentId = notebookCreateMode.value === 'child' ? currentNotebookId.value : null
    const notebook = await request<any>('/notebooks', {
      method: 'POST',
      body: JSON.stringify({ ...notebookForm, parentId, nodeType: 'collection' }),
    })
    notebooks.value = await request<any[]>('/notebooks')
    if (parentId) {
      expandedSidebarNotebookKeys.value = new Set(expandedSidebarNotebookKeys.value).add(`notebook:${parentId}`)
      expandedNotebookTreeKeys.value = new Set(expandedNotebookTreeKeys.value).add(`notebook:${parentId}`)
    }
    await selectNotebook(notebook.id)
    notify('success', parentId ? '子知识库创建成功' : '知识库创建成功')
    return notebook
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '知识库创建失败')
    return null
  } finally {
    loading.creatingNotebook = false
  }
}

async function selectNotebook(notebookId: number) {
  currentNotebookId.value = notebookId
  const query = `notebookId=${notebookId}&recursive=true`
  const [loadedNotes, loadedItems] = await Promise.all([
    request<any[]>(`/notes?${query}`),
    request<any[]>(`/library?${query}`),
  ])
  notes.value = loadedNotes
  libraryItems.value = loadedItems
  showNotebookOverview()
}

function showNotebookOverview() {
  currentNoteId.value = null
  noteFileLinks.value = []
  noteMode.value = 'overview'
}

function newNote() {
  if (!currentNotebookIsCollection.value) {
    notify('error', '请选择一个知识库节点再新建笔记')
    return
  }
  currentNoteId.value = null
  Object.assign(noteForm, defaultNote)
  noteMode.value = 'edit'
}

function editNote(note: any) {
  currentNoteId.value = note.id
  noteForm.title = note.title
  noteForm.summary = note.summary || ''
  noteForm.content = note.content
  noteFileLinks.value = note.fileLinks || []
  noteMode.value = 'read'
}

async function saveNote() {
  if (!currentNotebookId.value) {
    notify('error', '请先创建或选择知识库')
    return null
  }
  if (!currentNotebookIsCollection.value) {
    notify('error', '领域/项目只是组织节点，请选择知识库保存笔记')
    return null
  }
  if (!noteForm.title.trim() || !noteForm.content.trim()) {
    notify('error', '标题和正文不能为空')
    return null
  }

  loading.saving = true
  try {
    if (parsedAgentNote.value.hasDualBlocks) {
      noteForm.content = canonicalAgentNoteContent(
        parsedAgentNote.value.body,
        parsedAgentNote.value.humanBlocks,
        parsedAgentNote.value.memoryBlocks,
      )
    } else if (parsedAgentNote.value.body.trim() !== noteForm.content.trim()) {
      noteForm.content = parsedAgentNote.value.body
    }
    const payload = {
      notebookId: currentNotebookId.value,
      title: noteForm.title,
      summary: noteForm.summary || null,
      content: noteForm.content,
    }
    const saved = await request<any>(currentNoteId.value ? `/notes/${currentNoteId.value}` : '/notes', {
      method: currentNoteId.value ? 'PUT' : 'POST',
      body: JSON.stringify(payload),
    })
    notes.value = await request<any[]>(`/notes?notebookId=${saved.notebookId}`)
    editNote(saved)
    notify('success', '笔记已保存，后台会整理内容')
    return saved
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '保存失败')
    return null
  } finally {
    loading.saving = false
  }
}

async function generateAgentNote() {
  if (!currentNoteId.value) {
    notify('error', '请先保存笔记，再让 Agent 读取原文件做笔记')
    return
  }
  if (!selectedKnowledgeAgentId.value) {
    notify('error', '请先在 AI 设置里创建并选择 Agent')
    return
  }
  loading.agentNote = true
  try {
    noteMode.value = 'read'
    const data = await request<{ agentId: number; content: string }>(`/notes/${currentNoteId.value}/agent-note`, {
      method: 'POST',
      body: JSON.stringify({
        agentId: selectedKnowledgeAgentId.value,
        includeCurrentContent: agentNoteSource.value === 'attachmentsAndNote',
      }),
    })
    if (!data.content.trim()) throw new Error('Agent 没有返回内容')
    if (!appendAgentNoteContent(data.content)) {
      throw new Error('Agent 没有按双轨协议返回 ATLAS_HUMAN_NOTE / VCP_AI_MEMORY，已阻止写入，避免污染当前笔记')
    }
    await nextTick()
    renderHumanNoteBubble()
    notify('success', 'Agent 已把新笔记追加到底部，请检查后保存')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : 'AI 做笔记失败')
  } finally {
    loading.agentNote = false
  }
}

function openDeepWiki() {
  appView.value = 'deepwiki'
  void loadDeepWikiLatest()
}

function openInbox() {
  appView.value = 'inbox'
  void loadInboxRequests()
}

async function openVcpCenter() {
  appView.value = 'vcp'
  await loadVcpCenter()
}

async function loadVcpCenter() {
  loading.vcp = true
  try {
    const [notebooksData, draftsData] = await Promise.all([
      request<VcpNotebook[]>('/vcp/notebooks'),
      request<VcpDraft[]>(`/vcp/drafts${vcpStatus.value ? `?status=${encodeURIComponent(vcpStatus.value)}` : ''}`),
    ])
    vcpNotebooks.value = notebooksData
    vcpDrafts.value = draftsData
    await searchVcpNotebooks()
    if (!selectedVcpNotebook.value && vcpNotebooks.value.length > 0) {
      selectedVcpNotebook.value = vcpNotebooks.value[0].name
      await loadVcpFiles()
    }
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '加载 VCP 同步中心失败')
  } finally {
    loading.vcp = false
  }
}

async function searchVcpNotebooks() {
  loading.vcpNotebookSearch = true
  try {
    const params = new URLSearchParams()
    if (vcpNotebookQuery.value.trim()) params.set('q', vcpNotebookQuery.value.trim())
    params.set('limit', '12')
    vcpNotebookResults.value = await request<VcpNotebookSearchResult[]>(`/vcp/notebooks/search?${params.toString()}`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '搜索 VCP 日记本失败')
  } finally {
    loading.vcpNotebookSearch = false
  }
}

async function selectVcpNotebook(name: string) {
  selectedVcpNotebook.value = name
  vcpNotebookQuery.value = name
  await loadVcpFiles()
}

async function createVcpNotebook() {
  const name = vcpNewNotebook.value.trim()
  if (!name) {
    notify('error', '请填写日记本名称')
    return
  }
  try {
    const notebook = await request<VcpNotebook>('/vcp/notebooks', {
      method: 'POST',
      body: JSON.stringify({ name }),
    })
    vcpNewNotebook.value = ''
    selectedVcpNotebook.value = notebook.name
    vcpNotebookQuery.value = notebook.name
    await loadVcpCenter()
    notify('success', 'VCP 日记本已创建')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '创建日记本失败')
  }
}

async function deleteVcpNotebook(notebook: VcpNotebook) {
  const force = (notebook.fileCount || 0) > 0
  try {
    await request<void>(`/vcp/notebooks/${encodeURIComponent(notebook.name)}?force=${force}`, { method: 'DELETE' })
    if (selectedVcpNotebook.value === notebook.name) {
      selectedVcpNotebook.value = ''
      selectedVcpFile.value = ''
      vcpFileContent.value = ''
      vcpFiles.value = []
    }
    await loadVcpCenter()
    notify('success', 'VCP 日记本已删除')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '删除 VCP 日记本失败')
  }
}

async function loadVcpFiles() {
  if (!selectedVcpNotebook.value) return
  try {
    vcpFiles.value = await request<VcpFile[]>(`/vcp/notebooks/${encodeURIComponent(selectedVcpNotebook.value)}/files`)
    selectedVcpFile.value = ''
    vcpFileContent.value = ''
    vcpSelectedFileNames.value = new Set()
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '读取日记本文件失败')
  }
}

async function openVcpFile(file: VcpFile) {
  try {
    const data = await request<{ notebook: string; filename: string; content: string }>(
      `/vcp/files?notebook=${encodeURIComponent(file.notebook)}&file=${encodeURIComponent(file.filename)}`
    )
    selectedVcpNotebook.value = data.notebook
    selectedVcpFile.value = data.filename
    vcpFileContent.value = data.content
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '打开日记文件失败')
  }
}

async function saveVcpFile() {
  if (!selectedVcpNotebook.value || !selectedVcpFile.value) {
    notify('error', '请先选择日记文件')
    return
  }
  try {
    await request(`/vcp/files?notebook=${encodeURIComponent(selectedVcpNotebook.value)}&file=${encodeURIComponent(selectedVcpFile.value)}`, {
      method: 'PUT',
      body: JSON.stringify({ content: vcpFileContent.value }),
    })
    await loadVcpFiles()
    notify('success', '日记文件已保存')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '保存日记文件失败')
  }
}

async function updateVcpDraft(draft: VcpDraft) {
  try {
    const updated = await request<VcpDraft>(`/vcp/drafts/${draft.id}`, {
      method: 'PUT',
      body: JSON.stringify({
        targetDailyNote: draft.targetDailyNote,
        memoryContent: draft.memoryContent,
        status: draft.status,
      }),
    })
    vcpDrafts.value = vcpDrafts.value.map((item) => (item.id === updated.id ? updated : item))
    notify('success', '草稿已更新')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '更新草稿失败')
  }
}

async function syncVcpDraft(draft: VcpDraft) {
  try {
    const data = await request<{ draftId: number; notebook: string; filename: string; path: string }>(`/vcp/drafts/${draft.id}/sync`, {
      method: 'POST',
      body: JSON.stringify({ targetDailyNote: draft.targetDailyNote }),
    })
    selectedVcpNotebook.value = data.notebook
    await loadVcpCenter()
    await loadVcpFiles()
    notify('success', `已同步到 ${data.notebook}`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '同步草稿失败')
  }
}

async function suggestVcpDraftTarget(draft: VcpDraft) {
  loading.vcp = true
  try {
    const data = await request<{ draft: VcpDraft; suggestion: string }>(`/vcp/drafts/${draft.id}/suggest-target`, {
      method: 'POST',
      body: JSON.stringify({ agentId: selectedVcpAgentId.value || null }),
    })
    vcpDrafts.value = vcpDrafts.value.map((item) => (item.id === data.draft.id ? data.draft : item))
    vcpBatchSuggestion.value = data.suggestion
    notify('success', `Agent 建议同步到 ${data.draft.targetDailyNote || data.draft.suggestedDailyNote}`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '生成目标日记本建议失败')
  } finally {
    loading.vcp = false
  }
}

async function askVcpSyncAgent() {
  const ids = vcpDrafts.value.filter((draft) => draft.status === 'pending' || draft.status === 'review').map((draft) => draft.id)
  if (ids.length === 0) {
    notify('error', '当前没有待分析草稿')
    return
  }
  loading.vcp = true
  try {
    const data = await request<{ suggestion: string }>('/vcp/drafts/batch-suggest', {
      method: 'POST',
      body: JSON.stringify({ agentId: selectedVcpAgentId.value || null, draftIds: ids }),
    })
    vcpBatchSuggestion.value = data.suggestion
    notify('success', '同步建议已生成')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '生成同步建议失败')
  } finally {
    loading.vcp = false
  }
}

async function ensureAtlasSystemAgents() {
  try {
    agents.value = await request<any[]>('/admin/ai/agents/atlas-defaults', { method: 'POST' })
    ensureSceneAgentDefaults()
    notify('success', 'DeepWiki Agent 和 VCP Sync Agent 已就绪')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '创建系统 Agent 失败')
  }
}

async function loadDeepWikiLatest() {
  if (!currentNotebookId.value) {
    deepWikiResult.value = null
    return
  }
  try {
    const query = new URLSearchParams({
      notebookId: String(currentNotebookId.value),
      mode: deepWikiMode.value,
      focus: deepWikiFocus.value.trim(),
    })
    deepWikiResult.value = await request<DeepWikiResult | null>(`/deepwiki/latest?${query.toString()}`)
  } catch (error) {
    deepWikiResult.value = null
    notify('error', error instanceof Error ? error.message : '读取 DeepWiki 保存页失败')
  }
}

function updateDeepWikiFocus(value: string) {
  deepWikiFocus.value = value
  deepWikiResult.value = null
}

async function deleteVcpDraft(draft: VcpDraft) {
  try {
    await request<void>(`/vcp/drafts/${draft.id}`, { method: 'DELETE' })
    vcpDrafts.value = vcpDrafts.value.filter((item) => item.id !== draft.id)
    const next = new Set(vcpSelectedDraftIds.value)
    next.delete(draft.id)
    vcpSelectedDraftIds.value = next
    notify('success', '草稿请求已删除')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '删除草稿失败')
  }
}

async function deleteVcpFile() {
  const notebook = selectedVcpNotebook.value
  const filenames = vcpSelectedFileNames.value.size > 0 ? Array.from(vcpSelectedFileNames.value) : selectedVcpFile.value ? [selectedVcpFile.value] : []
  if (!notebook || filenames.length === 0) {
    notify('error', '请先选择日记文件')
    return
  }
  try {
    await Promise.all(
      filenames.map((filename) =>
        request<void>(`/vcp/files?notebook=${encodeURIComponent(notebook)}&file=${encodeURIComponent(filename)}`, {
          method: 'DELETE',
        })
      )
    )
    if (filenames.includes(selectedVcpFile.value)) {
      selectedVcpFile.value = ''
      vcpFileContent.value = ''
    }
    const deleted = new Set(filenames)
    vcpFiles.value = vcpFiles.value.filter((item) => !deleted.has(item.filename))
    const next = new Set(vcpSelectedFileNames.value)
    filenames.forEach((filename) => next.delete(filename))
    vcpSelectedFileNames.value = next
    notify('success', filenames.length > 1 ? `已删除 ${filenames.length} 个 VCP 日记文件` : 'VCP 日记文件已删除')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '删除 VCP 文件失败')
  }
}

function toggleVcpFileSelection(filename: string, checked: boolean) {
  const next = new Set(vcpSelectedFileNames.value)
  if (checked) next.add(filename)
  else next.delete(filename)
  vcpSelectedFileNames.value = next
}

function toggleAllVcpFiles(checked: boolean) {
  vcpSelectedFileNames.value = checked ? new Set(vcpFiles.value.map((file) => file.filename)) : new Set()
}

function toggleVcpDraftSelection(id: number, checked: boolean) {
  const next = new Set(vcpSelectedDraftIds.value)
  if (checked) next.add(id)
  else next.delete(id)
  vcpSelectedDraftIds.value = next
}

function toggleAllVcpDrafts(checked: boolean) {
  vcpSelectedDraftIds.value = checked ? new Set(vcpDrafts.value.map((draft) => draft.id)) : new Set()
}

function summarizeVcpTransfer(result: VcpTransferResult) {
  const messages = result.messages?.slice(0, 5).join('\n') || ''
  vcpTransferLog.value = `已转移 ${result.moved} 项到「${result.targetNotebook}」${result.skipped ? `，跳过 ${result.skipped} 项` : ''}${messages ? `\n${messages}` : ''}`
}

async function transferSelectedVcpFiles() {
  const filenames = selectedVcpFileNames.value.length > 0
    ? selectedVcpFileNames.value
    : selectedVcpFile.value
    ? [selectedVcpFile.value]
    : []
  if (!selectedVcpNotebook.value || filenames.length === 0) {
    notify('error', '请先选择要转移的文件')
    return
  }
  if (!vcpTransferTarget.value.trim()) {
    notify('error', '请填写目标日记本')
    return
  }
  loading.vcpTransfer = true
  try {
    const result = await request<VcpTransferResult>('/vcp/files/transfer', {
      method: 'POST',
      body: JSON.stringify({
        sourceNotebook: selectedVcpNotebook.value,
        targetNotebook: vcpTransferTarget.value.trim(),
        filenames,
        overwrite: vcpTransferOverwrite.value,
      }),
    })
    summarizeVcpTransfer(result)
    selectedVcpNotebook.value = result.targetNotebook
    selectedVcpFile.value = ''
    vcpFileContent.value = ''
    vcpSelectedFileNames.value = new Set()
    await loadVcpCenter()
    await loadVcpFiles()
    notify('success', `已转移 ${result.moved} 个文件`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '转移文件失败')
  } finally {
    loading.vcpTransfer = false
  }
}

async function transferVcpNotebookContents() {
  if (!selectedVcpNotebook.value) {
    notify('error', '请先选择源日记本')
    return
  }
  if (!vcpTransferTarget.value.trim()) {
    notify('error', '请填写目标日记本')
    return
  }
  if (!window.confirm(`确定把「${selectedVcpNotebook.value}」的全部文件转移到「${vcpTransferTarget.value.trim()}」吗？`)) return
  loading.vcpTransfer = true
  try {
    const result = await request<VcpTransferResult>('/vcp/notebooks/transfer-contents', {
      method: 'POST',
      body: JSON.stringify({
        sourceNotebook: selectedVcpNotebook.value,
        targetNotebook: vcpTransferTarget.value.trim(),
        overwrite: vcpTransferOverwrite.value,
        deleteSourceWhenEmpty: vcpTransferDeleteSource.value,
      }),
    })
    summarizeVcpTransfer(result)
    selectedVcpNotebook.value = result.targetNotebook
    selectedVcpFile.value = ''
    vcpFileContent.value = ''
    vcpSelectedFileNames.value = new Set()
    await loadVcpCenter()
    await loadVcpFiles()
    notify('success', `已转移 ${result.moved} 个文件`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '转移日记本内容失败')
  } finally {
    loading.vcpTransfer = false
  }
}

async function transferSelectedVcpDrafts() {
  if (selectedVcpDraftIds.value.length === 0) {
    notify('error', '请先选择要转移的记忆')
    return
  }
  if (!vcpTransferTarget.value.trim()) {
    notify('error', '请填写目标日记本')
    return
  }
  loading.vcpTransfer = true
  try {
    const result = await request<VcpTransferResult>('/vcp/drafts/transfer', {
      method: 'POST',
      body: JSON.stringify({
        targetNotebook: vcpTransferTarget.value.trim(),
        draftIds: selectedVcpDraftIds.value,
        moveSyncedFiles: vcpTransferMoveSynced.value,
        overwrite: vcpTransferOverwrite.value,
      }),
    })
    summarizeVcpTransfer(result)
    vcpSelectedDraftIds.value = new Set()
    selectedVcpNotebook.value = result.targetNotebook
    await loadVcpCenter()
    await loadVcpFiles()
    notify('success', `已转移 ${result.moved} 条记忆`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '转移记忆失败')
  } finally {
    loading.vcpTransfer = false
  }
}

async function openTrash() {
  appView.value = 'trash'
  await loadTrash()
}

async function openAssets() {
  appView.value = 'assets'
  await loadAssetSummary()
}

function selectNotebookForAssets(value: string | number) {
  currentNotebookId.value = Number(value) || null
  assetScope.value = 'current'
  void loadAssetSummary()
}

function selectNotebookForDeepWiki(value: string | number) {
  currentNotebookId.value = Number(value) || null
  deepWikiResult.value = null
  void loadDeepWikiLatest()
}

function returnToDeepWiki() {
  const ctx = deepWikiReturnFrom.value
  deepWikiReturnFrom.value = null
  if (!ctx) {
    appView.value = 'deepwiki'
    return
  }
  currentNotebookId.value = ctx.notebookId
  deepWikiMode.value = (ctx.mode as any) || 'home'
  deepWikiFocus.value = ctx.focus || ''
  appView.value = 'deepwiki'
  // 用缓存的 deepWikiResult 直接展示,不强制重新拉取;若被清空则拉一次
  if (!deepWikiResult.value) void loadDeepWikiLatest()
}

async function loadAssetSummary() {
  loading.assets = true
  try {
    assetSummary.value = await request<SpaceSummary>('/assets/summary')
    await loadAssetItems()
    await loadAssetImagePreviews()
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '加载资产统计失败')
  } finally {
    loading.assets = false
  }
}

async function loadAssetItems() {
  const params = new URLSearchParams()
  if (assetScope.value === 'current' && currentNotebookId.value) params.set('notebookId', String(currentNotebookId.value))
  if (assetTypeFilter.value && assetTypeFilter.value !== '全部') params.set('type', assetTypeFilter.value)
  if (assetQuery.value.trim()) params.set('q', assetQuery.value.trim())
  params.set('limit', '360')
  assetItems.value = await request<AssetItem[]>(`/assets/items?${params.toString()}`)
  const visibleKeys = new Set(assetItems.value.map((item) => item.key))
  selectedAssetKeys.value = new Set([...selectedAssetKeys.value].filter((key) => visibleKeys.has(key)))
  if (selectedAssetKey.value && !assetItems.value.some((item) => item.key === selectedAssetKey.value)) {
    selectedAssetKey.value = ''
  }
}

async function refreshAssetView() {
  loading.assets = true
  try {
    await loadAssetItems()
    await loadAssetImagePreviews()
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '刷新资产列表失败')
  } finally {
    loading.assets = false
  }
}

function selectAsset(item: AssetItem) {
  selectedAssetKey.value = item.key
}

function toggleAssetSelection(item: AssetItem) {
  const next = new Set(selectedAssetKeys.value)
  if (next.has(item.key)) next.delete(item.key)
  else next.add(item.key)
  selectedAssetKeys.value = next
  selectedAssetKey.value = item.key
}

function toggleAllAssets(event: Event) {
  const checked = (event.target as HTMLInputElement).checked
  selectedAssetKeys.value = checked ? new Set(assetItems.value.map((item) => item.key)) : new Set()
}

function clearAssetSelection() {
  selectedAssetKeys.value = new Set()
}

async function exportSelectedAssets() {
  const keys = selectedAssetItems.value.map((item) => item.key)
  if (keys.length === 0) return
  assetBulkAction.value = 'export'
  try {
    const response = await requestRaw('/assets/export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ keys }),
    })
    const blob = await response.blob()
    const downloadUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = filenameFromDisposition(response.headers.get('Content-Disposition')) || 'Atlas选中资产.zip'
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(downloadUrl)
    notify('success', `已开始导出 ${keys.length} 个资产`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '导出选中资产失败')
  } finally {
    assetBulkAction.value = ''
  }
}

async function trashSelectedAssets() {
  const keys = selectedAssetItems.value.map((item) => item.key)
  if (keys.length === 0) return
  if (!window.confirm(`确定将选中的 ${keys.length} 个资产移入回收站吗？关联笔记会保留。`)) return
  assetBulkAction.value = 'trash'
  try {
    const result = await request<{ count: number }>('/assets/trash', {
      method: 'POST',
      body: JSON.stringify({ keys }),
    })
    clearAssetSelection()
    await loadAssetSummary()
    notify('success', `已将 ${result.count} 个资产移入回收站`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '移入回收站失败')
  } finally {
    assetBulkAction.value = ''
  }
}

async function openAssetFile(item?: AssetItem | null) {
  const target = item || selectedAsset.value
  if (!target) return
  await openFileLink({
    label: target.originalFilename || target.title || '原文件',
    url: target.fileUrl,
    type: target.contentType || '',
  })
}

async function jumpToAssetNote(item?: AssetItem | null) {
  const target = item || selectedAsset.value
  if (!target?.noteId) {
    notify('info', '这个资产没有关联笔记')
    return
  }
  const note = await request<any>(`/notes/${target.noteId}`)
  if (note.notebookId !== currentNotebookId.value) {
    await selectNotebook(note.notebookId)
  }
  editNote(note)
  appView.value = 'workspace'
}

async function loadAssetImagePreviews() {
  clearAssetImagePreviews()
  const imageItems = assetImageItems.value.slice(0, 36)
  const entries = await Promise.all(imageItems.map(async (item) => {
    try {
      const response = await requestRaw(item.fileUrl)
      const blob = await response.blob()
      return [item.key, URL.createObjectURL(blob)] as const
    } catch {
      return null
    }
  }))
  const next: Record<string, string> = {}
  for (const entry of entries) {
    if (entry) next[entry[0]] = entry[1]
  }
  assetImageUrls.value = next
}

function clearAssetImagePreviews() {
  for (const url of Object.values(assetImageUrls.value)) {
    URL.revokeObjectURL(url)
  }
  assetImageUrls.value = {}
}

async function loadTrash() {
  await trashStore.load()
}

async function restoreTrashItem(item: TrashItem) {
  await trashStore.restore(item, async (notebookId) => {
    if (currentNotebookId.value === notebookId) await selectNotebook(notebookId)
  })
}

async function purgeTrashItem(item: TrashItem) {
  await trashStore.purge(item)
}

async function purgeAllTrash() {
  await trashStore.purgeAll()
}

async function purgeExpiredTrash() {
  await trashStore.purgeExpired()
}

function trashKindLabel(kind: TrashItem['kind']) {
  if (kind === 'note') return '笔记'
  if (kind === 'paper') return 'PDF 附件'
  return '资料文件'
}

function assetBucketPercent(bucket: SpaceBucket) {
  const total = assetSummary.value?.totalBytes || 0
  if (!total || !bucket.bytes) return 2
  return Math.min(100, Math.max(3, Math.round((bucket.bytes / total) * 100)))
}

async function generateTopicFromEntry(focus: string) {
  deepWikiMode.value = 'topic'
  deepWikiFocus.value = focus
  await generateDeepWiki()
}

async function generateDeepWiki() {
  if (!currentNotebookId.value) {
    notify('error', '请先选择一个知识库')
    return
  }
  if (!selectedDeepWikiAgentId.value) {
    notify('error', '请先选择 DeepWiki Agent')
    return
  }
  if (deepWikiMode.value === 'topic' && !deepWikiFocus.value.trim()) {
    notify('error', '请填写专题标题')
    return
  }  loading.deepwiki = true
  try {
    deepWikiResult.value = await request<DeepWikiResult>('/deepwiki/generate', {
      method: 'POST',
      body: JSON.stringify({
        notebookId: currentNotebookId.value,
        agentId: selectedDeepWikiAgentId.value,
        mode: deepWikiMode.value,
        focus: deepWikiFocus.value.trim(),
      }),
    })
    aiActivity.push((deepWikiResult.value as any)?.aiTrace)
    notify('success', 'DeepWiki 页面已生成')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : 'DeepWiki 生成失败')
  } finally {
    loading.deepwiki = false
  }
}

async function askDeepWiki(questionText?: string) {
  const text = (questionText || deepWikiQuestion.value).trim()
  if (!text) {
    notify('error', '请输入要追问的问题')
    return
  }
  deepWikiQuestion.value = text
  loading.asking = true
  try {
    const data = await request<any>('/chat/rag', {
      method: 'POST',
      body: JSON.stringify({ question: text, topK: 8 }),
    })
    deepWikiAnswer.value = data.answer
    deepWikiCitations.value = data.citations || []
    notify('success', `DeepWiki 已召回 ${deepWikiCitations.value.length} 个依据片段`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : 'DeepWiki 追问失败')
  } finally {
    loading.asking = false
  }
}

function useDeepWikiQuestion(questionText: string) {
  void askDeepWiki(questionText)
}

async function deleteNote() {
  if (!currentNoteId.value) return
  deleteTarget.value = {
    kind: 'note',
    id: currentNoteId.value,
    title: noteForm.title || '当前笔记',
  }
}

async function deleteAsset(item: any) {
  await executeDelete({
    kind: 'library',
    id: item.id,
    title: item.title || item.originalFilename || '当前资料',
    noteId: item.noteId,
  }, 'all')
}

function deleteNotebook() {
  if (!currentNotebook.value) return
  deleteTarget.value = {
    kind: 'notebook',
    id: currentNotebook.value.id,
    title: `知识库：${currentNotebook.value.name}`,
  }
}

function renameNotebook(notebook: any) {
  notebookEditTarget.value = notebook
  notebookEditForm.name = notebook.name || ''
  notebookEditForm.description = notebook.description || ''
}

async function saveNotebookEdit() {
  const target = notebookEditTarget.value
  if (!target) return
  const name = notebookEditForm.name.trim()
  if (!name) {
    notify('error', '请填写知识库名称')
    return
  }
  try {
    const updated = await request<any>(`/notebooks/${target.id}`, {
      method: 'PUT',
      body: JSON.stringify({
        name,
        description: notebookEditForm.description.trim(),
      }),
    })
    notebooks.value = await request<any[]>('/notebooks')
    notebookEditTarget.value = null
    if (currentNotebookId.value === updated.id) await selectNotebook(updated.id)
    notify('success', '知识库已更新')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '更新失败')
  }
}

function startNotebookDrag(notebook: any) {
  draggedNotebookId.value = notebook.id
}

async function dropNotebook(target: any) {
  const sourceId = draggedNotebookId.value
  draggedNotebookId.value = null
  if (!sourceId || sourceId === target.id) return
  const source = notebooks.value.find((item) => item.id === sourceId)
  if (!source) return
  try {
    await request<any>(`/notebooks/${sourceId}/merge`, {
      method: 'POST',
      body: JSON.stringify({ targetNotebookId: target.id }),
    })
    notebooks.value = await request<any[]>('/notebooks')
    const selectedId = notebookType(target) === 'collection' ? target.id : sourceId
    await selectNotebook(selectedId)
    notify('success', `已把“${source.name}”移动到“${target.name}”下面`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '合并失败')
  }
}

async function confirmDelete(mode: DeleteMode) {
  const target = deleteTarget.value
  if (!target) return
  await executeDelete(target, mode)
}

async function executeDelete(target: DeleteTarget, mode: DeleteMode) {
  try {
    if (target.kind === 'notebook') {
      await request<void>(`/notebooks/${target.id}`, { method: 'DELETE' })
      removeDeletedFromUi(target, mode)
      notify('success', '知识库已删除')
      deleteTarget.value = null
      return
    }

    const endpoint = target.kind === 'note' ? `/notes/${target.id}` : `/library/${target.id}`
    await request<void>(`${endpoint}?mode=${mode}`, { method: 'DELETE' })
    if (target.kind === 'library') closeFilePreview()
    removeDeletedFromUi(target, mode)
    notify('success', deleteModeText(mode))
    deleteTarget.value = null
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '删除失败')
  }
}

function removeDeletedFromUi(target: DeleteTarget, mode: DeleteMode) {
  if (target.kind === 'notebook') {
    notebooks.value = notebooks.value.filter((notebook) => notebook.id !== target.id)
    if (currentNotebookId.value === target.id) {
      const next = notebooks.value[0]
      currentNotebookId.value = null
      notes.value = []
      libraryItems.value = []
      noteFileLinks.value = []
      closeFilePreview()
      newNote()
      if (next) void selectNotebook(rootNotebookRows.value[0]?.id || next.id)
    }
    return
  }

  if (target.kind === 'library') {
    libraryItems.value = libraryItems.value.filter((item) => item.id !== target.id)
    if ((mode === 'all' || mode === 'note') && target.noteId) {
      notes.value = notes.value.filter((note) => note.id !== target.noteId)
      if (currentNoteId.value === target.noteId) newNote()
    }
    return
  }

  if (mode === 'files' || mode === 'all') {
    libraryItems.value = libraryItems.value.filter((item) => item.noteId !== target.id)
  }
  if (mode === 'note' || mode === 'all') {
    notes.value = notes.value.filter((note) => note.id !== target.id)
    if (currentNoteId.value === target.id) newNote()
  }
}

function deleteModeText(mode: string) {
  if (mode === 'note') return '已删除笔记，原文件记录保留'
  if (mode === 'files') return '已删除资料/原文件记录，笔记保留'
  return '已删除笔记和原文件/资料记录'
}

function handlePaperFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] || null
  paperForm.file = file
  paperFileName.value = file?.name || ''
  if (file && !paperForm.title.trim()) {
    paperForm.title = file.name.replace(/\.[^.]+$/i, '')
  }
}

function isPdfFile(file: File | null) {
  if (!file) return false
  return file.type.toLowerCase().includes('pdf') || file.name.toLowerCase().endsWith('.pdf')
}

function handleLibraryFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] || null
  libraryForm.file = file
  libraryFileName.value = file?.name || ''
  if (file && !libraryForm.title.trim()) {
    libraryForm.title = file.name.replace(/\.[^.]+$/i, '')
  }
}

async function autoIngestFiles(files: FileList | File[]) {
  const fileList = Array.from(files)
  if (fileList.length === 0) return
  loading.autoIngesting = true
  try {
    for (const file of fileList) {
      const formData = new FormData()
      formData.append('file', file)
      const data = await request<any>('/library/auto-import', {
        method: 'POST',
        body: formData,
      })
      ingestResults.value = [data, ...ingestResults.value].slice(0, 20)
      aiActivity.push(data?.aiTrace)
      notebooks.value = await request<any[]>('/notebooks')
      await selectNotebook(data.notebookId)
      const note = await request<any>(`/notes/${data.noteId}`)
      editNote(note)
    }
    notify('success', `已自动入库 ${fileList.length} 个文件，PDF/Word 已先转换为可检索文本`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '自动入库失败')
  } finally {
    loading.autoIngesting = false
  }
}

function isDocumentConversionFile(file: File | null) {
  if (!file) return false
  const name = file.name.toLowerCase()
  const type = file.type.toLowerCase()
  return /\.(pdf|docx?|pptx?)$/.test(name)
    || type.includes('pdf')
    || type.includes('word')
    || type.includes('officedocument')
}

function handleAutoIngestInput(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.files) void autoIngestFiles(input.files)
  input.value = ''
}

function handleDrop(event: DragEvent) {
  event.preventDefault()
  if (event.dataTransfer?.files) void autoIngestFiles(event.dataTransfer.files)
}

async function loadInboxRequests() {
  if (!token.value) return
  loading.inbox = true
  try {
    const requests = await request<any[]>('/inbox/requests?status=pending')
    inboxRequests.value = requests
    if (!selectedInboxRequestId.value && requests.length > 0) {
      selectInboxRequest(requests[0])
    } else if (selectedInboxRequestId.value && !requests.some((item) => item.id === selectedInboxRequestId.value)) {
      selectInboxRequest(requests[0] || null)
    }
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '加载投递箱失败')
  } finally {
    loading.inbox = false
  }
}

function selectInboxRequest(item: any | null) {
  selectedInboxRequestId.value = item?.id || null
  inboxAiPlan.value = null
  inboxGenerateNotes.value = false
  const selectable = (item?.files || []).filter((file: any) => file.status !== 'imported' && file.status !== 'skipped')
  selectedInboxFileIds.value = new Set(selectable.map((file: any) => file.id))
  const suggested = item ? suggestInboxNotebook(item) : null
  inboxReviewNotebookId.value = suggested?.id
    || (currentNotebookIsCollection.value && currentNotebookId.value ? currentNotebookId.value : null)
    || collectionNotebookOptions.value[0]?.id
    || null
  inboxReviewAgentId.value = selectedKnowledgeAgentId.value || ''
  inboxCategoryPrefix.value = item?.sourceProject ? `外部投递/${item.sourceProject}` : '外部投递'
}

function inboxLooksLikeImageStudio(item: any) {
  const text = `${item?.sourceProject || ''} ${item?.title || ''} ${item?.description || ''}`.toLowerCase()
  return text.includes('ai image studio') || text.includes('表情包') || text.includes('贴纸') || text.includes('生图')
}

function inboxFileIsImage(file: any) {
  const type = String(file?.contentType || '').toLowerCase()
  const name = String(file?.originalFilename || file?.relativePath || '').toLowerCase()
  return type.startsWith('image/') || /\.(png|jpe?g|webp|gif|bmp)$/.test(name)
}

function inboxFileIsMarkdown(file: any) {
  const name = String(file?.originalFilename || file?.relativePath || '').toLowerCase()
  return /\.(md|markdown)$/.test(name)
}

function selectedInboxLooksImageOnly() {
  const files = selectedInboxFiles.value.filter((file: any) => !inboxFileIsMarkdown(file))
  return files.length > 0 && files.every(inboxFileIsImage)
}

function suggestInboxNotebook(item: any) {
  const options = collectionNotebookOptions.value
  if (inboxLooksLikeImageStudio(item)) {
    return findNotebookOption(['AI图像生成', 'AI 图像生成', '表情包素材', '图片收藏'], ['图像', '图片', '素材', '表情'])
  }
  const files = (item?.files || []).filter((file: any) => file.status !== 'imported' && file.status !== 'skipped')
  const payloadFiles = files.filter((file: any) => !inboxFileIsMarkdown(file))
  if (payloadFiles.length > 0 && payloadFiles.every(inboxFileIsImage)) {
    return findNotebookOption(['图片收藏', 'AI图像生成', '表情包素材'], ['图像', '图片', '素材'])
  }
  return findNotebookOption(['外部投递', '个人资料', '资料收件箱'], ['资料', '投递']) || options[0] || null
}

function findNotebookOption(exactNames: string[], keywords: string[]) {
  for (const name of exactNames) {
    const exact = collectionNotebookOptions.value.find((notebook) => notebook.name === name || notebook.path === name || String(notebook.path || '').endsWith(` / ${name}`))
    if (exact) return exact
  }
  for (const keyword of keywords) {
    const matched = collectionNotebookOptions.value.find((notebook) => String(notebook.name || '').includes(keyword) || String(notebook.path || '').includes(keyword))
    if (matched) return matched
  }
  return null
}

function inboxCommonTopFolder(item: any) {
  let common = ''
  for (const file of (item?.files || [])) {
    if (!selectedInboxFileIds.value.has(file.id)) continue
    const path = String(file.relativePath || file.originalFilename || '').replace(/\\/g, '/')
    const slash = path.indexOf('/')
    if (slash <= 0) continue
    const top = path.slice(0, slash)
    if (!common) common = top
    else if (common !== top) return ''
  }
  return common
}

function toggleInboxFile(fileId: number, checked: boolean) {
  const next = new Set(selectedInboxFileIds.value)
  if (checked) next.add(fileId)
  else next.delete(fileId)
  selectedInboxFileIds.value = next
  inboxAiPlan.value = null
}

function toggleAllInboxFiles(checked: boolean) {
  selectedInboxFileIds.value = checked
    ? new Set(inboxSelectableFiles.value.map((file: any) => file.id))
    : new Set()
  inboxAiPlan.value = null
}

async function planInboxRequest() {
  const current = selectedInboxRequest.value
  if (!current) {
    notify('error', '请选择投递请求')
    return
  }
  if (selectedInboxFileIds.value.size === 0) {
    notify('error', '请选择要规划的文件')
    return
  }
  loading.inboxPlanning = true
  try {
    const plan = await request<any>(`/inbox/requests/${current.id}/plan`, {
      method: 'POST',
      body: JSON.stringify({
        agentId: inboxReviewAgentId.value || null,
        fileIds: Array.from(selectedInboxFileIds.value),
      }),
    })
    inboxAiPlan.value = plan
    aiActivity.push(plan?.aiTrace)
    inboxImportMode.value = 'ai'
    if (plan.notebookId) inboxReviewNotebookId.value = plan.notebookId
    if (plan.categoryPrefix) inboxCategoryPrefix.value = plan.categoryPrefix
    notify('success', plan.planSource === 'ai-readme' ? 'AI 已根据 README 制定入库计划' : '已生成保守入库计划')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : 'AI 制定入库计划失败')
  } finally {
    loading.inboxPlanning = false
  }
}

async function acceptInboxRequest() {
  const current = selectedInboxRequest.value
  if (!current) {
    notify('error', '请选择投递请求')
    return
  }
  if (inboxImportMode.value === 'manual' && !inboxReviewNotebookId.value) {
    notify('error', '请选择目标知识库')
    return
  }
  if (selectedInboxFileIds.value.size === 0) {
    notify('error', '请选择要入库的文件')
    return
  }
  loading.inbox = true
  try {
    const result = await request<any>(`/inbox/requests/${current.id}/accept`, {
      method: 'POST',
      body: JSON.stringify({
        notebookId: inboxReviewNotebookId.value,
        agentId: inboxReviewAgentId.value || null,
        fileIds: Array.from(selectedInboxFileIds.value),
        categoryPrefix: inboxCategoryPrefix.value,
        includeCurrentContent: false,
        importMode: inboxImportMode.value,
        generateNotes: inboxGenerateNotes.value,
      }),
    })
    notebooks.value = await request<any[]>('/notebooks')
    if (inboxImportMode.value === 'manual' && inboxReviewNotebookId.value) await selectNotebook(inboxReviewNotebookId.value)
    await loadInboxRequests()
    await loadVcpCenter().catch(() => undefined)
    notify(result.failedCount > 0 ? 'error' : 'success', `投递处理完成：${inboxImportMode.value === 'ai' ? 'AI 辅助' : '手动'}入库 ${result.importedCount} 个，失败 ${result.failedCount} 个`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '投递入库失败')
  } finally {
    loading.inbox = false
  }
}

async function rejectInboxRequest() {
  const current = selectedInboxRequest.value
  if (!current) return
  loading.inbox = true
  try {
    await request<void>(`/inbox/requests/${current.id}/reject`, { method: 'POST' })
    await loadInboxRequests()
    notify('success', '已驳回这条投递请求')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '驳回失败')
  } finally {
    loading.inbox = false
  }
}

function inboxStatusName(status: string) {
  const names: Record<string, string> = {
    pending: '待审',
    imported: '已入库',
    partial: '部分成功',
    rejected: '已驳回',
    skipped: '已跳过',
    failed: '失败',
  }
  return names[status] || status || '未知'
}

async function handleFolderInput(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  if (files.length === 0) return
  pendingFolderFiles.value = files
  pendingFolderPlan.value = null
  folderCorrection.value = ''
  folderPlanTree.value = []
  folderImportLogs.value = []
  folderAiTrace.value = []
  folderAiLogs.value = []
  folderLogTab.value = 'import'
  await planPendingFolder()
}

async function planPendingFolder(useCorrection = false) {
  const files = pendingFolderFiles.value
  if (files.length === 0) return
  loading.planningFolder = true
  folderImportStatus.value = useCorrection ? '正在按你的纠错意见重新规划文件树' : '正在扫描文件名并生成待确认文件树'
  addFolderLog(`收到文件夹：${files.length} 个文件，总大小 ${formatBytes(files.reduce((sum, file) => sum + file.size, 0))}`)
  try {
    const inputs = await folderPlanInputs(files)
    const scriptCount = files.filter((file) => isScriptFile(file) && !isImageFile(file)).length
    const imageCount = files.filter((file) => isImageFile(file)).length
    addFolderLog(`规划策略：先用规则解释器出草案，再交给 AI 精修树；图片 ${imageCount} 个按名字分类，脚本 ${scriptCount} 个先按路径/文件名规划`)
    const plan = await requestWithRetry<any>('/library/folder-plan', {
      method: 'POST',
      body: JSON.stringify({
        files: inputs,
        correction: useCorrection ? folderCorrection.value : '',
        previousPlan: useCorrection ? pendingFolderPlan.value : null,
      }),
    }, '全局分类规划', 1, 240_000)
    const uncertainCount = (plan.files || []).filter((file: any) => file.uncertain).length
    pendingFolderPlan.value = plan
    folderPlanTree.value = plan.tree || []
    folderImportStatus.value = `规划完成，等待确认：${plan.tree?.length || 0} 个分类路径，${uncertainCount} 个文件入库时会复判`
    addFolderLog(`规划完成，等待确认：${plan.tree?.length || 0} 个分类路径，${uncertainCount} 个不确定文件`, 'success')
  } catch (error) {
    folderImportStatus.value = `规划失败：${error instanceof Error ? error.message : '文件夹规划失败'}`
    addFolderLog(folderImportStatus.value, 'error')
    notify('error', folderImportStatus.value)
  } finally {
    loading.planningFolder = false
  }
}

async function confirmFolderImport() {
  const files = pendingFolderFiles.value
  const plan = pendingFolderPlan.value
  if (files.length === 0 || !plan) {
    notify('error', '请先选择文件夹并生成规划')
    return
  }
  await importFolderFiles(files)
}

async function retryFailedFolderImports() {
  const files = failedFolderImports.value.map((item) => item.file)
  if (files.length === 0) return
  await importFolderFiles(files)
}

async function importFolderFiles(files: File[]) {
  const plan = pendingFolderPlan.value
  if (!plan) {
    notify('error', '缺少待确认规划')
    return
  }
  loading.importingFolder = true
  failedFolderImports.value = []
  try {
    const uncertainPaths = new Set<string>((plan.files || []).filter((file: any) => file.uncertain).map((file: any) => file.path))
    addFolderLog(`开始并发入库：${files.length} 个文件，每个文件最多重试 10 次`)
    const allImported: any[] = []
    const allTrees = new Set<string>(plan.tree || [])
    let firstImported: any | null = null
    let planners = new Set<string>([plan.planner || 'folder-plan'])
    const planJson = JSON.stringify(plan)
    let finished = 0

    await Promise.all(files.map(async (file) => {
      const path = (file as any).webkitRelativePath || file.name
      try {
        const data = await importSingleFolderFile(file, planJson, uncertainPaths.has(path))
        planners.add(data.planner || 'unknown')
        for (const treePath of data.tree || []) allTrees.add(treePath)
        for (const item of data.files || []) {
          allImported.push(item)
          if (!firstImported) firstImported = item
        }
        if (Array.isArray(data.aiTrace) && data.aiTrace.length) {
          folderAiTrace.value = [...folderAiTrace.value, ...data.aiTrace]
          aiActivity.push(data.aiTrace)
        }
        folderPlanTree.value = Array.from(allTrees)
        ingestResults.value = allImported.slice(-20).reverse()
      } catch (error) {
        failedFolderImports.value = [
          ...failedFolderImports.value,
          { path, file, error: error instanceof Error ? error.message : '导入失败' },
        ]
      } finally {
        finished += 1
        folderImportStatus.value = `并发入库中：${finished}/${files.length} 完成，成功 ${allImported.length}，失败 ${failedFolderImports.value.length}`
      }
    }))

    notebooks.value = await request<any[]>('/notebooks')
    const first = firstImported
    if (first?.notebookId) {
      await selectNotebook(first.notebookId)
      showNotebookOverview()
    }
    if (failedFolderImports.value.length > 0) {
      folderImportStatus.value = `部分完成：成功 ${allImported.length} 个，失败 ${failedFolderImports.value.length} 个`
      addFolderLog(folderImportStatus.value, 'error')
      notify('error', folderImportStatus.value)
    } else {
      folderImportStatus.value = `完成：已导入 ${allImported.length || files.length} 个文件，规划方式 ${Array.from(planners).join(', ')}`
      addFolderLog(folderImportStatus.value, 'success')
      pendingFolderFiles.value = []
      pendingFolderPlan.value = null
      folderCorrection.value = ''
      notify('success', folderImportStatus.value)
    }
  } catch (error) {
    folderImportStatus.value = `失败：${error instanceof Error ? error.message : '文件夹导入失败'}`
    addFolderLog(folderImportStatus.value, 'error')
    notify('error', folderImportStatus.value)
  } finally {
    loading.importingFolder = false
  }
}

async function importSingleFolderFile(file: File, planJson: string, uncertain: boolean) {
  const path = (file as any).webkitRelativePath || file.name
  const formData = new FormData()
  formData.append('files', file)
  formData.append('paths', path)
  formData.append('planJson', planJson)
  return requestWithRetry<any>('/library/folder-import', {
    method: 'POST',
    body: formData,
  }, `入库 ${path}`, 9, isDocumentConversionFile(file) ? 360_000 : (uncertain ? 300_000 : 180_000))
}

async function importLibraryItem() {
  if (!currentNotebookId.value) {
    notify('error', '请先选择一个知识库')
    return
  }
  if (!libraryForm.file) {
    notify('error', '请先选择资料文件')
    return
  }

  loading.importingLibrary = true
  try {
    const conversionHint = isDocumentConversionFile(libraryForm.file)
    if (conversionHint) {
      notify('info', '正在把 PDF/Word 转成 Markdown/可读文本，完成后会生成笔记并进入检索')
    }
    const formData = new FormData()
    formData.append('notebookId', String(currentNotebookId.value))
    if (libraryForm.title.trim()) formData.append('title', libraryForm.title.trim())
    if (libraryForm.category.trim()) formData.append('category', libraryForm.category.trim())
    formData.append('file', libraryForm.file)

    const data = await request<any>('/library/import', {
      method: 'POST',
      body: formData,
    })

    const [loadedNotes, loadedItems] = await Promise.all([
      request<any[]>(`/notes?notebookId=${currentNotebookId.value}`),
      request<any[]>(`/library?notebookId=${currentNotebookId.value}`),
    ])
    notes.value = loadedNotes
    libraryItems.value = loadedItems
    const note = await request<any>(`/notes/${data.noteId}`)
    editNote(note)
    keyword.value = data.title
    question.value = `请基于《${data.title}》总结这份资料的核心内容。`
    notify('success', conversionHint
      ? '文档已转换并入库，原文件已保存，Markdown/可读文本已进入知识库'
      : '资料已导入，原文件已保存，可读文本已进入知识库')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '资料导入失败')
  } finally {
    loading.importingLibrary = false
  }
}

async function openLibraryItem(item: any) {
  if (!item.noteId) {
    await openLibraryFile(item)
    return
  }
  const note = await request<any>(`/notes/${item.noteId}`)
  if (note.notebookId !== currentNotebookId.value) {
    await selectNotebook(note.notebookId)
  }
  editNote(note)
}

async function openLibraryFile(item: any) {
  await openFileLink({
    label: item.originalFilename || item.title || '原文件',
    url: item.fileUrl,
    type: item.contentType || '',
  })
}

async function exportCurrentLibraryFiles() {
  if (!currentNotebookId.value) {
    notify('error', '请先选择要导出的知识库')
    return
  }
  loading.exportingLibrary = true
  try {
    const response = await requestRaw(`/library/export?notebookId=${currentNotebookId.value}&recursive=true`)
    const blob = await response.blob()
    const downloadUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = filenameFromDisposition(response.headers.get('Content-Disposition')) || `${currentNotebook.value?.name || 'Atlas资料导出'}_原文件.zip`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(downloadUrl)
    notify('success', '已开始下载导出 ZIP')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '导出失败')
  } finally {
    loading.exportingLibrary = false
  }
}

function filenameFromDisposition(disposition: string | null) {
  if (!disposition) return ''
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
  if (encoded) return decodeURIComponent(encoded)
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1]
  return plain || ''
}

async function openFileLink(link: { label: string; url: string; type?: string }) {
  try {
    closeFilePreview()
    const response = await requestRaw(link.url)
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const type = link.type || blob.type || ''
    const title = link.label
    const isText = type.startsWith('text/') || /\.(txt|md|markdown|json|xml|csv|log|yml|yaml|toml|js|ts|py|java|css)$/i.test(title)
    const text = isText ? await blob.text().catch(() => '') : ''
    filePreview.value = {
      title,
      url,
      type,
      text,
      zoom: 1,
      fit: 'contain',
    }
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '打开原文件失败')
  }
}

function closeFilePreview() {
  if (filePreview.value?.url) URL.revokeObjectURL(filePreview.value.url)
  filePreview.value = null
}

function setPreviewFit(fit: 'contain' | 'width' | 'actual') {
  if (!filePreview.value) return
  filePreview.value.fit = fit
  filePreview.value.zoom = 1
}

function zoomPreview(delta: number) {
  if (!filePreview.value) return
  filePreview.value.zoom = Math.min(4, Math.max(0.25, Number((filePreview.value.zoom + delta).toFixed(2))))
}

watch(
  () => [noteMode.value, parsedAgentNote.value.hasDualBlocks, parsedAgentNote.value.body, parsedAgentNote.value.human, currentNoteId.value, appView.value, humanNoteHost.value],
  async () => {
    await nextTick()
    renderHumanNoteBubble()
  },
  { immediate: true }
)

watch(
  () => [appView.value, currentNotebookId.value, imageAssets.value.map((item) => item.id).join(',')],
  () => {
    if (appView.value === 'assets') void loadAssetImagePreviews()
    else clearAssetImagePreviews()
  }
)

onUnmounted(() => {
  clearAssetImagePreviews()
})

watch(selectedKnowledgeAgentId, (value) => {
  if (value) localStorage.setItem('atlas_selected_knowledge_agent_id', String(value))
  else localStorage.removeItem('atlas_selected_knowledge_agent_id')
})

watch(selectedDeepWikiAgentId, (value) => {
  if (value) localStorage.setItem('atlas_selected_deepwiki_agent_id', String(value))
  else localStorage.removeItem('atlas_selected_deepwiki_agent_id')
})

watch(selectedVcpAgentId, (value) => {
  if (value) localStorage.setItem('atlas_selected_vcp_agent_id', String(value))
  else localStorage.removeItem('atlas_selected_vcp_agent_id')
})

let vcpNotebookSearchTimer = 0
watch(vcpNotebookQuery, () => {
  if (appView.value !== 'vcp') return
  window.clearTimeout(vcpNotebookSearchTimer)
  vcpNotebookSearchTimer = window.setTimeout(() => {
    void searchVcpNotebooks()
  }, 220)
})

watch(agentNoteSource, (value) => {
  localStorage.setItem('atlas_agent_note_source', value)
})

watch(
  () => [appView.value, currentNotebookId.value, deepWikiMode.value],
  () => {
    if (appView.value === 'deepwiki') void loadDeepWikiLatest()
  }
)

watch(
  () => appView.value,
  () => {
    if (appView.value === 'inbox') void loadInboxRequests()
  }
)

async function importPaper() {
  if (!currentNotebookId.value) {
    notify('error', '请先选择一个知识库')
    return
  }
  if (!paperForm.file) {
    notify('error', '请先选择 PDF / Word 文件')
    return
  }
  if (!isPdfFile(paperForm.file)) {
    loading.importingPaper = true
    try {
      await importLongDocumentAsLibrary('长文档已入库，正文已转换为可检索文本')
    } catch (error) {
      notify('error', error instanceof Error ? error.message : '长文档入库失败')
    } finally {
      loading.importingPaper = false
    }
    return
  }
  if (!paperForm.title.trim() || !paperForm.markdownContent.trim()) {
    notify('error', '请填写论文标题，并粘贴 Markdown 内容')
    return
  }

  loading.importingPaper = true
  try {
    const formData = new FormData()
    formData.append('notebookId', String(currentNotebookId.value))
    formData.append('title', paperForm.title.trim())
    formData.append('summary', paperForm.summary.trim())
    formData.append('markdownContent', paperForm.markdownContent.trim())
    formData.append('file', paperForm.file)

    const data = await request<any>('/papers/import', {
      method: 'POST',
      body: formData,
    })

    const [loadedNotes, loadedItems] = await Promise.all([
      request<any[]>(`/notes?notebookId=${currentNotebookId.value}`),
      request<any[]>(`/library?notebookId=${currentNotebookId.value}`),
    ])
    notes.value = loadedNotes
    libraryItems.value = loadedItems
    const note = await request<any>(`/notes/${data.noteId}`)
    editNote(note)
    keyword.value = paperForm.title.trim()
    question.value = `请总结《${paperForm.title.trim()}》这篇论文的研究问题、方法和结论。`
    notify('success', '论文已归档，原文链接和 Markdown 笔记已生成')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '论文入库失败')
  } finally {
    loading.importingPaper = false
  }
}

async function runSearch(mode = searchMode.value) {
  const query = keyword.value.trim()
  if (!query) {
    notify('error', '请输入搜索问题或关键词')
    return
  }

  searchMode.value = mode
  loading.searching = true
  const t0 = performance.now()
  try {
    const path = mode === 'keyword' ? '/search/keyword' : mode === 'semantic' ? '/search/semantic' : '/search/hybrid'
    // keyword 模式只调 hits 不调 tree(快 ~50%);semantic/hybrid 才同时跑 tree
    if (mode === 'keyword') {
      searchHits.value = await request<any[]>(`${path}?query=${encodeURIComponent(query)}&topK=8`)
      searchTree.value = []
      allSearchTreeExpandedKeys.value = []
    } else {
      const [hits, tree] = await Promise.all([
        request<any[]>(`${path}?query=${encodeURIComponent(query)}&topK=8`),
        request<any>(`/search/tree?query=${encodeURIComponent(query)}&limit=160`),
      ])
      searchHits.value = hits
      searchTree.value = tree.roots || []
      allSearchTreeExpandedKeys.value = tree.expandedKeys || []
    }
    expandedSearchTreeKeys.value = new Set()
    showAllSearchHits.value = false
    showFullSearchTree.value = false
    matchDetails.value = []
    const ms = Math.round(performance.now() - t0)
    const treeMsg = mode === 'keyword' ? '' : `，${searchTree.value.length} 条命中路径`
    notify('info', `检索完成 ${ms}ms：${searchHits.value.length} 条结果${treeMsg}`)
  } catch (error) {
    const message = error instanceof Error ? error.message : '检索失败'
    notify('error', mode === 'semantic' && message.includes('向量模型') ? `${message}。也可以先用"综合"或"精确"。` : message)
  } finally {
    loading.searching = false
  }
}

async function importPaperWithAi() {
  if (!currentNotebookId.value) {
    notify('error', '请先选择一个知识库')
    return
  }
  if (!paperForm.file) {
    notify('error', '请先选择 PDF / Word 文件')
    return
  }

  loading.importingPaperAi = true
  try {
    if (!isPdfFile(paperForm.file)) {
      notify('info', '正在把 Word 长文档转换为 Markdown/可读文本，并生成知识库笔记')
      await importLongDocumentAsLibrary('Word 长文档已转换并入库')
      return
    }
    const formData = new FormData()
    formData.append('notebookId', String(currentNotebookId.value))
    if (paperForm.title.trim()) formData.append('titleHint', paperForm.title.trim())
    formData.append('file', paperForm.file)

    const data = await request<any>('/papers/import/ai', {
      method: 'POST',
      body: formData,
    })

    const [loadedNotes, loadedItems] = await Promise.all([
      request<any[]>(`/notes?notebookId=${currentNotebookId.value}`),
      request<any[]>(`/library?notebookId=${currentNotebookId.value}`),
    ])
    notes.value = loadedNotes
    libraryItems.value = loadedItems
    const note = await request<any>(`/notes/${data.noteId}`)
    editNote(note)
    keyword.value = data.title
    question.value = `请总结《${data.title}》的创新点和实际应用。`
    notify('success', 'AI 已生成论文笔记，并进入知识库')
  } catch (error) {
    notify('error', error instanceof Error ? error.message : 'AI 论文入库失败')
  } finally {
    loading.importingPaperAi = false
  }
}

async function importLongDocumentAsLibrary(successMessage: string) {
  if (!currentNotebookId.value || !paperForm.file) return
  const formData = new FormData()
  formData.append('notebookId', String(currentNotebookId.value))
  if (paperForm.title.trim()) formData.append('title', paperForm.title.trim())
  formData.append('category', '长文档/Word')
  formData.append('file', paperForm.file)

  const data = await request<any>('/library/import', {
    method: 'POST',
    body: formData,
  })

  const [loadedNotes, loadedItems] = await Promise.all([
    request<any[]>(`/notes?notebookId=${currentNotebookId.value}`),
    request<any[]>(`/library?notebookId=${currentNotebookId.value}`),
  ])
  notes.value = loadedNotes
  libraryItems.value = loadedItems
  const note = await request<any>(`/notes/${data.noteId}`)
  editNote(note)
  keyword.value = data.title
  question.value = `请基于《${data.title}》总结这份长文档的核心内容。`
  notify('success', successMessage)
}

async function openMatchDetails() {
  const query = keyword.value.trim()
  if (!query) {
    notify('error', '请输入要查看的关键词')
    return
  }
  loading.searching = true
  try {
    matchDetails.value = await request<any[]>(`/search/matches?query=${encodeURIComponent(query)}&limit=30`)
    showMatchDetails.value = true
    notify('info', `找到 ${matchDetails.value.length} 篇命中文档`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '加载命中详情失败')
  } finally {
    loading.searching = false
  }
}

function toggleFullSearchTree() {
  showFullSearchTree.value = !showFullSearchTree.value
  expandedSearchTreeKeys.value = showFullSearchTree.value ? new Set(allSearchTreeExpandedKeys.value) : new Set()
}

async function openHitDetail(hit: any) {
  const query = keyword.value.trim()
  if (!query) {
    await openHit(hit)
    return
  }
  loading.searching = true
  try {
    const details = await request<any[]>(`/search/matches?query=${encodeURIComponent(query)}&limit=100`)
    const selected = details.filter((detail) => detail.noteId === hit.noteId)
    matchDetails.value = selected.length ? selected : [{
      noteId: hit.noteId,
      title: hit.title,
      totalMatches: 0,
      excerpts: [{
        field: 'snippet',
        label: searchSourceName(hit.source),
        excerpt: hit.snippet || '这条结果主要来自语义或路径重排，没有精确片段。',
        matchStart: -1,
        matchEnd: -1,
      }],
    }]
    showMatchDetails.value = true
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '加载单条命中详情失败')
  } finally {
    loading.searching = false
  }
}

function highlightedParts(excerpt: any) {
  const text = excerpt?.excerpt || excerpt?.text || ''
  const start = Math.max(0, Number(excerpt?.matchStart || 0))
  const end = Math.min(text.length, Number(excerpt?.matchEnd || 0))
  if (end <= start) return { before: text, hit: '', after: '' }
  return {
    before: text.slice(0, start),
    hit: text.slice(start, end),
    after: text.slice(end),
  }
}

async function openHit(hit: any) {
  const note = await request<any>(`/notes/${hit.noteId}`)
  if (note.notebookId !== currentNotebookId.value) {
    currentNotebookId.value = note.notebookId
    notes.value = await request<any[]>(`/notes?notebookId=${note.notebookId}`)
  }
  editNote(note)
}

async function handleDeepWikiLink(payload: { href: string; label: string }) {
  const href = payload.href.trim()
  const label = payload.label.trim()
  const sourceText = `${href} ${label}`
  const noteId = Number((sourceText.match(/atlas:\/\/note\/(\d+)/i) || sourceText.match(/note\s*#?\s*(\d+)/i))?.[1] || 0)
  if (noteId > 0) {
    try {
      // 从 DeepWiki 跳转时,记住来源上下文,便于一键返回原 Wiki 页
      if (appView.value === 'deepwiki' && currentNotebookId.value) {
        deepWikiReturnFrom.value = {
          notebookId: currentNotebookId.value,
          mode: deepWikiMode.value,
          focus: deepWikiFocus.value,
        }
      }
      await openHit({ noteId })
      appView.value = 'workspace'
      notify('success', `已打开 ${label || `Note #${noteId}`}`)
      return
    } catch {
      notify('error', `没有找到 Note #${noteId}`)
      return
    }
  }

  const fileId = Number((sourceText.match(/atlas:\/\/(?:library|file)\/(\d+)/i) || sourceText.match(/(?:file|library)\s*#?\s*(\d+)/i))?.[1] || 0)
  if (fileId > 0) {
    const item = libraryItems.value.find((entry) => entry.id === fileId)
    if (item) {
      await openLibraryFile(item)
      return
    }
    await openFileLink({ label: label || `File #${fileId}`, url: `/library/${fileId}/file`, type: '' })
    return
  }

  if (href.startsWith('/api/') || href.startsWith(`${API_ORIGIN}/api/`)) {
    await openFileLink({ label: label || 'DeepWiki 链接', url: href, type: '' })
    return
  }

  if (/^https?:\/\//i.test(href)) {
    window.open(href, '_blank', 'noopener,noreferrer')
    return
  }

  notify('info', `DeepWiki 链接已拦截：${label || href}`)
}

async function openNoteHistory() {
  if (!currentNoteId.value) {
    notify('error', '请先选择一篇笔记')
    return
  }
  showNoteHistoryModal.value = true
  loadingNoteHistory.value = true
  noteHistory.value = []
  try {
    noteHistory.value = await request<any[]>(`/notes/${currentNoteId.value}/history`)
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '加载历史版本失败')
  } finally {
    loadingNoteHistory.value = false
  }
}

function formatHistoryTime(value: string) {
  if (!value) return ''
  const d = new Date(value.replace(' ', 'T'))
  if (isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function rollbackNoteHistory(historyId: number, version: number) {
  if (!currentNoteId.value) return
  if (!window.confirm(`回滚到第 ${version} 版?当前内容会先存为新的历史版本。`)) return
  rollingBack.value = true
  try {
    const data = await request<any>(`/notes/${currentNoteId.value}/history/${historyId}/rollback`, { method: 'POST' })
    noteForm.title = data.title
    noteForm.content = data.content
    noteForm.summary = data.summary || ''
    notify('success', `已回滚到第 ${version} 版`)
    showNoteHistoryModal.value = false
    await openNoteHistory()
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '回滚失败')
  } finally {
    rollingBack.value = false
  }
}

async function askRag() {
  const text = question.value.trim()
  if (!text) {
    notify('error', '请输入要问知识库的问题')
    return
  }

  // SSE 流式问答:用 fetch + ReadableStream(可带 Authorization header,比 EventSource 更安全)
  loading.asking = true
  answer.value = ''
  citations.value = []
  ragAiTrace.value = []

  const params = new URLSearchParams({ question: text, topK: '5' })
  if (currentNotebookId.value) params.set('notebookId', String(currentNotebookId.value))

  let currentEvent = 'message'
  let dataBuf = ''
  let collected = ''

  const finish = () => {
    loading.asking = false
  }

  try {
    const response = await fetch(apiUrl(`/chat/rag/stream?${params.toString()}`), {
      headers: token.value ? { Authorization: `Bearer ${token.value}` } : {},
    })
    if (!response.ok || !response.body) {
      throw new Error(`问答失败(${response.status})`)
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // SSE 以空行分隔事件
      let sep: number
      while ((sep = buffer.indexOf('\n\n')) >= 0) {
        const rawEvent = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        currentEvent = 'message'
        dataBuf = ''
        for (const line of rawEvent.split('\n')) {
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            dataBuf += line.slice(5).trim()
          }
        }
        if (!dataBuf) continue
        if (currentEvent === 'delta') {
          collected += dataBuf
          answer.value = collected
        } else if (currentEvent === 'citations') {
          try {
            citations.value = JSON.parse(dataBuf) || []
          } catch { /* ignore */ }
          if (citations.value.length) notify('success', `已找到 ${citations.value.length} 个参考片段`)
        } else if (currentEvent === 'ai-call-start') {
          // SSE 异步线程拿不到主请求 ThreadLocal,用专门事件回传 AI 调用元信息
          try {
            const meta = JSON.parse(dataBuf)
            const call: Record<string, any> = {
              scene: meta.scene || 'rag',
              channel: meta.channel || 'chat',
              model: meta.model || '',
              providerId: meta.providerId,
              inputChars: meta.inputChars || 0,
              outputChars: 0,
              durationMs: 0,
              success: true,
              error: null,
              agentId: null,
              agentName: null,
              providerName: null,
              _pending: true,
            }
            const globalId = aiActivity.startStreaming({
              scene: call.scene,
              channel: call.channel,
              model: call.model,
              providerId: call.providerId,
              inputChars: call.inputChars,
            })
            call._globalId = globalId
            ragAiTrace.value = [...ragAiTrace.value, call]
            console.log('[AiTrace] ai-call-start', meta)
          } catch (e) { console.warn('[AiTrace] parse start error', e) }
        } else if (currentEvent === 'ai-call-end') {
          try {
            const meta = JSON.parse(dataBuf)
            console.log('[AiTrace] ai-call-end', meta)
            const last = [...ragAiTrace.value]
            let found = false
            for (let i = last.length - 1; i >= 0; i--) {
              if (last[i]._pending) {
                last[i] = {
                  ...last[i],
                  durationMs: meta.durationMs || 0,
                  success: meta.success !== false,
                  outputChars: meta.outputChars || 0,
                  error: meta.error || null,
                  _pending: false,
                }
                if (typeof last[i]._globalId === 'number') {
                  aiActivity.endStreaming(last[i]._globalId, {
                    success: meta.success !== false,
                    durationMs: meta.durationMs || 0,
                    outputChars: meta.outputChars || 0,
                    error: meta.error || null,
                  })
                }
                found = true
                break
              }
            }
            if (!found) {
              // end 先于 start 到(竞态,极少见):直接记一条完成的
              const globalId = aiActivity.startStreaming({ scene: 'rag', model: meta.model || '', ...meta })
              aiActivity.endStreaming(globalId, meta)
            }
            ragAiTrace.value = last
          } catch (e) { console.warn('[AiTrace] parse end error', e) }
        } else if (currentEvent === 'done') {
          finish()
        } else if (currentEvent === 'error') {
          if (!collected) notify('error', dataBuf || '知识库问答失败')
          finish()
        }
      }
    }
    finish()
  } catch (error) {
    finish()
    if (!collected) {
      notify('error', error instanceof Error ? error.message : '知识库问答失败')
    }
  }
}

onMounted(async () => {
  if (!token.value) return
  try {
    await loadWorkspace()
  } catch (error) {
    notify('error', error instanceof Error ? error.message : '加载工作台失败')
  }
})
</script>

<template>
  <main class="app-shell">
    <NocturneArt v-if="themeId === 'nocturne'" />
    <section v-if="!isAuthed" class="login-view">
      <div class="login-bg" aria-hidden="true">
        <div class="login-bg__orb login-bg__orb--primary" />
        <div class="login-bg__orb login-bg__orb--sage" />
        <div class="login-bg__grid" />
      </div>

      <div class="brand-block">
        <NocturneHero v-if="themeId === 'nocturne'" class="brand-block__hero" />
        <span class="brand-block__chip">
          <span class="brand-block__chip-dot" />
          ATLAS · 个人知识库
        </span>
        <h1>把零散资料<br />整理成<em>会回答</em>的知识库</h1>
        <p>导入文件、自动归类、语义检索、AI 问答 —— 一个属于你的认知图谱。</p>
        <div class="brand-block__features">
          <div class="brand-feature">
            <div class="brand-feature__icon">M</div>
            <div>
              <strong>MinerU 解析</strong>
              <small>PDF / Word 自动转 Markdown</small>
            </div>
          </div>
          <div class="brand-feature">
            <div class="brand-feature__icon">R</div>
            <div>
              <strong>RAG 问答</strong>
              <small>向量召回 + 上下文回答</small>
            </div>
          </div>
          <div class="brand-feature">
            <div class="brand-feature__icon">V</div>
            <div>
              <strong>VCP 同步</strong>
              <small>双轨笔记进入长期记忆</small>
            </div>
          </div>
        </div>
      </div>

      <form class="auth-panel" @submit.prevent="submitAuth">
        <header class="auth-panel__head">
          <h2>欢迎回来</h2>
          <p>登录或创建账号开始整理你的资料</p>
        </header>

        <div class="segmented">
          <button type="button" :class="{ active: authMode === 'login' }" @click="authMode = 'login'">登录</button>
          <button type="button" :class="{ active: authMode === 'register' }" @click="authMode = 'register'">注册</button>
          <span class="segmented__indicator" :class="`segmented__indicator--${authMode}`" />
        </div>

        <div class="auth-fields">
          <input v-if="authMode === 'register'" v-model="authForm.username" autocomplete="username" placeholder="用户名" />
          <input v-if="authMode === 'register'" v-model="authForm.email" autocomplete="email" placeholder="邮箱" />
          <input v-if="authMode === 'login'" v-model="authForm.account" autocomplete="username" placeholder="账号" />
          <input v-model="authForm.password" type="password" autocomplete="current-password" placeholder="密码" />
        </div>

        <button class="primary wide" type="submit">
          <span>{{ authMode === 'login' ? '进入工作台' : '创建账号' }}</span>
          <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
            <path d="M3 8h10M9 4l4 4-4 4" stroke="currentColor" fill="none" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>

        <small class="auth-panel__hint">登录即可使用</small>
      </form>
    </section>

    <section v-else class="desk">
      <header class="appbar glass">
        <div class="app-title">
          <span class="app-title__logo">A</span>
          <strong>Atlas</strong>
          <span class="app-title__crumb" v-if="currentNotebook?.path || currentNotebook?.name">
            <span class="app-title__sep">/</span>
            {{ currentNotebook?.path || currentNotebook?.name }}
          </span>
          <span class="app-title__crumb app-title__crumb--muted" v-else>
            <span class="app-title__sep">/</span>
            未选择知识库
          </span>
        </div>
        <nav class="app-actions">
          <button class="ghost nav-btn" :class="{ active: appView === 'workspace' }" @click="appView = 'workspace'">工作台</button>
          <button class="ghost nav-btn" :class="{ active: appView === 'assets' }" @click="openAssets">资产管家</button>
          <button class="ghost nav-btn" :class="{ active: appView === 'inbox' }" @click="openInbox">投递箱</button>
          <button class="ghost nav-btn" :class="{ active: appView === 'deepwiki' }" @click="openDeepWiki">DeepWiki</button>
          <button class="ghost nav-btn" :class="{ active: appView === 'vcp' }" @click="openVcpCenter">VCP 同步</button>
          <button class="ghost nav-btn" :class="{ active: appView === 'trash' }" @click="openTrash">回收站</button>
          <span class="app-actions__sep" aria-hidden="true" />
          <ThemePicker />
          <AiActivityButton />
          <button class="ghost nav-btn nav-btn--icon" @click="showAiSettings = true" title="AI 设置">
            <svg viewBox="0 0 16 16" width="14" height="14"><path d="M8 5.5a2.5 2.5 0 100 5 2.5 2.5 0 000-5zM8 1v2M8 13v2M3.05 3.05l1.42 1.42M11.53 11.53l1.42 1.42M1 8h2M13 8h2M3.05 12.95l1.42-1.42M11.53 4.47l1.42-1.42" stroke="currentColor" fill="none" stroke-width="1.4" stroke-linecap="round"/></svg>
          </button>
          <button class="ghost nav-btn nav-btn--user" @click="logout">
            <span class="nav-btn__avatar">{{ username.slice(0, 1).toUpperCase() }}</span>
            <span class="nav-btn__name">{{ username }}</span>
          </button>
        </nav>
      </header>

      <section v-if="appView === 'workspace'" class="desk-grid">
        <aside class="nav-pane">
          <NotebookCreatePanel
            :name="notebookForm.name"
            :mode="notebookCreateMode"
            :current-name="currentNotebook?.name"
            :can-create-child="Boolean(currentNotebookId)"
            :loading="loading.creatingNotebook"
            @update:name="notebookForm.name = $event"
            @update:mode="notebookCreateMode = $event"
            @create="createNotebook"
          />

          <div class="nav-section">
            <div class="section-title">
              <span>知识库</span>
              <button class="text-button danger-link" :disabled="!currentNotebookId" @click="deleteNotebook">删除当前</button>
            </div>
            <NotebookTree
              :rows="sidebarNotebookRows"
              :current-id="currentNotebookId"
              :dragged-id="draggedNotebookId"
              @open="openSidebarNotebookNode"
              @rename="renameNotebook"
              @drag-start="startNotebookDrag"
              @drag-end="draggedNotebookId = null"
              @drop="dropNotebook"
            />
          </div>

          <div class="nav-section">
            <div class="section-title">
              <span>文件树</span>
              <button class="text-button" :disabled="!currentNotebookId" @click="newNote">新建</button>
            </div>
            <FileTreeView
              :rows="fileTreeRows"
              :current-note-id="currentNoteId"
              empty-text="暂无文件"
              show-open-file
              show-delete-asset
              @open-node="openTreeNode"
              @open-file="openLibraryFile"
              @delete-asset="deleteAsset"
            />
          </div>
        </aside>

        <section class="note-pane">
          <button v-if="deepWikiReturnFrom" class="deepwiki-return-bar" @click="returnToDeepWiki">
            <svg viewBox="0 0 16 16" width="13" height="13" aria-hidden="true"><path d="M10 3L5 8l5 5" stroke="currentColor" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
            返回 DeepWiki
          </button>
          <section v-if="noteMode === 'overview'" class="notebook-overview">
            <header class="overview-header">
              <div>
                <span>{{ currentNotebook ? notebookTypeLabel(currentNotebook) : '知识库' }}</span>
                <h1>{{ currentNotebook?.path || currentNotebook?.name || '未选择知识库' }}</h1>
                <p>{{ currentNotebook?.description || '这个节点还没有介绍。可以用更名功能补充描述，或先导入资料生成结构。' }}</p>
              </div>
              <div class="overview-actions">
                <button class="ghost" :disabled="!currentNotebookId || loading.exportingLibrary || notebookOverviewStats.files === 0" @click="exportCurrentLibraryFiles">
                  {{ loading.exportingLibrary ? '导出中' : '导出原文件' }}
                </button>
                <button class="primary" :disabled="!currentNotebookId || !currentNotebookIsCollection" @click="newNote">新建笔记</button>
              </div>
            </header>

            <div class="overview-stats">
              <article>
                <strong>{{ currentNotebookIsCollection ? notebookOverviewStats.notes : childCollections.length }}</strong>
                <span>{{ currentNotebookIsCollection ? '笔记' : '子知识库' }}</span>
              </article>
              <article>
                <strong>{{ currentNotebookIsCollection ? notebookOverviewStats.files : childNotebookRows.length }}</strong>
                <span>{{ currentNotebookIsCollection ? '资料文件' : '子节点' }}</span>
              </article>
              <article>
                <strong>{{ notebookOverviewStats.categories }}</strong>
                <span>分类路径</span>
              </article>
              <article>
                <strong>{{ notebookOverviewStats.types }}</strong>
                <span>文件类型</span>
              </article>
            </div>

            <div v-if="currentNotebook?.children?.length" class="overview-tree">
              <div class="section-title">
                <span>内部知识树</span>
              </div>
              <NotebookTree
                v-if="internalNotebookTreeRows.length > 0"
                :rows="internalNotebookTreeRows"
                :current-id="currentNotebookId"
                :dragged-id="draggedNotebookId"
                @open="openNotebookTreeNode"
                @rename="renameNotebook"
                @drag-start="startNotebookDrag"
                @drag-end="draggedNotebookId = null"
                @drop="dropNotebook"
              />
              <p v-else class="empty">这个组织节点下面还没有知识库。</p>
            </div>

            <div class="overview-tree">
              <div class="section-title">
                <span>{{ currentNotebookIsCollection ? '文件树' : '全部子库文件树' }}</span>
                <button class="text-button" :disabled="!currentNotebookId" @click="newNote">新建</button>
              </div>
              <FileTreeView
                :rows="fileTreeRows"
                :current-note-id="currentNoteId"
                large
                empty-text="这个知识库下面还没有文件，去右侧导入区添加资料。"
                show-open-file
                @open-node="openTreeNode"
                @open-file="openLibraryFile"
              />
            </div>

            <div class="overview-tree">
              <div class="section-title">
                <span>按文件类型浏览</span>
              </div>
              <FileTreeView
                :rows="typeTreeRows"
                :current-note-id="currentNoteId"
                large
                empty-text="暂无可按类型浏览的文件。"
                show-open-file
                @open-node="openTreeNode"
                @open-file="openLibraryFile"
              />
            </div>
          </section>

          <NoteToolbar
            v-if="noteMode !== 'overview'"
            :title="noteForm.title"
            :mode="noteMode"
            :agents="agents"
            :selected-agent-id="selectedKnowledgeAgentId"
            :source="agentNoteSource"
            :loading-agent-note="loading.agentNote"
            :saving="loading.saving"
            :has-note="Boolean(currentNoteId)"
            :has-notebook="Boolean(currentNotebookId)"
            @update:title="noteForm.title = $event"
            @update:mode="noteMode = $event"
            @update:selected-agent-id="selectedKnowledgeAgentId = $event"
            @update:source="agentNoteSource = $event"
            @generate-agent-note="generateAgentNote"
            @overview="showNotebookOverview"
            @history="openNoteHistory"
            @delete-note="deleteNote"
            @save="saveNote"
          />
          <div v-if="noteMode !== 'overview' && currentFileLinks.length" class="note-file-links">
            <button v-for="link in currentFileLinks" :key="link.url" class="file-link-button" @click="openFileLink(link)">
              {{ link.label }}
            </button>
          </div>
          <section v-if="loading.agentNote" class="agent-working-panel">
            <strong>Agent 正在生成双轨笔记</strong>
            <p>正在读取关联原文件和附件，完成后会自动展示阅读笔记。这里不会把半截内容塞进页面。</p>
          </section>
          <div v-else-if="noteMode === 'read'" class="note-read-layout">
            <article
              v-if="!parsedAgentNote.hasDualBlocks"
              class="note-reader prose"
              v-html="renderedNoteContent"
              @click="handleHumanNoteClick"
            ></article>
            <article
              v-else
              ref="humanNoteHost"
              class="note-reader prose human-note-renderer"
              @click="handleHumanNoteClick"
            ></article>
          </div>
          <div v-else-if="noteMode === 'edit'" class="note-edit-layout">
            <textarea
              v-if="parsedAgentNote.hasDualBlocks"
              :value="parsedAgentNote.body"
              class="note-editor"
              placeholder="这里编辑原始 Markdown 正文。AI 生成的阅读块会保留在正文下方；AI 记忆在记忆区单独管理。"
              spellcheck="false"
              @input="handleBaseNoteInput"
            />
            <textarea v-else v-model="noteForm.content" class="note-editor" spellcheck="false" />
          </div>
          <div v-else-if="noteMode === 'memory'" class="note-memory-layout">
            <section v-if="parsedAgentNote.hasDualBlocks" class="ai-note-panel editable">
              <div class="section-title">
                <span>AI 记忆草稿</span>
                <small>给 RAG、搜索和 VCP 同步使用</small>
              </div>
              <p v-if="!parsedAgentNote.memory" class="empty">这篇笔记还没有 AI 记忆草稿。</p>
              <textarea
                :value="parsedAgentNote.memory"
                class="note-editor ai-note-editor"
                placeholder="这里编辑 VCP_AI_MEMORY。保存笔记会写回 Atlas，供搜索、RAG 和 VCP 同步中心使用。"
                spellcheck="false"
                @input="handleVcpMemoryInput"
              />
              <div class="memory-actions">
                <button class="primary" @click="goVcpMemoryDrafts">进入 VCP 同步中心</button>
              </div>
            </section>
            <section v-else class="ai-note-panel empty-memory-panel">
              <div class="section-title">
                <span>AI 记忆草稿</span>
                <small>这篇笔记还不是双轨格式</small>
              </div>
              <p class="empty">先点击 AI 做笔记，生成 ATLAS_HUMAN_NOTE 和 VCP_AI_MEMORY 后，这里会显示可同步的 AI 记忆。</p>
            </section>
            <details v-if="parsedAgentNote.hasDualBlocks" class="raw-note-details">
              <summary>原始 Agent 返回内容</summary>
              <textarea v-model="noteForm.content" class="note-editor raw-note-editor" spellcheck="false" />
            </details>
          </div>
        </section>

        <aside class="assistant-pane">
          <div class="assistant-tabs">
            <button :class="{ active: assistantTab === 'search' }" @click="assistantTab = 'search'">搜索</button>
            <button :class="{ active: assistantTab === 'ask' }" @click="assistantTab = 'ask'">问答</button>
            <button :class="{ active: assistantTab === 'import' }" @click="assistantTab = 'import'">导入</button>
          </div>

          <section v-if="assistantTab === 'search'" class="tool-panel">
            <div class="inline-action">
              <input v-model="keyword" placeholder="搜索知识库" @keyup.enter="runSearch()" />
              <button class="primary" :disabled="loading.searching" @click="runSearch()">{{ loading.searching ? '搜索中' : '搜索' }}</button>
            </div>
            <div class="mode-row">
              <button :class="{ active: searchMode === 'keyword' }" @click="runSearch('keyword')" title="关键词精确查找,本地秒出">
                极速
              </button>
              <button :class="{ active: searchMode === 'hybrid' }" @click="runSearch('hybrid')" title="关键词 + AI 语义,需要 embedding 模型">
                AI · 综合
              </button>
              <button :class="{ active: searchMode === 'semantic' }" @click="runSearch('semantic')" title="纯 AI 语义,理解意图但慢">
                AI · 语义
              </button>
            </div>
            <div class="result-list">
              <div class="search-section-head">
                <strong>命中卡片</strong>
                <span>{{ searchHits.length }} 条</span>
              </div>
              <article v-for="hit in visibleSearchHits" :key="`${hit.noteId}-${hit.source}`" class="result-card" @click="openHitDetail(hit)">
                <strong>{{ hit.title || '未命名笔记' }}</strong>
                <span>{{ hit.snippet }}</span>
                <em>{{ searchSourceName(hit.source) }} · {{ Number(hit.score || 0).toFixed(3) }}</em>
                <button class="text-button" @click.stop="openHit(hit)">打开笔记</button>
              </article>
              <div v-if="searchHits.length > 5" class="search-more-actions">
                <button class="ghost wide" @click="showAllSearchHits = !showAllSearchHits">
                  {{ showAllSearchHits ? '收起卡片' : `查看全部 ${searchHits.length} 条卡片` }}
                </button>
                <button class="ghost wide" :disabled="loading.searching || !keyword.trim()" @click="openMatchDetails">查看全部命中详情</button>
              </div>
              <p v-if="searchHits.length === 0" class="empty compact">暂无结果</p>
            </div>
            <div v-if="searchTreeRows.length" class="search-tree-panel">
              <div class="search-section-head">
                <strong>命中树</strong>
                <span>{{ searchTreeRows.length }} 个节点</span>
              </div>
              <div class="file-tree compact-tree">
                <div
                  v-for="row in visibleSearchTreeRows"
                  :key="row.key"
                  class="tree-row"
                  :class="[`tree-${row.type === 'library' ? 'library' : 'folder'}`]"
                  :style="{ paddingLeft: `${8 + row.level * 14}px` }"
                >
                  <button class="tree-main" @click="openSearchTreeNode(row)">
                    <span class="tree-caret">{{ row.expandable ? (row.expanded ? '▾' : '▸') : '' }}</span>
                    <span class="tree-icon" :class="row.type === 'library' ? 'tree-icon-library' : 'tree-icon-folder'"></span>
                    <span class="tree-name">{{ row.name }}</span>
                  </button>
                  <button v-if="row.fileUrl" class="text-button" @click="openFileLink({ label: row.name, url: row.fileUrl, type: '' })">原文</button>
                  <small v-if="row.reason">{{ row.reason }}</small>
                </div>
              </div>
              <button class="ghost wide" @click="toggleFullSearchTree">
                {{ showFullSearchTree ? '收起命中树' : '查看完整命中树' }}
              </button>
            </div>
          </section>

          <section v-if="assistantTab === 'ask'" class="tool-panel">
            <textarea v-model="question" class="question-box" placeholder="问知识库" />
            <button class="primary wide" :disabled="loading.asking" @click="askRag">{{ loading.asking ? '思考中' : '提问' }}</button>
            <article v-if="answer" class="answer-card">
              <strong>回答</strong>
              <p>{{ answer }}</p>
            </article>
            <AiTracePanel v-if="ragAiTrace.length" :trace="ragAiTrace" />
          </section>

          <section v-if="assistantTab === 'import'" class="tool-panel import-panel">
            <div class="import-hub-head">
              <div>
                <strong>导入中心</strong>
                <span>把资料放进 Atlas：PDF/Word 先转 Markdown/可读文本，再生成笔记、索引和搜索。</span>
              </div>
              <em>{{ currentNotebook ? currentNotebook.name : '未选择知识库' }}</em>
            </div>

            <div class="import-mode-tabs" role="tablist" aria-label="导入方式">
              <button :class="{ active: importMode === 'smart' }" @click="importMode = 'smart'">智能整理</button>
              <button :class="{ active: importMode === 'folder' }" @click="importMode = 'folder'">文件夹规划</button>
              <button :class="{ active: importMode === 'single' }" @click="importMode = 'single'">单文件归档</button>
              <button :class="{ active: importMode === 'paper' }" @click="importMode = 'paper'">论文长文档</button>
            </div>

            <div v-if="importMode === 'smart'" class="import-workspace">
              <div class="ingest-drop import-drop-primary" @dragover.prevent @drop="handleDrop">
                <strong>{{ loading.autoIngesting ? '正在整理并入库' : '拖入多个文件' }}</strong>
                <span>适合少量混合资料。支持 TXT/MD/HTML/PDF/Word；长文档会先解析成可检索文本。</span>
                <label class="file-picker compact-picker">
                  <span>选择多个文件</span>
                  <input type="file" multiple :accept="documentImportAccept" @change="handleAutoIngestInput" />
                </label>
              </div>

              <div v-if="ingestResults.length" class="ingest-results">
                <div class="import-section-title">
                  <strong>最近入库</strong>
                  <span>{{ ingestResults.length }} 条</span>
                </div>
                <article v-for="item in ingestResults.slice(0, 6)" :key="`${item.noteId}-${item.itemId}`">
                  <strong>{{ item.title }}</strong>
                  <span>{{ item.notebookPath || item.notebookName || '知识库' }} / {{ item.category || '未分类' }}</span>
                  <em v-if="item.tags?.length">#{{ item.tags.join(' #') }}</em>
                  <AiTracePanel v-if="item.aiTrace?.length" :trace="item.aiTrace" compact />
                </article>
              </div>
            </div>

            <div v-else-if="importMode === 'folder'" class="import-workspace">
              <div class="import-card import-flow-card">
                <div class="import-card-head">
                  <strong>批量文件夹整理</strong>
                  <span>适合大文件夹。先生成待确认文件树；PDF/Word 入库时会转成 Markdown/可读文本。</span>
                </div>
                <label class="file-picker">
                  <span>{{ loading.planningFolder ? '正在规划文件树' : pendingFolderPlan ? '重新选择文件夹' : '选择文件夹' }}</span>
                  <input type="file" multiple webkitdirectory directory @change="handleFolderInput" />
                </label>
                <span v-if="folderImportStatus" class="status-line">{{ folderImportStatus }}</span>

                <div v-if="pendingFolderPlan" class="folder-confirm">
                  <strong>等待确认</strong>
                  <p>{{ folderPlanSummary }}</p>
                  <textarea v-model="folderCorrection" placeholder="不满意就写调整意见，例如：图片统一放到视觉资产；脚本只作为工具脚本；第几柱统一改为第几女主。"></textarea>
                  <div class="folder-actions">
                    <button class="ghost" :disabled="loading.planningFolder || loading.importingFolder || !folderCorrection.trim()" @click="planPendingFolder(true)">
                      {{ loading.planningFolder ? '重排中' : '按意见重排' }}
                    </button>
                    <button class="primary" :disabled="loading.planningFolder || loading.importingFolder" @click="confirmFolderImport">
                      {{ loading.importingFolder ? '入库中' : '确认入库' }}
                    </button>
                  </div>
                </div>

                <div v-if="failedFolderImports.length" class="folder-failures">
                  <div>
                    <strong>失败文件</strong>
                    <button class="ghost small" :disabled="loading.importingFolder" @click="retryFailedFolderImports">重试失败文件</button>
                  </div>
                  <p v-for="item in failedFolderImports" :key="item.path">
                    <span>{{ item.path }}</span>
                    <em>{{ item.error }}</em>
                  </p>
                </div>
              </div>

              <div v-if="folderPlanTree.length" class="folder-tree">
                <div class="import-section-title">
                  <strong>规划文件树</strong>
                  <span>{{ folderPlanTree.length }} 条路径</span>
                </div>
                <p
                  v-for="row in folderPlanTreeRows"
                  :key="row.key"
                  :style="{ paddingLeft: `${row.level * 14}px` }"
                >{{ row.name }}</p>
              </div>

              <AiTracePanel v-if="folderAiTrace.length" :trace="folderAiTrace" :default-open="true" />

              <div v-if="folderImportLogs.length || folderAiLogs.length" class="folder-log">
                <div class="folder-log-head">
                  <strong>{{ folderLogTab === 'import' ? '导入日志' : 'AI 交互' }}</strong>
                  <span>{{ folderLogTab === 'import' ? folderImportLogs.length : folderAiLogs.length }} 条</span>
                </div>
                <div class="log-tabs">
                  <button :class="{ active: folderLogTab === 'import' }" @click="folderLogTab = 'import'">导入</button>
                  <button :class="{ active: folderLogTab === 'ai' }" @click="folderLogTab = 'ai'">AI</button>
                </div>
                <template v-if="folderLogTab === 'import'">
                  <p v-for="(log, index) in folderImportLogs" :key="`${log.time}-${index}`" :class="`log-${log.type}`">
                    <span>{{ log.time }}</span>
                    <em>{{ log.text }}</em>
                  </p>
                </template>
                <template v-else>
                  <details v-for="(log, index) in folderAiLogs" :key="`ai-${log.time}-${index}`" class="ai-log-entry">
                    <summary>
                      <span>{{ log.time }}</span>
                      <em>{{ log.title }}</em>
                    </summary>
                    <strong>发送</strong>
                    <pre>{{ log.request }}</pre>
                    <strong>返回</strong>
                    <pre>{{ log.response }}</pre>
                  </details>
                </template>
              </div>
            </div>

            <div v-else-if="importMode === 'single'" class="import-workspace">
              <div class="import-card import-flow-card">
                <div class="import-card-head">
                  <strong>单文件归档</strong>
                  <span>适合已经知道放到哪个知识库的资料。PDF/Word 会先转换，再生成笔记和索引。</span>
                </div>
                <label class="file-picker">
                  <span>{{ libraryFileName || '选择 TXT / MD / HTML / PDF / Word' }}</span>
                  <input type="file" :accept="documentImportAccept" @change="handleLibraryFile" />
                </label>
                <input v-model="libraryForm.title" placeholder="标题" />
                <input v-model="libraryForm.category" placeholder="库内分类路径，例如：课堂笔记/贝多芬" />
                <button class="primary wide" :disabled="loading.importingLibrary || !currentNotebookId" @click="importLibraryItem">
                  {{ loading.importingLibrary ? '导入中' : '导入当前知识库' }}
                </button>
              </div>
            </div>

            <div v-else class="import-workspace">
              <div class="import-card import-flow-card">
                <div class="import-card-head">
                  <strong>论文 / 长文档</strong>
                  <span>PDF 走论文阅读链路；Word 会转 Markdown/可读文本后作为长文档笔记入库。</span>
                </div>
                <label class="file-picker">
                  <span>{{ paperFileName || '选择 PDF / Word 文件' }}</span>
                  <input type="file" accept="application/pdf,.pdf,.doc,.docx,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document" @change="handlePaperFile" />
                </label>
                <input v-model="paperForm.title" placeholder="标题或标题线索" />
                <div class="folder-actions">
                  <button class="primary" :disabled="loading.importingPaperAi || loading.importingPaper || !currentNotebookId" @click="importPaperWithAi">
                    {{ loading.importingPaperAi ? '处理中' : (isPdfFile(paperForm.file) ? 'AI 生成笔记并入库' : '转换长文档并入库') }}
                  </button>
                  <button class="ghost" :disabled="loading.importingPaper || loading.importingPaperAi || !currentNotebookId" @click="importPaper">
                    {{ loading.importingPaper ? '归档中' : (isPdfFile(paperForm.file) ? '使用下方笔记归档' : '直接作为长文档归档') }}
                  </button>
                </div>
                <input v-model="paperForm.summary" placeholder="摘要，可选" />
                <textarea v-model="paperForm.markdownContent" placeholder="已有 Markdown 笔记可放这里，手动归档时会写入阅读区" />
              </div>
            </div>
          </section>
        </aside>
      </section>

      <section v-else-if="appView === 'assets'" class="asset-page">
        <header class="asset-command">
          <div class="asset-command-copy">
            <span>资产管家</span>
            <h1>像文件管理器一样管理 Atlas 原文件</h1>
            <p>按知识库、类型和关键词快速定位资料；选中后可打开原文件、跳转笔记或导出当前库。</p>
          </div>
          <div class="asset-command-actions">
            <ASelect
              :model-value="currentNotebookId"
              :options="notebookSelectOptions"
              placeholder="选择知识库"
              size="sm"
              @update:model-value="selectNotebookForAssets"
            />
            <button class="ghost" :disabled="loading.assets" @click="loadAssetSummary">{{ loading.assets ? '刷新中' : '刷新资产' }}</button>
            <button class="primary" :disabled="!currentNotebookId || loading.exportingLibrary || notebookOverviewStats.files === 0" @click="exportCurrentLibraryFiles">
              {{ loading.exportingLibrary ? '导出中' : '导出当前库' }}
            </button>
          </div>
          <div class="asset-metric-strip">
            <article>
              <span>总占用</span>
              <strong>{{ formatBytes(assetSummary?.totalBytes || 0) }}</strong>
            </article>
            <article>
              <span>可清理</span>
              <strong>{{ formatBytes(assetSummary?.reclaimableBytes || 0) }}</strong>
            </article>
            <article>
              <span>活跃文件</span>
              <strong>{{ assetSummary?.activeFiles || 0 }}</strong>
            </article>
            <article>
              <span>图片资产</span>
              <strong>{{ assetSummary?.imageFiles || 0 }}</strong>
            </article>
          </div>
        </header>

        <section class="asset-shell">
          <aside class="asset-ledger">
            <div class="asset-panel-head">
              <div>
                <span>空间账单</span>
                <strong>{{ assetSummary?.buckets?.length || 0 }} 个来源</strong>
              </div>
              <small>{{ assetSummary?.trashItems || 0 }} 个回收站项目</small>
            </div>
            <div class="space-bucket-list">
              <article v-for="bucket in assetSummary?.buckets || []" :key="bucket.key" class="space-bucket-card" :class="{ reclaimable: bucket.reclaimable }">
                <div class="bucket-row">
                  <strong>{{ bucket.label }}</strong>
                  <span>{{ bucket.count }} 项</span>
                  <b>{{ formatBytes(bucket.bytes) }}</b>
                </div>
                <div class="bucket-meter">
                  <i :style="{ width: `${assetBucketPercent(bucket)}%` }"></i>
                </div>
                <p>{{ bucket.description }}</p>
              </article>
            </div>
          </aside>

          <section class="asset-gallery-panel">
            <div class="asset-gallery-head asset-browser-head">
              <div>
                <span>资产浏览器</span>
                <h2>{{ assetScope === 'current' ? (currentNotebook?.path || currentNotebook?.name || '当前知识库') : '全部知识库' }}</h2>
              </div>
              <small>{{ assetItems.length }} 个文件 · {{ formatBytes(assetTotalSize) }}</small>
            </div>

            <div class="asset-browser-tools">
              <div class="segmented asset-scope-toggle">
                <button :class="{ active: assetScope === 'current' }" :disabled="!currentNotebookId" @click="assetScope = 'current'; refreshAssetView()">当前库</button>
                <button :class="{ active: assetScope === 'all' }" @click="assetScope = 'all'; refreshAssetView()">全部</button>
              </div>
              <select v-model="assetTypeFilter" @change="refreshAssetView">
                <option v-for="type in assetTypeOptions" :key="type" :value="type">{{ type }}</option>
              </select>
              <select v-model="assetSort">
                <option value="updated-desc">更新时间：新到旧</option>
                <option value="updated-asc">更新时间：旧到新</option>
                <option value="name-asc">名称：A 到 Z</option>
                <option value="name-desc">名称：Z 到 A</option>
                <option value="type-asc">类型：A 到 Z</option>
                <option value="type-desc">类型：Z 到 A</option>
                <option value="size-desc">大小：大到小</option>
                <option value="size-asc">大小：小到大</option>
              </select>
              <input v-model="assetQuery" placeholder="搜索文件名、分类、知识库" @keydown.enter="refreshAssetView" />
              <button class="ghost" :disabled="loading.assets" @click="refreshAssetView">筛选</button>
            </div>

            <div class="asset-type-rail">
              <article v-for="type in assetSummary?.fileTypes || []" :key="type.label">
                <strong>{{ type.label }}</strong>
                <span>{{ type.count }} 个</span>
                <em>{{ formatBytes(type.bytes) }}</em>
              </article>
              <p v-if="!assetSummary?.fileTypes?.length" class="empty">还没有可统计的资料文件。</p>
            </div>

            <div class="asset-bulk-bar" :class="{ active: selectedAssetItems.length > 0 }">
              <label>
                <input type="checkbox" :checked="allVisibleAssetsSelected" :disabled="assetItems.length === 0" @change="toggleAllAssets" />
                <span>{{ allVisibleAssetsSelected ? '取消全选' : '全选当前结果' }}</span>
              </label>
              <div>
                <strong>已选 {{ selectedAssetItems.length }} 个</strong>
                <small>{{ formatBytes(selectedAssetSize) }}</small>
              </div>
              <button class="text-button" :disabled="selectedAssetItems.length === 0 || !!assetBulkAction" @click="clearAssetSelection">清空选择</button>
              <button class="ghost" :disabled="selectedAssetItems.length === 0 || !!assetBulkAction" @click="exportSelectedAssets">
                {{ assetBulkAction === 'export' ? '打包中' : '导出选中' }}
              </button>
              <button class="danger-button" :disabled="selectedAssetItems.length === 0 || !!assetBulkAction" @click="trashSelectedAssets">
                {{ assetBulkAction === 'trash' ? '处理中' : '移入回收站' }}
              </button>
            </div>

            <div class="asset-workbench">
              <div class="asset-list-panel">
                <div
                  v-for="item in sortedAssetItems"
                  :key="item.key"
                  class="asset-row"
                  :class="{ active: selectedAsset?.key === item.key, checked: selectedAssetKeys.has(item.key) }"
                  role="button"
                  tabindex="0"
                  @click="selectAsset(item)"
                  @keydown.enter="selectAsset(item)"
                  @dblclick="openAssetFile(item)"
                >
                  <label class="asset-row-check" @click.stop>
                    <input type="checkbox" :checked="selectedAssetKeys.has(item.key)" @change="toggleAssetSelection(item)" />
                    <span></span>
                  </label>
                  <span class="asset-row-icon" :class="{ image: item.image }">
                    <img v-if="item.image && assetImageUrls[item.key]" :src="assetImageUrls[item.key]" :alt="item.originalFilename || item.title" loading="lazy" />
                    <i v-else>{{ item.typeLabel }}</i>
                  </span>
                  <span class="asset-row-main">
                    <strong>{{ item.originalFilename || item.title }}</strong>
                    <em>{{ item.notebookPath || '未归属知识库' }}</em>
                  </span>
                  <span class="asset-row-meta">
                    <b>{{ formatBytes(item.fileSize || 0) }}</b>
                    <small>{{ item.category || '未分类' }}</small>
                  </span>
                </div>
                <p v-if="assetItems.length === 0" class="empty">没有匹配的资产。试试切换到“全部”或清空筛选词。</p>
              </div>

              <aside class="asset-detail-panel">
                <template v-if="selectedAsset">
                  <div class="asset-detail-preview" :class="{ image: selectedAsset.image }">
                    <img v-if="selectedAsset.image && assetImageUrls[selectedAsset.key]" :src="assetImageUrls[selectedAsset.key]" :alt="selectedAsset.originalFilename || selectedAsset.title" />
                    <span v-else>{{ selectedAsset.typeLabel }}</span>
                  </div>
                  <div class="asset-detail-title">
                    <span>{{ selectedAsset.typeLabel }} · {{ formatBytes(selectedAsset.fileSize || 0) }}</span>
                    <strong>{{ selectedAsset.originalFilename || selectedAsset.title }}</strong>
                  </div>
                  <dl class="asset-detail-grid">
                    <dt>知识库</dt>
                    <dd>{{ selectedAsset.notebookPath || '未归属' }}</dd>
                    <dt>库内路径</dt>
                    <dd>{{ selectedAsset.category || '未分类' }}</dd>
                    <dt>状态</dt>
                    <dd>{{ selectedAsset.status || 'READY' }}</dd>
                    <dt>更新时间</dt>
                    <dd>{{ selectedAsset.updatedAt || selectedAsset.createdAt || '未知' }}</dd>
                  </dl>
                  <div class="asset-detail-actions">
                    <button class="primary" @click="openAssetFile(selectedAsset)">打开原文件</button>
                    <button class="ghost" :disabled="!selectedAsset.noteId" @click="jumpToAssetNote(selectedAsset)">跳转笔记</button>
                  </div>
                </template>
                <p v-else class="empty">选择一个资产查看详情。</p>
              </aside>
            </div>

            <div class="asset-image-grid compact">
              <button v-for="item in assetImageItems.slice(0, 18)" :key="item.key" class="asset-image-card" @click="selectAsset(item)">
                <span class="asset-thumb">
                  <img v-if="assetImageUrls[item.key]" :src="assetImageUrls[item.key]" :alt="item.originalFilename || item.title" loading="lazy" />
                  <i v-else>{{ item.typeLabel }}</i>
                </span>
                <span class="asset-image-meta">
                  <strong>{{ item.originalFilename || item.title }}</strong>
                  <em>{{ item.category || '未分类' }}</em>
                </span>
              </button>
            </div>
          </section>
        </section>
      </section>

      <section v-else-if="appView === 'inbox'" class="inbox-page">
        <header class="vcp-hero inbox-hero">
          <div>
            <span>外部投递箱</span>
            <h1>先读说明，再确认计划，最后入库</h1>
            <p>其他项目发来的资料不会直接污染知识库。Atlas 优先读取 README/说明文件制定入库计划，只在你确认后写入一个目标知识库。</p>
          </div>
          <div class="vcp-actions">
            <button class="ghost" :disabled="loading.inbox" @click="loadInboxRequests">{{ loading.inbox ? '刷新中' : '刷新' }}</button>
            <button class="primary" :disabled="!selectedInboxRequest || loading.inbox || loading.inboxPlanning || selectedInboxFileCount === 0" @click="acceptInboxRequest">
              {{ loading.inbox ? '处理中' : (inboxImportMode === 'ai' ? '按计划确认入库' : '手动确认入库') }}
            </button>
          </div>
        </header>

        <section class="inbox-shell">
          <aside class="inbox-master">
            <div class="section-title">
              <span>待审请求</span>
              <small>{{ inboxRequests.length }} 条</small>
            </div>
            <p v-if="inboxRequests.length === 0" class="empty">暂无外部项目投递。</p>
            <button
              v-for="item in inboxRequests"
              :key="item.id"
              class="inbox-request-card"
              :class="{ active: selectedInboxRequestId === item.id }"
              @click="selectInboxRequest(item)"
            >
              <strong>{{ item.title }}</strong>
              <span>{{ item.sourceProject }} · {{ item.files?.length || 0 }} 个文件 · {{ inboxStatusName(item.status) }}</span>
              <small>{{ item.updatedAt || item.createdAt }}</small>
            </button>
          </aside>

          <main class="inbox-detail">
            <section v-if="!selectedInboxRequest" class="inbox-empty-state">
              <strong>选择一条投递请求开始审核</strong>
              <p>推荐流程：确认文件 -> AI 读取 README 制定计划 -> 你确认目标库和路径 -> 批量入库。</p>
            </section>

            <section v-else class="inbox-review-grid">
              <div class="inbox-review-header">
                <div>
                  <span>{{ selectedInboxRequest.sourceProject }}</span>
                  <h2>{{ selectedInboxRequest.title }}</h2>
                  <p>{{ selectedInboxRequest.description || '这个投递请求没有补充说明。' }}</p>
                </div>
                <em>{{ selectedInboxFileCount }}/{{ inboxSelectableFiles.length }} 个文件已选</em>
              </div>

              <div class="inbox-flow-strip">
                <span>1. 人工确认文件</span>
                <span>2. README 生成计划</span>
                <span>3. 选择目标库和路径</span>
                <span>4. 确认后批量入库</span>
              </div>

              <div class="inbox-import-mode">
                <button :class="{ active: inboxImportMode === 'ai' }" @click="inboxImportMode = 'ai'">
                  AI 辅助入库
                  <small>先读 README 生成计划，再由你确认</small>
                </button>
                <button :class="{ active: inboxImportMode === 'manual' }" @click="inboxImportMode = 'manual'; inboxAiPlan = null">
                  手动指定入库
                  <small>使用下方知识库和分类前缀</small>
                </button>
              </div>

              <div class="inbox-review-controls">
                <label>
                  <span>{{ inboxImportMode === 'ai' ? '目标知识库（可手动调整）' : '目标知识库' }}</span>
                  <select :value="inboxReviewNotebookId || ''" @change="inboxReviewNotebookId = Number(($event.target as HTMLSelectElement).value) || null">
                    <option value="">{{ inboxImportMode === 'ai' ? '不选，由 Atlas 自动匹配一个库' : '选择知识库' }}</option>
                    <option v-for="notebook in collectionNotebookOptions" :key="notebook.id" :value="notebook.id">
                      {{ notebook.path || notebook.name }}
                    </option>
                  </select>
                </label>
                <label>
                  <span>做笔记 Agent</span>
                  <select :value="inboxReviewAgentId || ''" @change="inboxReviewAgentId = Number(($event.target as HTMLSelectElement).value) || ''">
                    <option value="">默认 Agent</option>
                    <option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agent.name }}</option>
                  </select>
                </label>
                <label class="inbox-note-toggle">
                  <span>入库后生成 Agent 笔记 / VCP 草稿</span>
                  <input type="checkbox" v-model="inboxGenerateNotes" />
                </label>
                <label>
                  <span>{{ inboxImportMode === 'ai' ? '分类前缀（AI 模式自动生成）' : '分类前缀' }}</span>
                  <input v-model="inboxCategoryPrefix" placeholder="例如：表情包/人物表情包_202606151230" />
                </label>
              </div>

              <div class="inbox-plan-actions">
                <button class="primary" :disabled="loading.inboxPlanning || selectedInboxFileCount === 0" @click="planInboxRequest">
                  {{ loading.inboxPlanning ? '规划中' : 'AI 制定入库计划' }}
                </button>
                <small>只发送 README/说明文件和文件清单，不发送图片内容。</small>
              </div>

              <div v-if="inboxPlanPreview" class="inbox-plan-preview">
                <div>
                  <span>本次入库计划</span>
                  <strong>{{ inboxPlanPreview.notebookName }}</strong>
                </div>
                <p>{{ inboxPlanPreview.summary }}</p>
                <small>库内路径：{{ inboxPlanPreview.category }}</small>
                <small v-if="inboxPlanPreview.readmeFilename">读取说明：{{ inboxPlanPreview.readmeFilename }}</small>
                <ol v-if="inboxPlanPreview.steps?.length">
                  <li v-for="step in inboxPlanPreview.steps" :key="step">{{ step }}</li>
                </ol>
                <AiTracePanel v-if="inboxAiPlan?.aiTrace?.length" :trace="inboxAiPlan.aiTrace" />
              </div>

              <div class="inbox-file-tools">
                <label class="inbox-check-all">
                  <input
                    type="checkbox"
                    :checked="inboxSelectableFiles.length > 0 && selectedInboxFileCount === inboxSelectableFiles.length"
                    @change="toggleAllInboxFiles(($event.target as HTMLInputElement).checked)"
                  />
                  <span>选择全部可入库文件</span>
                </label>
                <button class="ghost" :disabled="loading.inbox" @click="rejectInboxRequest">驳回整条请求</button>
              </div>

              <div class="inbox-file-list">
                <label v-for="file in selectedInboxRequest.files" :key="file.id" class="inbox-file-row" :class="`status-${file.status}`">
                  <input
                    type="checkbox"
                    :disabled="file.status === 'imported' || file.status === 'skipped'"
                    :checked="selectedInboxFileIds.has(file.id)"
                    @change="toggleInboxFile(file.id, ($event.target as HTMLInputElement).checked)"
                  />
                  <span>
                    <strong>{{ file.originalFilename }}</strong>
                    <em>{{ file.relativePath || file.originalFilename }} · {{ formatBytes(file.fileSize || 0) }} · {{ inboxStatusName(file.status) }}</em>
                    <small v-if="file.errorMessage">{{ file.errorMessage }}</small>
                  </span>
                </label>
              </div>
            </section>
          </main>
        </section>
      </section>

      <DeepWikiPage
        v-else-if="appView === 'deepwiki'"
        :current-notebook="currentNotebook"
        :notebook-options="notebookSelectOptions"
        :current-notebook-id="currentNotebookId"
        :mode="deepWikiMode"
        :focus="deepWikiFocus"
        :result="deepWikiResult"
        :stats="notebookOverviewStats"
        :agents="agents"
        :selected-agent-id="selectedDeepWikiAgentId"
        :loading="loading.deepwiki"
        :asking="loading.asking"
        :rendered-markdown="renderedDeepWiki"
        :suggested-questions="deepWikiSuggestedQuestions"
        :source-links="deepWikiSourceLinks"
        :question="deepWikiQuestion"
        :answer="deepWikiAnswer"
        :citations="deepWikiCitations"
        @update:mode="deepWikiMode = $event"
        @update:focus="updateDeepWikiFocus"
        @update:selected-agent-id="selectedDeepWikiAgentId = $event"
        @update:question="deepWikiQuestion = $event"
        @select-notebook="selectNotebookForDeepWiki"
        @generate="generateDeepWiki"
        @generate-topic="generateTopicFromEntry"
        @ask="askDeepWiki"
        @ask-suggestion="useDeepWikiQuestion"
        @load-latest="loadDeepWikiLatest"
        @link-click="handleDeepWikiLink"
      />

      <VcpCenter
        v-else-if="appView === 'vcp'"
        :agents="agents"
        :selected-agent-id="selectedVcpAgentId"
        :loading="loading.vcp"
        :notebook-search-loading="loading.vcpNotebookSearch"
        :status="vcpStatus"
        :notebooks="vcpNotebooks"
        :notebook-query="vcpNotebookQuery"
        :notebook-results="vcpNotebookResults"
        :new-notebook="vcpNewNotebook"
        :selected-notebook="selectedVcpNotebook"
        :drafts="vcpDrafts"
        :suggestion="vcpBatchSuggestion"
        :files="vcpFiles"
        :selected-file="selectedVcpFile"
        :selected-file-names="selectedVcpFileNames"
        :selected-draft-ids="selectedVcpDraftIds"
        :file-content="vcpFileContent"
        :transfer-target="vcpTransferTarget"
        :transfer-overwrite="vcpTransferOverwrite"
        :transfer-move-synced="vcpTransferMoveSynced"
        :transfer-delete-source="vcpTransferDeleteSource"
        :transfer-loading="loading.vcpTransfer"
        :transfer-log="vcpTransferLog"
        :rendered-suggestion="markdownToHtml(vcpBatchSuggestion)"
        @update:selected-agent-id="selectedVcpAgentId = $event"
        @update:status="vcpStatus = $event"
        @update:notebook-query="vcpNotebookQuery = $event"
        @update:new-notebook="vcpNewNotebook = $event"
        @update:selected-notebook="selectedVcpNotebook = $event"
        @update:selected-file="selectedVcpFile = $event"
        @update:file-content="vcpFileContent = $event"
        @update:transfer-target="vcpTransferTarget = $event"
        @update:transfer-overwrite="vcpTransferOverwrite = $event"
        @update:transfer-move-synced="vcpTransferMoveSynced = $event"
        @update:transfer-delete-source="vcpTransferDeleteSource = $event"
        @refresh="loadVcpCenter"
        @ask-agent="askVcpSyncAgent"
        @ensure-agents="ensureAtlasSystemAgents"
        @search-notebooks="searchVcpNotebooks"
        @select-notebook="selectVcpNotebook"
        @create-notebook="createVcpNotebook"
        @delete-notebook="deleteVcpNotebook"
        @load-files="loadVcpFiles"
        @update-draft="updateVcpDraft"
        @sync-draft="syncVcpDraft"
        @suggest-draft="suggestVcpDraftTarget"
        @delete-draft="deleteVcpDraft"
        @open-file="openVcpFile"
        @save-file="saveVcpFile"
        @delete-file="deleteVcpFile"
        @toggle-file-selection="toggleVcpFileSelection"
        @toggle-all-files="toggleAllVcpFiles"
        @toggle-draft-selection="toggleVcpDraftSelection"
        @toggle-all-drafts="toggleAllVcpDrafts"
        @transfer-files="transferSelectedVcpFiles"
        @transfer-notebook="transferVcpNotebookContents"
        @transfer-drafts="transferSelectedVcpDrafts"
      />

      <section v-else class="trash-page">
        <header class="vcp-hero">
          <div>
            <span>回收站</span>
            <h1>误删内容可以在这里复原</h1>
            <p>笔记和资料删除后默认保留 30 天。需要立即清空时，可以在这里一次性彻底删除。</p>
          </div>
          <div class="vcp-actions">
            <button class="ghost" :disabled="loading.trash" @click="loadTrash">刷新</button>
            <button class="ghost" :disabled="loading.trash || trashItems.length === 0" @click="purgeExpiredTrash">清理过期</button>
            <button class="danger-button" :disabled="loading.trash || trashItems.length === 0" @click="purgeAllTrash">全部删除</button>
          </div>
        </header>

        <section class="trash-list">
          <article v-for="item in trashItems" :key="`${item.kind}-${item.id}`" class="trash-item">
            <div>
              <strong>{{ item.title }}</strong>
              <span>{{ trashKindLabel(item.kind) }} · 删除时间 {{ item.deletedAt }} · 到期 {{ item.purgeAfter }}</span>
              <p>{{ item.detail || '无补充信息' }}</p>
            </div>
            <div class="trash-actions">
              <button class="ghost" @click="restoreTrashItem(item)">复原</button>
              <button class="danger-button" @click="purgeTrashItem(item)">彻底清除</button>
            </div>
          </article>
          <p v-if="trashItems.length === 0" class="empty">回收站是空的。</p>
        </section>
      </section>

      <transition name="fade">
        <div v-if="toast" :class="['toast', toast.type]">{{ toast.text }}</div>
      </transition>

      <AiSettings
        v-if="showAiSettings"
        :request="request"
        @close="showAiSettings = false"
        @message="(text, type = 'info') => notify(type, text)"
      />

      <section v-if="deleteTarget" class="delete-overlay">
        <div class="delete-dialog">
          <header>
            <strong>删除确认</strong>
            <button class="ghost" @click="deleteTarget = null">关闭</button>
          </header>
          <p>{{ deleteTarget.title }}</p>
          <div v-if="deleteTarget.kind === 'notebook'" class="delete-actions single">
            <button class="danger-button" @click="confirmDelete('all')">删除知识库和全部内容</button>
          </div>
          <div v-else class="delete-actions">
            <button class="ghost" @click="confirmDelete('note')">只删笔记</button>
            <button class="ghost" @click="confirmDelete('files')">只删资料</button>
            <button class="danger-button" @click="confirmDelete('all')">全部删除</button>
          </div>
        </div>
      </section>

      <section v-if="notebookEditTarget" class="delete-overlay">
        <div class="delete-dialog notebook-edit-dialog">
          <header>
            <strong>编辑知识库</strong>
            <button class="ghost" @click="notebookEditTarget = null">关闭</button>
          </header>
          <label>
            <span>名称</span>
            <input v-model="notebookEditForm.name" placeholder="知识库名称" />
          </label>
          <label>
            <span>介绍</span>
            <textarea v-model="notebookEditForm.description" rows="5" placeholder="写给自己看的说明：这里收什么资料、怎么使用、何时同步到 VCP。" />
          </label>
          <div class="delete-actions single">
            <button class="primary" @click="saveNotebookEdit">保存修改</button>
          </div>
        </div>
      </section>

      <section v-if="showMatchDetails" class="match-overlay">
        <div class="match-page">
          <header class="match-header">
            <div>
              <strong>命中详情</strong>
              <span>“{{ keyword }}” · {{ matchDetails.length }} 篇文档</span>
            </div>
            <button class="ghost" @click="showMatchDetails = false">关闭</button>
          </header>

          <div class="match-list">
            <article v-for="detail in matchDetails" :key="detail.noteId" class="match-doc">
              <div class="match-doc-head">
                <button class="text-button" @click="openHit({ noteId: detail.noteId })">{{ detail.title || '未命名笔记' }}</button>
                <em>{{ detail.totalMatches }} 处命中</em>
              </div>
              <div class="match-excerpts">
                <section v-for="(excerpt, index) in detail.excerpts" :key="`${detail.noteId}-${excerpt.field}-${index}`">
                  <b>{{ excerpt.label }}</b>
                  <p>
                    <template v-for="(part, partIndex) in [highlightedParts(excerpt)]" :key="partIndex">
                      <span>{{ part.before }}</span><mark>{{ part.hit }}</mark><span>{{ part.after }}</span>
                    </template>
                  </p>
                </section>
              </div>
            </article>
            <p v-if="matchDetails.length === 0" class="empty">没有找到精确命中的正文片段</p>
          </div>
        </div>
      </section>

      <section v-if="diagramPreview" class="diagram-preview-overlay">
        <div class="diagram-preview-page">
          <header class="file-preview-header">
            <div class="file-preview-title">
              <strong>{{ diagramPreview.title }}</strong>
              <span>Mermaid 图表 · 大屏阅读</span>
            </div>
            <div>
              <button class="ghost" @click="zoomDiagramPreview(-0.1)">-</button>
              <button class="ghost" @click="zoomDiagramPreview(0.1)">+</button>
              <button class="ghost" @click="diagramPreview.zoom = 1">100%</button>
              <button class="ghost" @click="closeDiagramPreview">关闭</button>
            </div>
          </header>
          <div class="diagram-preview-stage">
            <div class="diagram-preview-canvas" :style="diagramPreviewStyle" v-html="diagramPreview.html"></div>
          </div>
        </div>
      </section>

      <section v-if="filePreview" class="file-preview-overlay">
        <div class="file-preview-page">
          <header class="file-preview-header">
            <div class="file-preview-title">
              <strong>{{ filePreview.title }}</strong>
              <span>{{ previewKind.toUpperCase() }} · {{ filePreview.type || 'unknown' }}</span>
            </div>
            <div>
              <button v-if="previewKind === 'image'" class="ghost" :class="{ active: filePreview.fit === 'contain' }" @click="setPreviewFit('contain')">适配</button>
              <button v-if="previewKind === 'image'" class="ghost" :class="{ active: filePreview.fit === 'width' }" @click="setPreviewFit('width')">适宽</button>
              <button v-if="previewKind === 'image'" class="ghost" :class="{ active: filePreview.fit === 'actual' }" @click="setPreviewFit('actual')">原始</button>
              <button v-if="previewKind === 'image'" class="ghost" @click="zoomPreview(-0.15)">-</button>
              <button v-if="previewKind === 'image'" class="ghost" @click="zoomPreview(0.15)">+</button>
              <a class="text-button" :href="filePreview.url" target="_blank" rel="noopener noreferrer">新窗口打开</a>
              <button class="ghost" @click="closeFilePreview">关闭</button>
            </div>
          </header>
          <div v-if="previewKind === 'image'" class="image-preview-stage">
            <img :src="filePreview.url" :alt="filePreview.title" :style="previewImageStyle" />
          </div>
          <article v-else-if="previewKind === 'text'" class="text-preview-reader prose" v-html="markdownToHtml(filePreview.text || '')"></article>
          <iframe v-else-if="previewKind === 'html'" :src="filePreview.url" sandbox="allow-same-origin allow-scripts allow-popups allow-forms" />
          <object v-else-if="previewKind === 'pdf'" :data="filePreview.url" :type="filePreview.type || 'application/pdf'">
            <a :href="filePreview.url" target="_blank" rel="noopener noreferrer">打开原文件</a>
          </object>
          <object v-else :data="filePreview.url" :type="filePreview.type">
            <a :href="filePreview.url" target="_blank" rel="noopener noreferrer">打开原文件</a>
          </object>
        </div>
      </section>
    </section>

    <section v-if="showNoteHistoryModal" class="history-overlay" @click.self="showNoteHistoryModal = false">
      <div class="history-modal">
        <header class="history-modal__head">
          <div>
            <h3>笔记历史版本</h3>
            <p>每次编辑会自动存档,可随时回滚。回滚前当前内容会先存为新版本。</p>
          </div>
          <button class="ghost nav-btn nav-btn--icon" @click="showNoteHistoryModal = false">
            <svg viewBox="0 0 16 16" width="14" height="14"><path d="M3 3l10 10M13 3L3 13" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
          </button>
        </header>
        <div class="history-modal__body">
          <div v-if="loadingNoteHistory" class="history-loading">
            <ASkeleton :rows="4" />
          </div>
          <AEmpty
            v-else-if="!noteHistory.length"
            title="还没有历史版本"
            description="编辑并保存这篇笔记后,会自动记录历史版本。"
          />
          <ul v-else class="history-list">
            <li v-for="item in noteHistory" :key="item.id" class="history-item">
              <div class="history-item__main">
                <div class="history-item__head">
                  <ABadge variant="primary">v{{ item.noteVersion }}</ABadge>
                  <strong>{{ item.title || '未命名' }}</strong>
                </div>
                <small class="history-item__time">{{ formatHistoryTime(item.createdAt) }}</small>
                <p v-if="item.summaryExcerpt" class="history-item__excerpt">{{ item.summaryExcerpt }}</p>
              </div>
              <button
                class="primary small"
                :disabled="rollingBack"
                @click="rollbackNoteHistory(item.id, item.noteVersion)"
              >
                回滚
              </button>
            </li>
          </ul>
        </div>
      </div>
    </section>
    <AiActivityDrawer />
  </main>
</template>
