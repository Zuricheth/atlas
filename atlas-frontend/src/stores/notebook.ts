import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { request } from '../lib/apiClient'
import { useUiStore } from './ui'
import type { NotebookNode, NotebookTreeRow, SidebarNotebookRow } from '../types'

/**
 * 笔记本(知识库树):currentNotebookId + 树构建/展开/CRUD。
 *
 * 注意:selectNotebook 在原 App.vue 里会同时加载 notes 和 libraryItems,
 * 但那些属于 note/library 域。为避免 notebook store 反向依赖业务域,
 * select() 接受一个 onSelected 回调,由调用方负责刷新 notes/library。
 */
export const useNotebookStore = defineStore('notebook', () => {
  const ui = useUiStore()

  const notebooks = ref<any[]>([])
  const currentNotebookId = ref<number | null>(null)
  const expandedSidebarKeys = ref<Set<string>>(new Set())
  const expandedTreeKeys = ref<Set<string>>(new Set())

  // 编辑/创建表单(reactive,storeToRefs 后可直接 .name 访问)
  const createForm = reactive({ name: '个人资料知识库', description: '导入 TXT、Markdown、HTML 和论文资料，自动整理后进入搜索和问答' })
  const createMode = ref<'top' | 'child'>('top')
  const editTarget = ref<any | null>(null)
  const editForm = reactive({ name: '', description: '' })

  // —— 派生:树 ——
  function typeOf(notebook: any) {
    return notebook?.nodeType || 'collection'
  }

  const rootRows = computed<NotebookNode[]>(() => buildTree(null, 0, ''))
  const flatRows = computed<NotebookNode[]>(() => {
    const out: NotebookNode[] = []
    const walk = (nodes: NotebookNode[]) => {
      for (const n of nodes) {
        out.push(n)
        walk(n.children || [])
      }
    }
    walk(rootRows.value)
    return out
  })
  const sidebarRows = computed<SidebarNotebookRow[]>(() => flattenSidebar(rootRows.value, 0))

  const current = computed(() => {
    return (
      flatRows.value.find((n) => n.id === currentNotebookId.value) ||
      notebooks.value.find((n) => n.id === currentNotebookId.value) ||
      null
    )
  })
  const currentIsCollection = computed(() => !current.value || typeOf(current.value) === 'collection')

  function buildTree(parentId: number | null, level: number, parentPath: string): NotebookNode[] {
    const children = notebooks.value
      .filter((n) => (n.parentId ?? null) === parentId)
      .sort((a, b) => {
        const rank: Record<string, number> = { domain: 0, project: 1, collection: 2 }
        return (rank[typeOf(a)] ?? 2) - (rank[typeOf(b)] ?? 2) || String(a.name).localeCompare(String(b.name), 'zh-Hans-CN')
      })
    return children.map((node) => {
      const path = parentPath ? `${parentPath} / ${node.name}` : node.name
      return { ...node, level, path, children: buildTree(node.id, level + 1, path) }
    })
  }

  function flattenSidebar(nodes: NotebookNode[], level: number): SidebarNotebookRow[] {
    const rows: SidebarNotebookRow[] = []
    for (const node of nodes) {
      const expandable = Array.isArray(node.children) && node.children.length > 0
      const key = `notebook:${node.id}`
      const expanded = !expandable || expandedSidebarKeys.value.has(key)
      rows.push({ ...node, level, expandable, expanded })
      if (expandable && expanded) rows.push(...flattenSidebar(node.children, level + 1))
    }
    return rows
  }

  function flattenInternal(nodes: NotebookNode[], level: number): NotebookTreeRow[] {
    const rows: NotebookTreeRow[] = []
    for (const node of nodes) {
      const expandable = Array.isArray(node.children) && node.children.length > 0
      const key = `notebook:${node.id}`
      const expanded = !expandable || expandedTreeKeys.value.has(key)
      rows.push({ ...node, relativeLevel: level, expandable, expanded })
      if (expandable && expanded) rows.push(...flattenInternal(node.children, level + 1))
    }
    return rows
  }

  function internalRows(nodes?: any[]): NotebookTreeRow[] {
    return flattenInternal(nodes ?? current.value?.children ?? [], 0)
  }

  // —— 展开/折叠 ——
  function toggleSidebar(row: SidebarNotebookRow) {
    if (!row.expandable) return
    const key = `notebook:${row.id}`
    const next = new Set(expandedSidebarKeys.value)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    expandedSidebarKeys.value = next
  }

  function toggleTreeNode(row: NotebookTreeRow) {
    if (!row.expandable) return
    const key = `notebook:${row.id}`
    const next = new Set(expandedTreeKeys.value)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    expandedTreeKeys.value = next
  }

  // —— CRUD ——
  async function loadAll() {
    notebooks.value = await request<any[]>('/notebooks')
  }

  async function create(payload: { name: string; description?: string }, mode: 'top' | 'child') {
    ui.loading.creatingNotebook = true
    try {
      const parentId = mode === 'child' ? currentNotebookId.value : null
      const notebook = await request<any>('/notebooks', {
        method: 'POST',
        body: JSON.stringify({ name: payload.name, description: payload.description || '', parentId, nodeType: 'collection' }),
      })
      await loadAll()
      if (parentId) {
        expandedSidebarKeys.value = new Set(expandedSidebarKeys.value).add(`notebook:${parentId}`)
        expandedTreeKeys.value = new Set(expandedTreeKeys.value).add(`notebook:${parentId}`)
      }
      ui.notify('success', parentId ? '子知识库创建成功' : '知识库创建成功')
      return notebook
    } catch (error) {
      ui.notify('error', error instanceof Error ? error.message : '知识库创建失败')
      return null
    } finally {
      ui.loading.creatingNotebook = false
    }
  }

  async function rename(notebook: any, form: { name: string; description?: string }) {
    try {
      await request<any>(`/notebooks/${notebook.id}`, {
        method: 'PUT',
        body: JSON.stringify({ name: form.name, description: form.description || '' }),
      })
      await loadAll()
      ui.notify('success', '已重命名')
    } catch (error) {
      ui.notify('error', error instanceof Error ? error.message : '重命名失败')
    }
  }

  async function remove(notebookId: number) {
    try {
      await request<void>(`/notebooks/${notebookId}`, { method: 'DELETE' })
      await loadAll()
      if (currentNotebookId.value === notebookId) currentNotebookId.value = null
      ui.notify('success', '已删除知识库')
    } catch (error) {
      ui.notify('error', error instanceof Error ? error.message : '删除失败')
    }
  }

  /** 选中知识库,回调里让调用方刷新 notes/library */
  async function select(notebookId: number, onSelected?: () => void | Promise<void>) {
    currentNotebookId.value = notebookId
    if (onSelected) await onSelected()
  }

  function reset() {
    notebooks.value = []
    currentNotebookId.value = null
    expandedSidebarKeys.value = new Set()
    expandedTreeKeys.value = new Set()
  }

  return {
    notebooks,
    currentNotebookId,
    expandedSidebarKeys,
    expandedTreeKeys,
    createForm,
    createMode,
    editTarget,
    editForm,
    rootRows,
    flatRows,
    sidebarRows,
    current,
    currentIsCollection,
    internalRows,
    typeOf,
    toggleSidebar,
    toggleTreeNode,
    loadAll,
    create,
    rename,
    remove,
    select,
    reset,
  }
})
