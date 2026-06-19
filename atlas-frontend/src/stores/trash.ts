import { defineStore } from 'pinia'
import { ref } from 'vue'
import { request } from '../lib/apiClient'
import { useUiStore } from './ui'
import type { TrashItem } from '../types'

/**
 * 回收站:列出 / 复原 / 清除 / 清空 / 清过期。
 * restore 时若项目属于当前知识库,通过 onRestore 回调让调用方刷新列表。
 */
export const useTrashStore = defineStore('trash', () => {
  const ui = useUiStore()
  const items = ref<TrashItem[]>([])

  async function load() {
    ui.loading.trash = true
    try {
      items.value = await request<TrashItem[]>('/trash')
    } catch (error) {
      ui.notify('error', error instanceof Error ? error.message : '加载回收站失败')
    } finally {
      ui.loading.trash = false
    }
  }

  async function restore(item: TrashItem, onRestore?: (notebookId: number) => void) {
    try {
      await request<void>(`/trash/${item.kind}/${item.id}/restore`, { method: 'POST' })
      items.value = items.value.filter((entry) => !(entry.kind === item.kind && entry.id === item.id))
      if (onRestore && item.notebookId) onRestore(item.notebookId)
      ui.notify('success', '已从回收站复原')
    } catch (error) {
      ui.notify('error', error instanceof Error ? error.message : '复原失败')
    }
  }

  async function purge(item: TrashItem) {
    try {
      await request<void>(`/trash/${item.kind}/${item.id}`, { method: 'DELETE' })
      items.value = items.value.filter((entry) => !(entry.kind === item.kind && entry.id === item.id))
      ui.notify('success', '已彻底清除')
    } catch (error) {
      ui.notify('error', error instanceof Error ? error.message : '清除失败')
    }
  }

  async function purgeAll() {
    if (items.value.length === 0) return
    if (!window.confirm(`确定彻底删除回收站里的 ${items.value.length} 个项目吗?这个操作不能恢复。`)) return
    ui.loading.trash = true
    try {
      const count = await request<number>('/trash', { method: 'DELETE' })
      items.value = []
      ui.notify('success', `已彻底删除 ${count} 个回收站项目`)
    } catch (error) {
      ui.notify('error', error instanceof Error ? error.message : '清空回收站失败')
    } finally {
      ui.loading.trash = false
    }
  }

  async function purgeExpired() {
    ui.loading.trash = true
    try {
      const count = await request<number>('/trash/expired', { method: 'DELETE' })
      await load()
      ui.notify('success', `已清除 ${count} 个过期回收站项目`)
    } catch (error) {
      ui.notify('error', error instanceof Error ? error.message : '清除过期项目失败')
    } finally {
      ui.loading.trash = false
    }
  }

  function clear() {
    items.value = []
  }

  return { items, load, restore, purge, purgeAll, purgeExpired, clear }
})
