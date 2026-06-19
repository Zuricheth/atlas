import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { AppView, Toast, ToastType } from '../types'

/**
 * 全局 UI 状态:toast / 当前视图 / 各类弹窗开关 / 集中式 loading。
 * App.vue 启动时绑定,任何 store/composable/组件都可读写。
 */
export const useUiStore = defineStore('ui', () => {
  const toast = ref<Toast | null>(null)
  const appView = ref<AppView>('workspace')
  const showAiSettings = ref(false)
  const showMatchDetails = ref(false)

  const loading = ref({
    creatingNotebook: false,
    saving: false,
    searching: false,
    asking: false,
    importingLibrary: false,
    importingPaper: false,
    importingPaperAi: false,
    exportingLibrary: false,
    autoIngesting: false,
    planningFolder: false,
    importingFolder: false,
    agentNote: false,
    inbox: false,
    inboxPlanning: false,
    deepwiki: false,
    vcp: false,
    vcpNotebookSearch: false,
    vcpTransfer: false,
    trash: false,
    assets: false,
  })

  function notify(type: ToastType, text: string) {
    toast.value = { type, text }
    if (type === 'info' || type === 'success') {
      window.setTimeout(() => {
        if (toast.value?.text === text) toast.value = null
      }, 3000)
    }
  }

  function clearToast() {
    toast.value = null
  }

  return {
    toast,
    appView,
    showAiSettings,
    showMatchDetails,
    loading,
    notify,
    clearToast,
  }
})
