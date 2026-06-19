// Atlas 前端共享类型
// 从 App.vue 抽离,供 stores / composables / views 复用

export type SearchMode = 'keyword' | 'semantic' | 'hybrid'
export type AssetSort =
  | 'updated-desc' | 'updated-asc'
  | 'name-asc' | 'name-desc'
  | 'type-asc' | 'type-desc'
  | 'size-asc' | 'size-desc'

export type ToastType = 'success' | 'error' | 'info'
export type Toast = { type: ToastType; text: string }

export type DeleteMode = 'note' | 'files' | 'all'
export type DeleteTarget =
  | { kind: 'note'; id: number; title: string }
  | { kind: 'library'; id: number; title: string; noteId?: number | null }
  | { kind: 'notebook'; id: number; title: string }

export type FileTreeNode = {
  key: string
  name: string
  type: 'folder' | 'note' | 'library'
  children: FileTreeNode[]
  note?: any
  item?: any
}
export type FileTreeRow = FileTreeNode & { level: number; expandable: boolean; expanded: boolean }

export type FolderImportLog = { time: string; type: 'info' | 'success' | 'error'; text: string }
export type AiExchangeLog = { time: string; title: string; request: string; response: string; open: boolean }
export type FolderFailedImport = { path: string; file: File; error: string }

export type DeepWikiResult = {
  notebookId: number
  title: string
  mode: string
  focus: string
  sourceCount: number
  markdown: string
  updatedAt?: string
}

export type SpaceBucket = { key: string; label: string; count: number; bytes: number; reclaimable: boolean; description: string }
export type FileTypeStat = { label: string; count: number; bytes: number }
export type SpaceSummary = {
  totalBytes: number
  reclaimableBytes: number
  activeFiles: number
  trashItems: number
  imageFiles: number
  buckets: SpaceBucket[]
  fileTypes: FileTypeStat[]
}

export type AssetItem = {
  key: string
  source: string
  id: number
  noteId?: number | null
  notebookId?: number | null
  notebookName: string
  notebookPath: string
  title: string
  originalFilename: string
  fileUrl: string
  contentType: string
  fileExt: string
  typeLabel: string
  fileSize: number
  category: string
  image: boolean
  status: string
  createdAt: string
  updatedAt: string
}

export type VcpNotebook = { name: string; fileCount: number; lastModified: string }
export type VcpNotebookSearchResult = VcpNotebook & { score: number; reason: string; snippet: string }
export type VcpFile = { notebook: string; filename: string; size: number; lastModified: string }
export type VcpTransferResult = { sourceNotebook: string; targetNotebook: string; moved: number; skipped: number; messages: string[] }
export type VcpDraft = {
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

export type TrashItem = {
  kind: 'note' | 'library' | 'paper'
  id: number
  noteId: number
  notebookId: number
  title: string
  detail: string
  deletedAt: string
  purgeAfter: string
}

export type NotebookNode = any & { children: NotebookNode[]; level: number; path: string }
export type NotebookTreeRow = NotebookNode & { relativeLevel: number; expandable: boolean; expanded: boolean }
export type SidebarNotebookRow = NotebookNode & { expandable: boolean; expanded: boolean }
export type PlanTreeRow = { key: string; name: string; level: number }
export type SearchTreeRow = any & { level: number; expandable: boolean; expanded: boolean }

export type AppView = 'workspace' | 'assets' | 'inbox' | 'deepwiki' | 'vcp' | 'trash'
export type NoteHistoryItem = {
  id: number
  noteVersion: number
  title: string
  summaryExcerpt: string
  createdAt: string
}
export type FilePreview = {
  title: string
  url: string
  type: string
  text?: string
  zoom: number
  fit: 'contain' | 'width' | 'actual'
}
